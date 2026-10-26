"""Run independent natural missions across five fixed seeds and archive every actual outcome."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import threading
import time

ROOT=Path(__file__).resolve().parent.parent
SEEDS=[61279,61280,61281,61282,61283]
MISSIONS=['logs','farm','skeleton','coal','diamond']
SUPPLIED_COMMANDS={
    'coal':'支給された道具を使って、石炭を1スタック集めてここのチェストに入れてほしい',
    'diamond':'協力して、支給された道具を使ってダイアモンドを10個採掘してここのチェストに入れてほしい'}
LAUNCH_LOCK=threading.Lock()

def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def read_current(path, started):
    try:
        if path.stat().st_mtime < started:return None
        return json.loads(path.read_text(encoding='utf-8'))
    except (OSError,json.JSONDecodeError):return None

def run_case(seed, mission, destination):
    case=destination/str(seed)/mission
    complete=case/'result.json'
    if complete.exists():
        saved=json.loads(complete.read_text(encoding='utf-8'))
        if saved['state'] not in ('running','infrastructure_failed','setup_failed'):
            return dict(seed=seed,mission=mission,state=saved['state'],resumed=True)
        raise RuntimeError(f'{case} contains an unfinished/invalid run; inspect before rerunning')
    case.mkdir(parents=True,exist_ok=True)
    env=os.environ.copy()
    env['AVATARCRAFT_NATURAL_SEED']=str(seed)
    env['AVATARCRAFT_TARGET_MISSION']=mission
    if env.get('AVATARCRAFT_QA_DIAMOND_TOOLS')=='true' and mission in SUPPLIED_COMMANDS:
        env['AVATARCRAFT_QA_COMMAND_OVERRIDE']=SUPPLIED_COMMANDS[mission]
    env.pop('AVATARCRAFT_NPC_TESTS',None)
    result=ROOT/f'build/npc-targeted-results/{seed}-{mission}.json'
    command=['powershell.exe','-NoProfile','-File',str(ROOT/'tools/dev.ps1'),'-Task','runNpcTargetedGameTest']
    with (case/'server.log').open('wb') as log:
        # The Gradle profile writes one shared argument-file set. Never prepare another launch
        # until the previous JVM has consumed its arguments and emitted fresh case evidence.
        with LAUNCH_LOCK:
            started=time.time()
            process=subprocess.Popen(command,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW)
            boot_deadline=time.monotonic()+240
            while process.poll() is None and read_current(result,started) is None:
                if time.monotonic()>boot_deadline:
                    raise RuntimeError(f'{seed}/{mission}: no initial evidence after 240s; process {process.pid}')
                time.sleep(.25)
            print(json.dumps(dict(event='started',seed=seed,mission=mission,pid=process.pid)),flush=True)
        returncode=process.wait()
    actual=read_current(result,started)
    if actual is None or actual.get('state')=='running':
        raise RuntimeError(f'{seed}/{mission}: server exited {returncode} without final case evidence; see {case}/server.log')
    shutil.copy2(result,complete)
    journal=ROOT/f'run-npc-targeted-qa/{seed}-{mission}/gametestserver/gametestworld/bep_3davater_companion/journal'
    files=[]
    for path in sorted(journal.rglob('*.jsonl')):
        output=case/'journal'/path.relative_to(journal)
        output.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,output)
        files.append(dict(path=str(output.relative_to(destination)),bytes=output.stat().st_size,sha256=digest(output)))
    if not files:raise RuntimeError(f'{seed}/{mission}: no real journal files')
    exchanges=actual.get('planningExchanges',[])
    (case/'planning-exchanges.jsonl').write_text(''.join(json.dumps(e,ensure_ascii=False)+'\n' for e in exchanges),encoding='utf-8')
    meta=dict(seed=seed,mission=mission,state=actual['state'],reason=actual.get('reason',''),
        serverExitCode=returncode,startedAtUtc=datetime.fromtimestamp(started,timezone.utc).isoformat(),
        wallSeconds=time.time()-started,journals=files,resultSha256=digest(complete),
        planningExchanges=len(exchanges),planningSha256=digest(case/'planning-exchanges.jsonl'))
    (case/'archive.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(dict(event='finished',seed=seed,mission=mission,state=actual['state'],
        wallSeconds=meta['wallSeconds'],reason=actual.get('reason','')),ensure_ascii=True),flush=True)
    return meta

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run-id',default='20261026-targeted-v7')
    p.add_argument('--seeds',type=int,nargs='+',default=SEEDS)
    p.add_argument('--missions',nargs='+',choices=MISSIONS,default=MISSIONS)
    p.add_argument('--parallel',type=int,default=5)
    a=p.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]+',a.run_id):raise ValueError('Invalid run ID')
    if not 1<=a.parallel<=5:raise ValueError('Parallel servers must be between 1 and 5')
    destination=ROOT/'build/npc-targeted-archive'/a.run_id
    destination.mkdir(parents=True,exist_ok=True)
    records=[];errors=[]
    def world(seed):
        # A logical failure ends this independent case, not the later independent fixtures.
        # No resource relocation, additional supplies or silent retries of a blocked case.
        for mission in a.missions:
            try:record=run_case(seed,mission,destination)
            except Exception as error:
                record=dict(seed=seed,mission=mission,state='infrastructure_failed',error=str(error))
            records.append(record)
            (destination/f'progress-{seed}.json').write_text(json.dumps([r for r in records if r['seed']==seed],ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    with ThreadPoolExecutor(max_workers=a.parallel) as pool:
        for future in as_completed([pool.submit(world,s) for s in a.seeds]):future.result()
    records.sort(key=lambda r:(r['seed'],a.missions.index(r['mission'])))
    summary=dict(runId=a.run_id,seeds=a.seeds,missions=a.missions,cases=records,
        independentReset=True,plannedCases=len(a.seeds)*len(a.missions))
    (destination/'manifest.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(dict(event='complete',cases=len(records),states={s:sum(r['state']==s for r in records) for s in sorted({r['state'] for r in records})})),flush=True)
    if any(r['state'] in ('infrastructure_failed','setup_failed') for r in records):raise SystemExit(1)

if __name__=='__main__':main()

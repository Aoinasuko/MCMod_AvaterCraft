"""Preserve infrastructure diagnostics and rerun only invalid cases plus all cleaned farm fixtures."""
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import shutil
import run_npc_targeted_campaign as runner

ROOT=Path(__file__).resolve().parent.parent
PRIMARY='20261026-targeted-v7-final'
RECHECK='20261026-targeted-v7-recheck'
REVIEWED='20261026-targeted-v7-reviewed'

def main():
    old=ROOT/'build/npc-targeted-archive'/PRIMARY
    records=json.loads((old/'manifest.json').read_text(encoding='utf-8'))['cases']
    def invalid(record):
        return record['state']=='infrastructure_failed' or record.get('serverExitCode',0)!=0
    destination=old/'infrastructure-diagnostics'
    for record in records:
        if not invalid(record):continue
        case=destination/str(record['seed'])/record['mission']
        if case.exists():continue
        case.mkdir(parents=True)
        current=ROOT/f"build/npc-targeted-results/{record['seed']}-{record['mission']}.json"
        if current.exists():shutil.copy2(current,case/'last-result.json')
        journal=ROOT/f"run-npc-targeted-qa/{record['seed']}-{record['mission']}/gametestserver/gametestworld/bep_3davater_companion/journal"
        if journal.exists():shutil.copytree(journal,case/'journal')
    recheck=ROOT/'build/npc-targeted-archive'/RECHECK
    recheck.mkdir(parents=True,exist_ok=True)
    output=[]
    def world(seed):
        for mission in runner.MISSIONS:
            previous=next(r for r in records if r['seed']==seed and r['mission']==mission)
            if mission!='farm' and not invalid(previous):continue
            output.append(runner.run_case(seed,mission,recheck))
    with ThreadPoolExecutor(max_workers=5) as pool:list(pool.map(world,runner.SEEDS))
    (recheck/'manifest.json').write_text(json.dumps(dict(runId=RECHECK,cases=output),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    final=ROOT/'build/npc-targeted-archive'/REVIEWED
    final.mkdir(parents=True,exist_ok=True)
    provenance=[]
    for record in records:
        seed,mission=record['seed'],record['mission']
        source=RECHECK if mission=='farm' or invalid(record) else PRIMARY
        src=ROOT/'build/npc-targeted-archive'/source/str(seed)/mission
        assert (src/'archive.json').exists(),src
        dst=final/str(seed)/mission
        if not dst.exists():shutil.copytree(src,dst)
        elif runner.digest(dst/'result.json')!=runner.digest(src/'result.json'):
            # Keep the formerly selected evidence instead of overwriting a reviewed case silently.
            superseded=final/'superseded'/str(seed)/mission
            assert dst.resolve().is_relative_to(final.resolve()) and superseded.resolve().is_relative_to(final.resolve())
            assert not superseded.exists(),'Inspect existing superseded evidence before changing it'
            superseded.parent.mkdir(parents=True,exist_ok=True)
            dst.rename(superseded)
            shutil.copytree(src,dst)
        provenance.append(dict(seed=seed,mission=mission,sourceRun=source,resultSha256=runner.digest(src/'result.json')))
    (final/'provenance.json').write_text(json.dumps(dict(cases=provenance,primary=PRIMARY,recheck=RECHECK,
        reason='All farms use single late planting with no pre-command crop drops; only infrastructure failures rerun otherwise.'),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(dict(event='reviewed_complete',cases=len(provenance),folder=str(final))),flush=True)

if __name__=='__main__':main()

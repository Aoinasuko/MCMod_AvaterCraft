"""Three bounded, independent real-planner water-pool trials. No task-success retries."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime,timezone
import collections
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import threading
import time
import zipfile

ROOT=Path(__file__).resolve().parent.parent
RUN='20261026-water-pool-v2'
CASES=['drops','chest','combat']
LOCK=threading.Lock()
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def read(p):return json.loads(p.read_text(encoding='utf-8'))
def fresh(p,start):
    try:return read(p) if p.stat().st_mtime>=start else None
    except (OSError,json.JSONDecodeError):return None

def trial(name):
    folder=ROOT/'build/npc-water-archive'/RUN/name;folder.mkdir(parents=True,exist_ok=True)
    if (folder/'archive.json').exists():return read(folder/'archive.json')
    env=os.environ.copy();env['AVATARCRAFT_WATER_CASE']=name;env.pop('AVATARCRAFT_NPC_TESTS',None)
    current=ROOT/'build/npc-water-results'/name
    with (folder/'server.log').open('wb') as log:
        with LOCK:
            start=time.time()
            process=subprocess.Popen(['powershell.exe','-NoProfile','-File',str(ROOT/'tools/dev.ps1'),'-Task','runNpcWaterGameTest'],
                cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
            deadline=time.monotonic()+180
            while process.poll() is None and fresh(current/'result.json',start) is None:
                if time.monotonic()>deadline:raise TimeoutError(f'{name}: initial evidence missing; inspect server.log')
                time.sleep(.25)
            print(json.dumps(dict(event='started',case=name,pid=process.pid)),flush=True)
        code=process.wait()
    result=fresh(current/'result.json',start)
    if code!=0 or result is None or result['state'] in ('running','setup_failed','infrastructure_failed'):
        raise RuntimeError(f'{name}: infrastructure/setup failure, exit={code}; inspect {folder}/server.log')
    shutil.copy2(current/'result.json',folder/'result.json')
    if (current/'trajectory.jsonl').exists():shutil.copy2(current/'trajectory.jsonl',folder/'trajectory.jsonl')
    journal=ROOT/f'run-npc-water-qa/{name}/gametestserver/gametestworld/bep_3davater_companion/journal'
    sources=[]
    for path in sorted(journal.rglob('*.jsonl')):
        dst=folder/'journal'/path.relative_to(journal);dst.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,dst)
        sources.append(dict(path=str(dst.relative_to(folder)),bytes=dst.stat().st_size,sha256=sha(dst)))
    assert sources,'Missing real journal'
    (folder/'planning-exchanges.jsonl').write_text(''.join(json.dumps(e,ensure_ascii=False)+'\n' for e in result['planningExchanges']),encoding='utf-8')
    meta=dict(case=name,state=result['state'],reason=result['reason'],serverExitCode=code,resultSha256=sha(folder/'result.json'),
        trajectorySha256=sha(folder/'trajectory.jsonl'),planningSha256=sha(folder/'planning-exchanges.jsonl'),journals=sources,
        startedUtc=datetime.fromtimestamp(start,timezone.utc).isoformat(),wallSeconds=time.time()-start)
    (folder/'archive.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(dict(event='finished',case=name,state=result['state'],reason=result['reason'])),flush=True)
    return meta

def summarize():
    folder=ROOT/'build/npc-water-archive'/RUN
    rows=[];classes={};model=set()
    for name in CASES:
        case=folder/name;meta=read(case/'archive.json');r=read(case/'result.json')
        assert sha(case/'result.json')==meta['resultSha256'] and sha(case/'trajectory.jsonl')==meta['trajectorySha256']
        assert sha(case/'planning-exchanges.jsonl')==meta['planningSha256']
        for entry in meta['journals']:assert sha(case/entry['path'])==entry['sha256']
        for key,value in r['runtimeClassSha256'].items():classes.setdefault(key,set()).add(value)
        model.add(r['weightsSha256'])
        records=[json.loads(line) for p in (case/'journal').rglob('*.jsonl') for line in p.read_text(encoding='utf-8').splitlines()]
        starts=[e for e in records if e['kind']=='tool_start'];results=[e for e in records if e['kind']=='tool_result']
        frames=[json.loads(line) for line in (case/'trajectory.jsonl').read_text(encoding='utf-8').splitlines()]
        deaths=[e['details'] for e in records if e['kind']=='death']
        firstGoal=(r['planningExchanges'][0].get('plan') or {}).get('goal',{}) if r['planningExchanges'] else {}
        goalMatches=(firstGoal.get('type')=='defeat' and firstGoal.get('target')=='minecraft:skeleton' and firstGoal.get('count')==1 if name=='combat'
            else firstGoal.get('type')=='deposit' and firstGoal.get('target')=='minecraft:wheat' and firstGoal.get('count')==8
                and firstGoal.get('position')==r['layout']['destination'])
        empty=collections.Counter()
        byId={e['details'].get('actionId'):e for e in results}
        for e in starts:
            result=byId.get(e['details'].get('actionId'))
            if e['text']=='find_blocks' and result and result['details'].get('ok') and result['details'].get('blocks')==[]:
                empty[e['details'].get('block','unknown')]+=1
        row=dict(case=name,state=r['state'],reason=r['reason'],command=r['command'],seconds=r['wallSeconds'],
            initialObservation=r['initialObservation'],lastFrame=r['lastFrame'],mission=r['npcObservation'].get('mission_goal'),
            destinationWheat=r['destinationWheat'],sourceWheat=r['sourceWheat'],carriedWheat=r['carriedWheat'],killedByNpc=r['killedByNpc'],
            actualPlanExchanges=len(r['planningExchanges']),planningRequestsAttempted=r['planningRequestsAttempted'],
            enteredWater=any(f['water'] for f in frames),lowestAir=min(f['air'] for f in frames),
            firstPlanGoal=firstGoal,firstPlanGoalMatchesObjective=goalMatches,emptyBlockSearches=dict(empty),
            inventoryTransferStarted=any(e['text'] in ('withdraw','container_take','deposit','container_put') for e in starts),
            attackStarted=any(e['text'] in ('attack','defeat','hunt') for e in starts),
            minimumHealth=min(f['health'] for f in frames),toolStarts=len(starts),
            toolFailures=[dict(tool=e['text'],result=e['details']) for e in results if not e['details'].get('ok')],
            deaths=deaths,tools=[e['text'] for e in starts],result=f'{name}/result.json',trajectory=f'{name}/trajectory.jsonl')
        rows.append(row)
    assert len(model)==1 and all(len(values)==1 for values in classes.values())
    summary=dict(runId=RUN,cases=rows,weightsSha256=next(iter(model)),runtimeClassSha256={k:next(iter(v)) for k,v in classes.items()},
        codeOrModelChangedDuringTrials=False,onlyWaterConditions=True,dryControlPerformed=False,
        limits=dict(maxSeconds=240,maxActualPlans=6),independentResets=True)
    (folder/'verification.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    labels={'drops':'水中の浮遊アイテム回収→収納','chest':'離れた足場のチェスト回収→収納','combat':'水上のスケルトン1体討伐'}
    table=[];details=[]
    for r in rows:
        amount=f"NPC討伐確認={r['killedByNpc']}" if r['case']=='combat' else f"収納{r['destinationWheat']}/8、所持{r['carriedWheat']}、元庫{r['sourceWheat']}"
        table.append(f"| {labels[r['case']]} | {r['state']} | {amount} | {r['seconds']:.1f}秒 | {r['actualPlanExchanges']}/{r['planningRequestsAttempted']} |")
        failed='\n'.join(f"- `{e['tool']}`: {json.dumps(e['result'],ensure_ascii=False)}" for e in r['toolFailures']) or '- 操作失敗の記録なし。'
        details.append(f"### {labels[r['case']]}\n\n命令: {r['command']}\n\n結果: {r['state']}。{r['reason']}\n\n"
            +f"入水={r['enteredWater']}、最低酸素値={r['lowestAir']}、最低体力={r['minimumHealth']}。NPCの死亡記録: {r['deaths']}。\n\n"
            +f"実行したツール: {', '.join(r['tools']) or 'なし'}\n\n{failed}\n\n"
            +f"[結果JSON](../build/npc-water-archive/{RUN}/{r['result']})・[軌跡](../build/npc-water-archive/{RUN}/{r['trajectory']})\n")
    report=f'''# 水を満たしたプールでの3課題試験（2026-10-26）

現在のNPC実装・v7重み・実接続の上位計画AIを固定し、3つの命令を独立した隔離ワールドで実行した。試験のためのルート・アイテム移動・攻撃の代行はしていない。

| 課題 | 結果 | 実状態 | 所要時間 | 計画完了／要求 |
| --- | --- | --- | --- | --- |
{chr(10).join(table)}

## 試験地形と条件

内側24×16ブロック、深さ4ブロックのプールを水源で満たし、底・縁を石で封じた。水面のブロック層に木の足場を浮かべた。大きな足場は5×5が2つ、その間に7〜8マスの水だけの区間があり、別に2×2の小さな足場がある。
開始足場に空の納品チェスト、離れた足場に回収元チェストを置く。NPCと指示者は開始足場上で開始し、指示者の視線を納品チェストへ向けて「ここのチェスト」を保存する。実座標は各結果JSONのlayoutに保存。
ソロNPC・食料16個、道具・持ち込み足場ブロックなし。地形上の木を実際に掘ることは許す。必要なものを試験中に追加支給しない。

- 浮遊アイテム: 水中に小麦4個の実ItemEntityを2つ、合計8個。通常の浮上・移動・拾得遅延を使い、寿命は改変しない。水が安定してから配置。
- チェスト: 回収元に小麦8個、納品先は空。元庫の減少・NPCの所持・納品先8個を検査する。
- 戦闘: 離れた木の足場に実体のスケルトン1体、弓あり。NPCにダイヤ防具一式と剣を支給。NORMAL、夜18000で開始し、敵の移動・射撃・被ダメージは通常どおり。実死亡イベントの原因エンティティが当該NPCであることを検査する。

物理は各Tick50ms待つため最大20TPSで進む。前回の高速Tick試験とは秒数を直接比較しない。各試験は実時間4分または実計画6回で区切る。上位AIに渡すのは従来の通常観測で、計測用の酸素ログを追加のヒントとして与えていない。
半径32の地形観測・16の実体観測、周囲3チャンク（7×7）の常時ロードは維持する。酸素・体力・入水・座標・アイテム数・スケルトンを10Tick間隔で記録した。
全件とも4分上限で終了した。終了時に各1件の計画要求が未完了で、応答待ちを停止した。保存した計画入出力は完了分（4件・2件・2件）で、未完了要求を完了済みと数えていない。時間切れは、時間を延ばしても達成不可能という証明ではない。

## 解釈の限界

これは水のある3場面の観察であり、乾いた同一配置の対照試験はしていない。「水が判断能力を低下させる」という因果関係や成功率を、この各1回だけで断定しない。
モデルが正しく対象を把握したか、選んだツールの前提が満たされていたか、実経路・泳ぎ・回収・攻撃が成立したか、最後に目標を検証できたかを、実計画入力とツール結果・軌跡で分けて確認する。
`passed`だけが実目標と命令終了の両方を満たす。保留、時間切れ、NPC死亡、対象の他因死亡、誤完了は区別している。

## ログと実装から分かる停止箇所

- 最初の計画の対象・必要数・収納先／討伐条件が実目標と一致した件数は{sum(r['firstPlanGoalMatchesObjective'] for r in rows)}/3件。小麦8個と納品先、スケルトン1体を把握できている。
- 3ケースとも入水記録なし、酸素300・体力20を維持した。今回は「入水後に判断が崩れた」事例ではなく、開始足場から水面区間を渡る前の段階で停止している。
- 通常の歩行足場判定`NpcWalkSurface.standable`は水を支持面として認めず、`NpcSkills.walking`の泳ぎ処理は既に入水している場合等に選ばれる。今回選ばれた経路では、陸上から水への移行が成立しなかった。
- 水中アイテム向け`work_route`は`walkingSearch=UNREACHABLE`、編集探索4096ノード上限・設置0／採掘0で失敗する記録がある。これは探索内で実行可能な経路を確定できなかった結果で、地形上のあらゆる横断が不可能という証明ではない。
- チェスト回収・戦闘では、架橋準備から`minecraft:dirt`の検索を各24回繰り返したが見つからない。`NpcWorkRoute`の橋材は土・丸石・石等の6種で、木材は対象外。不足時は土を調達する実装になっている。周囲の木の足場をそのまま橋材として扱う構成ではない。
- 浮遊アイテムの試験でも、後続の道具準備で`minecraft:oak_log`を19回検索したが、あるのは原木ではなく設置済みの板材だった。手順を組み替えて試験内の資源を使う点にも制約がある。
- 同じ足場上の移動にも20秒の無進捗を記録した。移動先が指示者の初期位置に一致する例があり、動的な衝突・到着判定の影響も別に切り分ける必要がある。
- チェスト試験は`withdraw/deposit`開始前、戦闘試験は`attack/defeat`開始前で止まった。今回だけでは、アイテム転送そのものや水中での攻撃命中処理の良否は評価できない。

優先して検討すべきなのは、水陸をつなぐ移動、持ち込み資材なしでの横断方針、橋材の選択と調達失敗時の切り替え、混雑した足場での到着判定である。足場の破壊・再利用を許す範囲は別途方針が必要で、今回の試験中にNPCの実行処理は修正していない。

開始前のv1は地形メタデータの型エラーで命令前に停止した。その3件のログは`build/npc-water-archive/20261026-water-pool-v1/`に保全し、修正後のv2の実試験3件には混ぜていない。

重みSHA-256: `{next(iter(model))}`。3試験で計測した11クラスのハッシュは一致。試験中にコード修正・再学習・失敗ケースの成功するまでの再試行はしていない。
Neoの実プレイ用MOD・セーブは変更していない。ワールドは`run-npc-water-qa/<case>/gametestserver/gametestworld`に残す。

{chr(10).join(details)}
'''
    reportPath=ROOT/'Docs/NpcWaterPoolStudy20261026.md';reportPath.write_text(report,encoding='utf-8')
    archive=ROOT/'build'/f'npc-water-study-{RUN}.zip'
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for p in sorted(folder.rglob('*')):
            if p.is_file():z.write(p,p.relative_to(folder))
        z.writestr('report.md',report.replace(f'../build/npc-water-archive/{RUN}/',''))
    with zipfile.ZipFile(archive) as z:assert z.testzip() is None
    print(json.dumps(dict(event='complete',states={r['case']:r['state'] for r in rows},zip=str(archive),zipBytes=archive.stat().st_size,zipSha256=sha(archive))),flush=True)

def main():
    with ThreadPoolExecutor(max_workers=3) as pool:records=list(pool.map(trial,CASES))
    (ROOT/'build/npc-water-archive'/RUN/'manifest.json').write_text(json.dumps(dict(runId=RUN,cases=records),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    summarize()

if __name__=='__main__':
    if '--summarize-only' in sys.argv:summarize()
    else:main()

"""Summarize independent real-AI runs and export observed outcomes without inventing candidate rewards."""
import argparse
import collections
import gzip
import hashlib
import json
from pathlib import Path
import zipfile

ROOT=Path(__file__).resolve().parent.parent
SEEDS=range(61279,61284)
MISSIONS={'coal':1,'farm':2,'diamond':3,'skeleton':4}
NAMES={'coal':'石炭64収納','farm':'収穫・植え直し','diamond':'協力ダイヤ10収納','skeleton':'協力スケルトン5討伐'}

def sha(data):return hashlib.sha256(data).hexdigest()

def observations(records, source, raw_hash, seed, mission, state):
    results={r['details'].get('actionId'):r for r in records if r['kind']=='tool_result'}
    for index,record in enumerate(records):
        if record['kind']!='local_policy':continue
        details=record['details'];chosen=details['chosen'];action=details['actions'][chosen]
        if not action['tool']:continue
        start=None
        for next_record in records[index+1:]:
            if next_record['kind']=='local_policy':break
            if next_record['kind']=='tool_start' and next_record['text']==action['tool']:
                if all(next_record['details'].get(k)==v for k,v in action['args'].items()):
                    start=next_record;break
        if start is None:continue
        result=results.get(start['details'].get('actionId'))
        if result is None:continue
        yield dict(seed=seed,mission=mission,missionIndex=MISSIONS[mission],caseState=state,
            timestamp=record['timestamp'],goalAction=details.get('goalAction'),goalTarget=details.get('goalTarget'),
            actorPosition=start['details'].get('actorPosition'),
            actor=record['actor'],actorName=record['actorName'],modelVersion=details.get('modelVersion'),
            selector=details.get('selector'),sourceFile=source,sourceSha256=raw_hash,policyLine=index+1,
            actionId=start['details']['actionId'],candidates=details['candidates'],actions=details['actions'],
            chosen=chosen,observedMask=[i==chosen for i in range(len(details['actions']))],
            result=result['details'],outcome=result['details'].get('ok',False),
            scope='Observed chosen operation only; no outcome or reward assigned to unexecuted alternatives.')

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run-id',default='20261026-targeted-v6')
    p.add_argument('--partial',action='store_true')
    a=p.parse_args();folder=ROOT/'build/npc-targeted-archive'/a.run_id
    rows=[];observed=[];planning=[];files=[]
    for seed in SEEDS:
        for mission in MISSIONS:
            case=folder/str(seed)/mission
            if not (case/'archive.json').exists():
                if a.partial:continue
                raise RuntimeError(f'Unfinished or missing archive: {seed}/{mission}')
            meta=json.loads((case/'archive.json').read_text(encoding='utf-8'))
            result=json.loads((case/'result.json').read_text(encoding='utf-8'))
            if result['state'] in ('running','setup_failed','infrastructure_failed'):
                raise RuntimeError(f'Invalid final evidence: {seed}/{mission}')
            all_records=[];case_observed=[]
            for path in sorted((case/'journal').rglob('*.jsonl')):
                raw=path.read_bytes();records=[json.loads(line) for line in raw.decode('utf-8').splitlines() if line.strip()]
                all_records.extend(records)
                case_observed.extend(observations(records,str(path.relative_to(folder)),sha(raw),seed,mission,result['state']))
                files.append(dict(path=str(path.relative_to(folder)),bytes=len(raw),sha256=sha(raw)))
            actors=collections.defaultdict(collections.Counter)
            for r in all_records:
                if r['kind']=='tool_result' and r['details'].get('ok'):
                    actors[r['actorName']][r['text']]+=1
            team_events=[dict(kind=r['kind'],actor=r['actorName'],details=r['details']) for r in all_records if r['kind'].startswith('team_')]
            fighting=[actor for actor,counts in actors.items() if any(counts[t] for t in ('attack','defeat','hunt'))]
            supporting=[actor for actor,counts in actors.items() if counts['give_item']>0]
            errors=collections.Counter(r['details'].get('error','') for r in all_records if r['kind']=='tool_result' and not r['details'].get('ok'))
            failure_details=[dict(actor=r['actorName'],tool=r['text'],result=r['details']) for r in all_records
                if r['kind']=='tool_result' and not r['details'].get('ok')][-20:]
            row=dict(seed=seed,mission=mission,state=result['state'],reason=result.get('reason',''),
                policyVersion=result['policyVersion'],weightsSha256=result['weightsSha256'],
                placedFarmCrops=len(result.get('farmCrops',[])),placedSkeletons=len(result.get('skeletons',[])),
                initialItems=result.get('initialObservation',{}).get('item_counts',{}),
                instruction=(result.get('outcomes') or [{}])[0].get('instruction',
                    next((r['text'] for r in all_records if r['kind']=='campaign_mission'),'')),
                wallSeconds=result['wallSeconds'],planningRequests=result.get('planningRequests',0),
                coalInChest=result.get('coalInChest',0),diamondsInChest=result.get('diamondsInChest',0),
                cropsChangedFromMature=result.get('cropsHarvested',0),cropsReplanted=result.get('cropsReplanted',0),
                wheatInBag=result.get('leaderState',{}).get('item_counts',{}).get('minecraft:wheat',0),
                npcSkeletonKills=result.get('npcSkeletonKills',0),observedActions=len(case_observed),
                successfulOperationsByActor={k:dict(v) for k,v in actors.items()},fightingActors=fighting,
                supplyDeliveringActors=supporting,teamEvents=team_events,
                cooperationEvidence='both-actors-fought' if len(fighting)>=2 else 'actual-supply-delivery' if supporting and team_events else 'delegation-recorded' if team_events else 'not-recorded',
                toolFailures=dict(errors),lastToolFailures=failure_details,
                resultFile=str((case/'result.json').relative_to(folder)),archive=meta)
            rows.append(row);observed.extend(case_observed)
            for e in result.get('planningExchanges',[]):
                planning.append(dict(seed=seed,mission=mission,caseState=result['state'],**e,
                    scope='Actual planner input and reply. Case outcome is episode-level; individual plans have no invented correctness label.'))
    summary=dict(runId=a.run_id,seeds=list(SEEDS),plannedCases=20,completedCases=len(rows),
        states=dict(collections.Counter(r['state'] for r in rows)),
        missionResults={m:dict(collections.Counter(r['state'] for r in rows if r['mission']==m)) for m in MISSIONS},
        modelWeightsSha256=sorted({r['weightsSha256'] for r in rows}),cases=rows,journals=files,
        observedActions=len(observed),observedOutcomes=dict(collections.Counter(str(e['outcome']) for e in observed)),
        observedSelectors=dict(collections.Counter(e['selector'] for e in observed)),planningExchanges=len(planning))
    if a.partial:
        print(json.dumps({k:v for k,v in summary.items() if k not in ('cases','journals')},ensure_ascii=True));return
    for name,examples in [('observed-actions',observed),('planning-exchanges',planning)]:
        payload=gzip.compress(''.join(json.dumps(e,ensure_ascii=False,separators=(',',':'))+'\n' for e in examples).encode(),mtime=0)
        (folder/(name+'.jsonl.gz')).write_bytes(payload)
        summary[name]=dict(rows=len(examples),bytes=len(payload),sha256=sha(payload))
    (folder/'verification.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    table=[]
    for seed in SEEDS:
        cells=[]
        for mission in MISSIONS:
            row=next(r for r in rows if r['seed']==seed and r['mission']==mission)
            amount=row['coalInChest'] if mission=='coal' else row['diamondsInChest'] if mission=='diamond' else row['cropsReplanted'] if mission=='farm' else row['npcSkeletonKills']
            label={'passed':'成功','blocked':'保留','failed':'失敗','timeout':'時間切れ','aborted_budget':'予算中断'}.get(row['state'],row['state'])
            cells.append(f'{label}（{amount}）')
        table.append('| '+str(seed)+' | '+' | '.join(cells)+' |')
    details=[]
    for row in rows:
        failures=' / '.join(f'{count}件: {error}' for error,count in sorted(row['toolFailures'].items(),key=lambda v:-v[1])[:3]) or 'なし'
        details.append(f"### {row['seed']} / {NAMES[row['mission']]}\n\n"
            +f"結果: {row['state']}、{row['wallSeconds']:.1f}秒、計画要求{row['planningRequests']}回、観測成否付き操作{row['observedActions']}件。\n\n"
            +f"終了理由: {row['reason'] or '実状態で完了を確認'}\n\n"
            +f"主な操作失敗: {failures}\n\n"
            +f"協力の記録: {row['cooperationEvidence']}。攻撃・討伐の成功操作があるNPC: {', '.join(row['fightingActors']) or 'なし'}。\n\n"
            +f"[結果JSON](../build/npc-targeted-archive/{a.run_id}/{row['resultFile'].replace(chr(92),'/')})\n")
    report=f'''# 石炭・畑・協力採掘・スケルトンの5 seed検証（2026-10-26）

5 seed×4課題の20ケースをすべて実際のMinecraftと上位AIで実行し、操作ログ・計画入出力・最終状態を保存した。
結果の内訳: {summary['states']}。20件の試験実施と20件の目標成功は区別する。

| seed | {' | '.join(NAMES.values())} |
| --- | --- | --- | --- | --- |
{chr(10).join(table)}

括弧内は石炭／ダイヤの実収納数、畑の植え直し確認数、NPCに討伐を帰属できるスケルトン数。
`passed`は依頼の作業計画が終了し、これらの実状態の条件を確認したもの。協力については下記の担当・操作履歴も参照する。
単にNPCが2人いることや計画の委任だけで、両者が戦ったと扱わない。

## 条件

- seedは61279〜61283で固定。各課題を独立した初期状態へリセットし、前の原木・石炭試験の失敗で後続課題を飛ばさない。
- NoiseBasedChunkGeneratorの自然地形。開始点は原点周辺256以内の最初の安全な陸地で、木や鉱石の多さで選ばない。石炭・ダイヤ・木は追加しない。
- GameTestの設定により、追加のモブ自然湧きと天候変化は停止している。
- 石炭はソロ、ダイヤは2人とも道具なしから開始。初期食料は各NPCにパン16個。空の指定チェストを配置する。
- 畑は成熟小麦8株・灌漑・共有アンカー「畑」を配置。追加の種・道具は支給せず、収穫した種を使う。完了には8株の植え直しと小麦8個の所持を確認する。
- 戦闘は実体のスケルトン5体を自然の地表位置に配置。両NPCにダイヤ防具一式・剣を支給し、NORMAL・夜間開始で通常の移動・攻撃・被ダメージを使う。
- 最大98,000Tick／25分。保留・死亡・完了宣言と実状態の不一致・時間切れを分け、停止したケースに資源を足して成功へ変えない。
- 上位AIの実計画要求は各ケース24回を上限とする。61283の戦闘ケースでは5体討伐後も再計画を続けて上限超過後の再試行が止まらず、元の実行中スナップショットを保存して専用QA JVMだけを監視側で停止した。`aborted_budget`は通常完了や資源不在とは区別する。
- 同梱v6重みのSHA-256を記録して固定。テスト用コードに計画の記録と独立ケースの起動・保存を追加した。
- 最初の畑ケースで相対記録先が入れ子の実行ディレクトリに依存する問題を修正し、そのケースの元データを保持して標準の保存先にも退避した。
- Neoの実プレイ用インスタンスは使用せず、専用の隔離したサーバー・ワールドで検証した。

畑の`cropsHarvested`は成熟状態から変化した株の観測カウンタで、踏み荒らし等を含む可能性がある。実収穫の完了は小麦の所持数と植え直しを併せて確認する。
スケルトンの初期位置や、その後の自然地形での落下・水中移動も実験条件の一部として結果JSON・計画観察に記録する。

## ログから確認できた問題

- 畑の範囲収穫で`Protected structure or unbreakable block`となる例がある。`NpcToolQueue.safety`は`dig`対象が`CropBlock`なら成熟度を問わず拒否するため、汎用の範囲作業を選んだ計画と収穫の実行条件が一致していない。
- 植え直しが7/8株の時点で命令を完了する例がある。試験の外側では実状態を検査して失敗にしたが、計画の手順完了と依頼全体の達成判定には差がある。
- 討伐を5体確認した後も計画・担当の終了を確定できず、試験の計画要求予算に達する例がある。討伐数とNPCが正常待機へ戻ったかを分けて扱う必要がある。
- 道具なしの資源採集では、原木の調達・高低差や水際の経路・道具準備で停止する例がある。資源の存在が未確認なことと、移動・準備処理の失敗を同じ理由にしない。

これらは採取時の挙動を示すもので、今回のログ採取に合わせて本体の行動モデルや実行処理を成功するよう書き換えていない。

## 採取データ

観測成否付きの実操作: {len(observed):,}件。内訳: {summary['observedOutcomes']}。
選択方式: {summary['observedSelectors']}。実際の計画要求・応答: {len(planning):,}件。

各`journal/`に実ツール引数、actorPosition、actionId、成否、モデルへの候補特徴量と選択を保存する。
`planning-exchanges.jsonl`に上位AIへ渡した指示・観察・残作業と返答計画・エラーを保存する。
`observed-actions.jsonl.gz`は選択と実引数・actionIdで結果を結合したもの。未実行候補には成否や報酬を付けない。
`planning-exchanges.jsonl.gz`はケースの成否を付けた計画入出力で、個々の中間計画を一律に正解／不正解にしていない。

rawジャーナルのSHA-256とファイル一覧、20ケースの詳細は`verification.json`と`manifest.json`にある。
再学習を行う場合はseedまたはケース単位で分離し、成功した操作の件数をミッションの成功数と混同しない。

## 各ケース

{chr(10).join(details)}
'''
    (ROOT/'Docs/NpcTargetedFiveWorldVerification20261026.md').write_text(report,encoding='utf-8')
    output=ROOT/'build'/f'npc-targeted-logs-{a.run_id}.zip'
    with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for path in sorted(folder.rglob('*')):
            if path.is_file():z.write(path,path.relative_to(folder))
        z.writestr('report.md',report.replace(f'../build/npc-targeted-archive/{a.run_id}/',''))
    print(json.dumps(dict(completedCases=len(rows),states=summary['states'],observedActions=len(observed),planningExchanges=len(planning),zip=str(output),zipBytes=output.stat().st_size,zipSha256=sha(output.read_bytes()))))

if __name__=='__main__':main()

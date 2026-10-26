"""Audit fixed-model five-seed revalidation after radius32/16 sensing and 7x7 loading."""
import argparse
import collections
import gzip
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile
import report_npc_targeted_campaign as prior

ROOT=Path(__file__).resolve().parent.parent
RUN='20261026-spatial32-entities16-chunks7-v7'
TASKS={'logs':'原木64収納','coal':'石炭64収納','farm':'収穫・植え直し','diamond':'協力ダイヤ10収納','skeleton':'協力スケルトン5討伐'}
SEEDS=list(range(61279,61284));prior.MISSIONS['logs']=0
def load(p):return json.loads(p.read_text(encoding='utf-8'))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run-id',default=RUN);p.add_argument('--partial',action='store_true');a=p.parse_args()
    folder=ROOT/'build/npc-targeted-archive'/a.run_id
    cases=[];live=[];observed=[];planning=[];files=[];revisions=collections.defaultdict(set)
    for seed in SEEDS:
        for task in TASKS:
            case=folder/str(seed)/task
            if not (case/'archive.json').exists():
                current=ROOT/f'build/npc-targeted-results/{seed}-{task}.json'
                if (case/'server.log').exists() and current.exists() and current.stat().st_mtime>=(case/'server.log').stat().st_ctime:
                    r=load(current);terrain=r.get('leaderState',{}).get('terrain_32',{})
                    live.append(dict(seed=seed,task=task,state=r['state'],seconds=round(r['wallSeconds']),plans=r.get('planningRequests'),status=r.get('status'),
                        stored=r.get({'logs':'logsInChest','coal':'coalInChest','diamond':'diamondsInChest','farm':'cropsReplanted','skeleton':'npcSkeletonKills'}[task]),
                        terrainComplete=terrain.get('complete_loaded_coverage'),scanMs=terrain.get('scan_milliseconds')))
                continue
            r=load(case/'result.json');meta=load(case/'archive.json')
            assert meta['serverExitCode']==0 and r['state'] not in ('running','infrastructure_failed','setup_failed'),str(case)
            assert sha(case/'result.json')==meta['resultSha256']
            assert sha(case/'planning-exchanges.jsonl')==meta['planningSha256']
            assert (r.get('terrainObservationRadius'),r.get('entityObservationRadius'),r.get('chunkLoadingRadius'))==(32,16,3)
            initial=r['initialObservation'];assert initial['terrain_32']['radius']==32 and initial['entities_16']['radius']==16
            if task=='farm':assert r.get('fixtureMatureCropsAtCommand')==8 and r.get('fixtureCropDropsAtCommand')==0
            records=[];examples=[]
            if not a.partial:
                for file in sorted((case/'journal').rglob('*.jsonl')):
                    sealed=next(e for e in meta['journals'] if Path(e['path'])==file.relative_to(folder))
                    assert sha(file)==sealed['sha256'],'Sealed journal changed'
                    data=[json.loads(l) for l in file.read_text(encoding='utf-8').splitlines() if l.strip()];records.extend(data)
                    examples.extend(prior.observations(data,str(file.relative_to(folder)),sha(file),seed,task,r['state']))
                    files.append(dict(path=str(file.relative_to(folder)),bytes=file.stat().st_size,sha256=sha(file)))
                assert sum(e['kind']=='campaign_mission' for e in records)==1
            operations=collections.defaultdict(collections.Counter);calls=collections.Counter();failures=collections.Counter()
            for e in records:
                if e['kind']=='tool_start':calls[e['text']]+=1
                if e['kind']=='tool_result':
                    if e['details'].get('ok'):operations[e['actorName']][e['text']]+=1
                    else:failures[e['details'].get('error','unknown')]+=1
            fighters=[actor for actor,counts in operations.items() if any(counts[t] for t in ('attack','defeat','hunt'))]
            suppliers=[actor for actor,counts in operations.items() if counts['give_item']]
            cooperation=(len(fighters)>=2 if task=='skeleton' else bool(suppliers) and len(operations)>=2 if task=='diamond' else None)
            mission=r.get('leaderState',{}).get('mission_goal',{})
            if r['state']=='passed':assert mission.get('verified') is True
            row=dict(seed=seed,task=task,state=r['state'],reason=r.get('reason',''),seconds=r['wallSeconds'],planningRequests=r['planningRequests'],
                logs=r.get('logsInChest',0),coal=r.get('coalInChest',0),diamonds=r.get('diamondsInChest',0),replanted=r.get('cropsReplanted',0),
                wheat=r.get('leaderState',{}).get('item_counts',{}).get('minecraft:wheat',0),kills=r.get('npcSkeletonKills',0),mission=mission,
                fightingActors=fighters,supplyDeliveringActors=suppliers,cooperationConfirmed=cooperation,
                fullTaskVerified=r['state']=='passed' and cooperation is not False,toolCalls=dict(calls),toolFailures=dict(failures),
                deaths=[dict(actor=e['actorName'],**e['details']) for e in records if e['kind']=='death'],
                initialTerrain=initial['terrain_32'],finalTerrain=r['leaderState']['terrain_32'],initialEntities=initial['entities_16'],
                observedActions=len(examples),successfulOperationsByActor={name:dict(counts) for name,counts in operations.items()},
                result=str((case/'result.json').relative_to(folder)))
            cases.append(row);observed.extend(examples)
            for name,digest in r['runtimeClassSha256'].items():revisions[name].add(digest)
            for exchange in r['planningExchanges']:
                assert '"terrain_32":' in exchange['input'] and '"entities_16":' in exchange['input'],'Spatial context absent from real planner request'
                planning.append(dict(seed=seed,task=task,caseState=r['state'],**exchange))
    states=dict(collections.Counter(c['state'] for c in cases))
    if a.partial:print(json.dumps(dict(completed=len(cases),planned=25,states=states,live=live)));return
    assert len(cases)==25 and len({(c['seed'],c['task']) for c in cases})==25
    assert len(revisions)==17 and all(len(v)==1 for v in revisions.values()),'Mixed compiled runtime revisions'
    baseline=load(ROOT/'build/npc-targeted-archive/20261026-targeted-v7-reviewed/verification.json')
    model=load(ROOT/'src/npc/resources/data/bep_3davater_companion/ai/action-policy.json')
    assert model['version']==7 and model['sha256']==baseline['weightsSha256']
    for seed in SEEDS:
        for task in TASKS:
            actual=load(folder/str(seed)/task/'result.json')
            previous=load(ROOT/'build/npc-targeted-archive'/baseline['runId']/str(seed)/task/'result.json')
            assert actual['weightsSha256']==model['sha256']
            assert actual['origin']==previous['origin'] and actual['chest']==previous['chest'],'Starting fixture changed'
    tests=collections.Counter()
    for path in (ROOT/'build/test-results/test').glob('TEST-*.xml'):
        suite=ET.parse(path).getroot();tests.update({k:int(suite.get(k,0)) for k in ('tests','failures','errors','skipped')})
    toolCalls=collections.Counter();[toolCalls.update(c['toolCalls']) for c in cases]
    for name,rows in [('observed-actions',observed),('planning-exchanges',planning)]:
        (folder/(name+'.jsonl.gz')).write_bytes(gzip.compress(''.join(json.dumps(e,ensure_ascii=False,separators=(',',':'))+'\n' for e in rows).encode(),mtime=0))
    jar=ROOT/'build/libs/bep_3davater_companion-26.3-0.10.0.jar'
    with zipfile.ZipFile(jar) as compiled:
        for name,values in revisions.items():
            path=('dev/avatarcraft/npc/'+name+'.class' if name in ('CompanionEntity','NpcChunkLoading') else
                  'dev/avatarcraft/npc/ai/'+('NpcCampaignGameTests$Run.class' if name=='Run' else name+'.class'))
            assert hashlib.sha256(compiled.read(path)).hexdigest()==next(iter(values)),'JAR differs from verified runtime'
        assert hashlib.sha256(compiled.read('data/bep_3davater_companion/ai/action-policy.bin')).hexdigest()==model['sha256']
    assert tests['failures']==0 and tests['errors']==0
    assert 'All 124 required tests passed' in (ROOT/'build/npc-spatial-regression-final.log').read_text(encoding='utf-8')
    evidenceFiles={name:ROOT/path for name,path in {
        'tool-contracts.json':'build/npc-tools.json','regression.log':'build/npc-spatial-regression-final.log',
        'build.log':'build/npc-spatial-release-build.log','model-policy.json':'src/npc/resources/data/bep_3davater_companion/ai/action-policy.json'}.items()}
    summary=dict(runId=a.run_id,completedCases=25,states=states,cases=cases,journals=files,unitTests=dict(tests),gameTests=124,
        weightsSha256=model['sha256'],weightBytes=model['weightBytes'],modelRetrained=False,
        runtimeClassSha256={name:next(iter(v)) for name,v in revisions.items()},
        taskResults={task:dict(collections.Counter(c['state'] for c in cases if c['task']==task)) for task in TASKS},
        fullTaskVerified={task:sum(c['fullTaskVerified'] for c in cases if c['task']==task) for task in TASKS},
        toolCalls=dict(toolCalls),observedActions=len(observed),observedOutcomes=dict(collections.Counter(str(e['outcome']) for e in observed)),
        planningExchanges=len(planning),jarSha256=sha(jar),baselineRunId=baseline['runId'],baselineStates=baseline['states'],baselineFullTaskVerified=baseline['fullTaskVerified'])
    summary['supportingEvidence']={name:dict(bytes=path.stat().st_size,sha256=sha(path)) for name,path in evidenceFiles.items()}
    (folder/'verification.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    labels={'passed':'正常終了','blocked':'保留','failed':'未達終了','timeout':'時間切れ'};table=[];comparison=[];details=[]
    for seed in SEEDS:
        cells=[]
        for task in TASKS:
            c=next(c for c in cases if c['seed']==seed and c['task']==task);n=c[{'logs':'logs','coal':'coal','farm':'replanted','diamond':'diamonds','skeleton':'kills'}[task]]
            cells.append(f"{labels.get(c['state'],c['state'])}{'・単独' if c['state']=='passed' and c['cooperationConfirmed'] is False else ''}（{n}）")
        table.append('| '+str(seed)+' | '+' | '.join(cells)+' |')
    for task in TASKS:
        old=sum(c['state']=='passed' for c in baseline['cases'] if c['task']==task);new=sum(c['state']=='passed' for c in cases if c['task']==task)
        comparison.append(f"| {TASKS[task]} | {old}/5 | {new}/5 | {summary['fullTaskVerified'][task]}/5 |")
    for c in cases:
        errors=' / '.join(f'{count}件: {error}' for error,count in sorted(c['toolFailures'].items(),key=lambda p:-p[1])[:3]) or 'なし'
        details.append(f"- {c['seed']} / {TASKS[c['task']]}: {c['state']}、{c['seconds']:.1f}秒、計画要求{c['planningRequests']}回。{c['reason'] or '実状態の目標を確認'}。主な操作失敗: {errors}。"
            +(f" 小麦所持{c['wheat']}個、収穫物回収確認{c['mission'].get('products_collected',False)}。" if c['task']=='farm' else '')
            +(f" 死亡記録: {c['deaths']}。" if c['deaths'] else '')
            +f" 実戦闘担当: {', '.join(c['fightingActors']) or 'なし'}。資材受け渡し担当: {', '.join(c['supplyDeliveringActors']) or 'なし'}。"
            +f" [結果JSON](../build/npc-targeted-archive/{a.run_id}/{c['result'].replace(chr(92),'/')})")
    full=sum(summary['fullTaskVerified'].values());baselineFull=sum(baseline['fullTaskVerified'].values())
    coverage=sum(c['initialTerrain']['complete_loaded_coverage'] for c in cases)
    report=f'''# 周囲32マス地形・16マス実体観測と7×7チャンクの5 seed再検証

5 seed×5課題の25件を再実行した。内訳は{states}。数量・収納条件の正常終了と、協力への実参加は別に確認する。
協力条件も含めて{full}/25件。前回の同じv7重みでは{baselineFull}/25件だった。モデル重みは変えず、今回のログで再学習はしていない。

| seed | {' | '.join(TASKS.values())} |
| --- | --- | --- | --- | --- | --- |
{chr(10).join(table)}

括弧内は指定チェスト内の原木・石炭・ダイヤ個数、植え直した株数、NPCに帰属できる討伐数。植え直し株数だけでは収穫物回収の成功にしない。
「単独」は目標数量まで達しても両NPCの実参加を確認できなかったケース。討伐は両者の攻撃・討伐の成功操作、採掘は複数担当の実操作と資材受け渡しを確認する。

| 課題 | 前回の数量・収納正常終了 | 今回の数量・収納正常終了 | 今回・協力を含む全条件 |
| --- | --- | --- | --- |
{chr(10).join(comparison)}

## 追加内容と範囲

- `observe_terrain()`：自身の足元セルを中心とする半径32の3次元球。ロード済みブロックを非破壊で観測し、未知数・衝突・水・溶岩・実資源座標・足場のつながりからの候補点を返す。遮蔽されたブロックも対象。未ロードを空気や安全な場所とは扱わない。
- 高さ・種別の65×65投影も取得できる。投影は自身に高さが近い足場を1つ示し、地下と地上などの全層を単一の2次元図で表したものではない。接続探索の内部では球内の他層も扱う。探索上限時は`graph_complete=false`を返す。
- `observe_entities()`：自身を中心とする半径16の3次元球。実ID・座標・速度・可視性・敵対性・体力、落下物の種類・個数・拾得遅延を返す。遮蔽も含み、最大128行・総数と省略を明示する。観測だけで拾得・討伐完了にはしない。
- 上位AIの各計画入力に`terrain_32`と`entities_16`を別枠で渡す。詳細が必要なら実ツールで更新する。失敗時は別の接近方向を選び、収穫後は残った実アイテムへの接近・通常拾得の待機・元の目標の再確認を指示した。代わりの小麦を調達して収穫済みを装わない。
- NPCごとの常時Tickロードを中心＋周囲1チャンクの3×3・9枚から、中心＋周囲3チャンクの7×7・49枚へ拡張。移動先を先に確保し、旧範囲を解放する。重なっている別NPCのチケットは解放しない。
- 32マスの地形は呼び出し時に読取り、同じ足元・次元では20Tick未満だけキャッシュする。明示的な地形ツールは再走査。実体情報は毎回取得する。毎Tickの全走査ではない。動的な障害物や地形変更に対して、実行時の経路検証は従来どおり必要。

初期観測で球内のロード済み被覆を全て確認できたのは{coverage}/25件。被覆不足がある時は不明領域として返す。
全{len(planning)}件の実計画入力に新しい2つの観測欄があることを検査した。
明示的なツール開始は`observe_terrain`={toolCalls['observe_terrain']}回、`observe_entities`={toolCalls['observe_entities']}回。
これは自動で渡した観測回数とは別の指標である。

## 条件・比較の限界

seedは61279〜61283、各課題で独立した自然NoiseBasedChunkGeneratorワールドを初期化する。前回と同じ命令・開始地点選定・指定チェスト・食料16個・初期道具なしを使う。木・石炭・ダイヤの追加や有利な場所への移設はない。
畑は前回の最終条件と同じ成熟小麦8株・灌漑・アンカー、事前の作物ドロップなし・種の支給なし。戦闘は実体スケルトン5体、両NPCにダイヤ防具一式と剣、NORMAL・夜間開始。その後の時刻と敵の移動は進む。追加自然湧きと天候変化はGameTest環境で停止。
GameTestは高速Tickで、外部AI待機中は各Tick50ms待つ。実秒数を通常20TPSのクライアント性能と比較しない。上限98,000Tick／25分・実計画24回。時間切れは達成不可能の証明ではない。
上位AIは実接続で、応答には変動がある。地形観測・エンティティ観測・計画指示・ロード範囲を同時に変更したため、それぞれ単独の因果効果や新規seedでの成功率は測定していない。49チャンクは従来の約5.44倍の所有チケット数で、複数NPCの共有範囲は実チャンクが重複する。
新しい観測は到達・回収の成功保証ではない。状態を把握できても、足場変更・安全な接近手順・通常拾得・実収納までの実行に失敗し得る。未達ケースの理由は各JSONと下記を参照。

## 観測によって具体化した回収漏れ

前回保留だったseed 61279の畑は、今回8株の植え直しと小麦8個の回収を満たして正常終了した。観測・計画指示・ロード範囲を同時に変更したため、どれか1つだけの効果とは断定しない。

seed 61281の畑では8株の植え直し後に小麦6個を所持し、残る2個はそれぞれ1個ずつY約62.44の水中に存在する状態を確認した。畑のY=67より約4.6ブロック低い。
1個は可視、もう1個は遮蔽されていたが、半径16の実体観測は両方の座標・個数・水中状態を返した。
この途中の実スナップショットを`61281/farm/water-drop-observation.json`に保全した。所在が把握できたことと、水辺への下降・泳ぎ・拾得が成功したかは分けて扱う。
seed 61282の石炭採集では溺死の実イベント（cause=`drown`、座標[-32,53,81]）を記録した。水を観測する能力だけでは、酸素・退避・安全な接近を含む制御が保証されないことも確認できた。

## 証跡と成果物

- 全25件は同一のコンパイル済み17クラスのハッシュを記録して一致を検査。ケース終了後に原ログ・計画入力・結果を封印しており、行動失敗を成功するまで取り直していない。
- モデルv7、{model['weightBytes']:,} bytes。重みSHA-256 `{model['sha256']}`。前回と一致。
- Minecraft回帰テスト124件成功。JUnit {tests['tests']}件中、スキップ{tests['skipped']}件、失敗{tests['failures']}・エラー{tests['errors']}。
- 観測した選択済み操作{len(observed):,}件。未実行候補に成否・報酬は付けていない。計画入出力{len(planning):,}件。
- 原データと集計: `build/npc-targeted-archive/{a.run_id}/`。`verification.json`、原JSONL、実計画、集計gzipを含む。
- JAR `build/libs/{jar.name}`、SHA-256 `{sha(jar)}`。
- Neoの実プレイ用MOD・セーブは変更していない。今回も隔離サーバーで実行した。

## 各ケース

{chr(10).join(details)}
'''
    reportPath=ROOT/'Docs/NpcSpatialObservationVerification20261026.md';reportPath.write_text(report,encoding='utf-8')
    archive=ROOT/'build'/f'npc-spatial-logs-{a.run_id}.zip'
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for file in sorted(folder.rglob('*')):
            if file.is_file():z.write(file,file.relative_to(folder))
        for name,path in evidenceFiles.items():z.write(path,'evidence/'+name)
        z.writestr('report.md',report.replace(f'../build/npc-targeted-archive/{a.run_id}/',''))
    with zipfile.ZipFile(archive) as z:assert z.testzip() is None
    print(json.dumps(dict(completed=25,states=states,fullTaskVerified=summary['fullTaskVerified'],toolCalls={t:toolCalls[t] for t in ('observe_terrain','observe_entities')},
        observedActions=len(observed),planningExchanges=len(planning),zip=str(archive),zipBytes=archive.stat().st_size,zipSha256=sha(archive))))

if __name__=='__main__':main()

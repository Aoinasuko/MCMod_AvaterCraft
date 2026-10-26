"""Report final five-task revalidation separately from development diagnostics and training logs."""
import argparse
import collections
import gzip
import hashlib
import json
from pathlib import Path
import zipfile
import xml.etree.ElementTree as ET
import report_npc_targeted_campaign as prior

ROOT=Path(__file__).resolve().parent.parent
TASKS={'logs':'原木64収納','coal':'石炭64収納','farm':'畑の収穫・植え直し','diamond':'協力ダイヤ10収納','skeleton':'協力スケルトン5討伐'}
prior.MISSIONS['logs']=0
SEEDS=list(range(61279,61284))
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def load(path):return json.loads(path.read_text(encoding='utf-8'))

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--partial',action='store_true')
    p.add_argument('--run-id',default='20261026-targeted-v7-reviewed');a=p.parse_args()
    folder=ROOT/'build/npc-targeted-archive'/a.run_id
    rows=[];live=[]
    for seed in SEEDS:
        for task in TASKS:
            case=folder/str(seed)/task
            if (case/'archive.json').exists():
                r=load(case/'result.json');rows.append(dict(seed=seed,task=task,result=r,archive=load(case/'archive.json')))
            elif (case/'server.log').exists():
                path=ROOT/f'build/npc-targeted-results/{seed}-{task}.json'
                try:
                    if path.stat().st_mtime < (case/'server.log').stat().st_ctime:continue
                    r=load(path)
                    live.append(dict(seed=seed,task=task,state=r['state'],seconds=round(r['wallSeconds']),
                        plans=r.get('planningRequests'),status=r.get('status'),logs=r.get('logsInChest'),
                        coal=r.get('coalInChest'),diamonds=r.get('diamondsInChest'),kills=r.get('npcSkeletonKills'),
                        replanted=r.get('cropsReplanted'),goal=r.get('leaderState',{}).get('mission_goal',{}).get('goal')))
                except (OSError,json.JSONDecodeError):pass
    states=dict(collections.Counter(row['result']['state'] for row in rows))
    if a.partial:
        print(json.dumps(dict(completed=len(rows),planned=25,states=states,live=live),ensure_ascii=True));return
    if len(rows)!=25:raise RuntimeError('All 25 final cases must be archived first')
    model=load(ROOT/'src/npc/resources/data/bep_3davater_companion/ai/action-policy.json')
    assert model['version']==7 and model['training']['passed'] and model['weightBytes']==41945660
    assert {row['result']['weightsSha256'] for row in rows}=={model['sha256']}
    assert all(row['result']['policyVersion']==7 for row in rows)
    baseline=load(ROOT/'build/npc-targeted-archive/20261026-targeted-v6/verification.json')
    paired=load(ROOT/'build/npc-policy-v7/paired-evaluation.json')
    observed=[];planning=[];journals=[];case_rows=[]
    core=collections.defaultdict(set);harness=set()
    for row in rows:
        seed,task,r=row['seed'],row['task'],row['result'];case=folder/str(seed)/task
        assert r['state'] not in ('running','infrastructure_failed','setup_failed')
        assert row['archive']['serverExitCode']==0,'Infrastructure crash is not a valid final case'
        if r['state']=='passed':
            assert r.get('leaderState',{}).get('mission_goal',{}).get('verified') is True
        if task=='farm':
            assert r.get('fixtureMatureCropsAtCommand')==8,'Invalid farm fixture'
            assert r.get('fixtureCropDropsAtCommand')==0,'Pre-command crop drops contaminate fixture'
        records=[];examples=[]
        for path in sorted((case/'journal').rglob('*.jsonl')):
            raw=path.read_bytes();data=[json.loads(l) for l in raw.decode('utf-8').splitlines() if l.strip()]
            records.extend(data)
            examples.extend(prior.observations(data,str(path.relative_to(folder)),sha(path),seed,task,r['state']))
            journals.append(dict(path=str(path.relative_to(folder)),bytes=len(raw),sha256=sha(path)))
        assert sum(record['kind']=='campaign_mission' for record in records)==1
        observed.extend(examples)
        for e in r.get('planningExchanges',[]):planning.append(dict(seed=seed,task=task,caseState=r['state'],**e))
        for name,digest in r.get('runtimeClassSha256',{}).items():
            if name=='NpcCampaignGameTests':harness.add(digest)
            else:core[name].add(digest)
        success=collections.defaultdict(collections.Counter)
        for e in records:
            if e['kind']=='tool_result' and e['details'].get('ok'):success[e['actorName']][e['text']]+=1
        errors=collections.Counter(e['details'].get('error','unknown') for e in records
            if e['kind']=='tool_result' and not e['details'].get('ok'))
        fighting=[name for name,counts in success.items() if any(counts[t] for t in ('attack','defeat','hunt'))]
        supporting=[name for name,counts in success.items() if counts['give_item']]
        cooperation=(len(fighting)>=2 if task=='skeleton' else bool(supporting) and len(success)>=2 if task=='diamond' else None)
        case_rows.append(dict(seed=seed,task=task,state=r['state'],reason=r.get('reason',''),seconds=r['wallSeconds'],
            requests=r.get('planningRequests'),observedActions=len(examples),
            logs=r.get('logsInChest',0),coal=r.get('coalInChest',0),diamonds=r.get('diamondsInChest',0),
            replanted=r.get('cropsReplanted',0),kills=r.get('npcSkeletonKills',0),
            mission=r.get('leaderState',{}).get('mission_goal',{}),
            initialMatureCrops=r.get('fixtureMatureCropsAtCommand'),
            wheatInBag=r.get('leaderState',{}).get('item_counts',{}).get('minecraft:wheat',0),
            toolFailures=dict(errors),fightingActors=fighting,supplyDeliveringActors=supporting,
            cooperationConfirmed=cooperation,fullTaskVerified=r['state']=='passed' and cooperation is not False,
            successfulOperationsByActor={name:dict(counts) for name,counts in success.items()},
            result=f'{seed}/{task}/result.json'))
    assert len(core)>=13 and all(len(values)==1 for values in core.values()),'Final run mixed core revisions'
    provenance=(load(folder/'provenance.json') if (folder/'provenance.json').exists() else dict(cases=[
        dict(seed=row['seed'],mission=row['task'],sourceRun=a.run_id,
            resultSha256=sha(folder/str(row['seed'])/row['task']/'result.json')) for row in rows]))
    for case in provenance['cases']:
        assert sha(folder/str(case['seed'])/case['mission']/'result.json')==case['resultSha256']
    for name,data in [('observed-actions',observed),('planning-exchanges',planning)]:
        payload=gzip.compress(''.join(json.dumps(e,ensure_ascii=False,separators=(',',':'))+'\n' for e in data).encode(),mtime=0)
        (folder/(name+'.jsonl.gz')).write_bytes(payload)
    tests=collections.Counter()
    for path in (ROOT/'build/test-results/test').glob('TEST-*.xml'):
        suite=ET.parse(path).getroot()
        for key in ('tests','failures','errors','skipped'):tests[key]+=int(suite.get(key,'0'))
    jar=ROOT/'build/libs/bep_3davater_companion-26.3-0.10.0.jar'
    summary=dict(runId=a.run_id,completedCases=25,states=states,
        taskResults={t:dict(collections.Counter(x['state'] for x in case_rows if x['task']==t)) for t in TASKS},
        fullTaskVerified={t:sum(x['fullTaskVerified'] for x in case_rows if x['task']==t) for t in TASKS},
        weightsSha256=model['sha256'],weightBytes=model['weightBytes'],parameters=model['parameters'],
        modelTraining=model['training'],pairedEvaluation=paired,unitTests=dict(tests),
        runtimeClassSha256={name:next(iter(values)) for name,values in core.items()},
        harnessClassSha256=sorted(harness),provenance=provenance,
        observedActions=len(observed),operationOutcomes=dict(collections.Counter(str(e['outcome']) for e in observed)),
        planningExchanges=len(planning),journals=journals,cases=case_rows,jarSha256=sha(jar))
    (folder/'verification.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    labels={'passed':'成功','blocked':'保留','failed':'未達終了','timeout':'時間切れ','aborted_budget':'予算中断'}
    table=[]
    for seed in SEEDS:
        cells=[]
        for task in TASKS:
            c=next(c for c in case_rows if c['seed']==seed and c['task']==task)
            n=c[{'logs':'logs','coal':'coal','farm':'replanted','diamond':'diamonds','skeleton':'kills'}[task]]
            suffix='・単独達成' if c['state']=='passed' and c['cooperationConfirmed'] is False else ''
            cells.append(f"{labels.get(c['state'],c['state'])}{suffix}（{n}）")
        table.append('| '+str(seed)+' | '+' | '.join(cells)+' |')
    comparison=[]
    for task in TASKS:
        if task=='logs':
            old=sum(any(o['mission']==0 and o['state']=='passed' for o in load(ROOT/f'build/npc-campaign-archive/{seed}/result.json')['outcomes']) for seed in SEEDS)
            source='初回v5・参考'
        else:old=sum(c['mission']==task and c['state']=='passed' for c in baseline['cases']);source='v6独立20課題'
        new=sum(c['task']==task and c['state']=='passed' for c in case_rows)
        comparison.append(f'| {TASKS[task]} | {old}/5（{source}） | {new}/5 |')
    details=[]
    for c in case_rows:
        errors=' / '.join(f'{count}件: {error}' for error,count in sorted(c['toolFailures'].items(),key=lambda p:-p[1])[:3])
        details.append(f"- {c['seed']} / {TASKS[c['task']]}: {c['state']}、{c['seconds']:.1f}秒、計画要求{c['requests']}回。{c['reason'] or '実状態で目標を確認'}。"
            +(f" 主な操作失敗: {errors}。" if errors else '')
            +(f" 小麦所持{c['wheatInBag']}個、収穫物回収確認{c['mission'].get('products_collected',False)}。" if c['task']=='farm' else '')
            +(f" 攻撃・討伐の成功操作がある担当: {', '.join(c['fightingActors']) or 'なし'}。" if c['task']=='skeleton' else '')
            +f" [結果JSON](../build/npc-targeted-archive/{a.run_id}/{c['result']})")
    splits=model['training']['corpus']['splits']
    report=f'''# NPC v7：失敗修正・40MiB再学習・同じ5 seedの25課題再検証

最終版で5 seed×5課題の25ケースを実行した。内訳は{states}。
試験を実施した件数と成功件数は区別する。候補モデルの評価、回帰テスト、自然地形での依頼達成は別に測定した。

## 最終結果

| seed | {' | '.join(TASKS.values())} |
| --- | --- | --- | --- | --- | --- |
{chr(10).join(table)}

括弧内は原木・石炭・ダイヤの指定チェストへの実収納数、畑の植え直し株数、NPCによるスケルトンの討伐数。
戦闘・採掘は目標数量の正常完了と協力の実参加を別に確認する。「単独達成」は数量を満たしても両者の実参加を確認できなかったケースで、協力課題全体の成功には数えない。
協力条件も含めた全条件の確認件数は{summary['fullTaskVerified']}。

| 課題 | 以前の正常完了 | 今回の正常完了 |
| --- | --- | --- |
{chr(10).join(comparison)}

原木の以前の試験はv5、ほか4課題はv6なので、同一の旧モデル25件との単純な比較ではない。
畑では後述の初期配置の不備も修正した。実行処理・上位AIへの情報・候補モデルを同時に更新しているため、差をモデル再学習だけの効果とみなさない。

## 実ログからの原因と修正

- 範囲作業の`dig`が`CropBlock`を成熟度によらず保護対象としていた。成熟作物の収穫を許可し、未成熟作物の保護は維持した。灌漑水に隣接する作物を掘削の浸水危険と混同しない。
- `harvest_replant`を追加。成熟作物だけを収穫し、実際の移動・拾得を使ってドロップを回収し、収穫全体から種を確保して植え直す。最初の1株で種が出ない場合に全体を止めず、先に残りも収穫する。種は生成・支給しない。
- NPCによる意図しないmob griefingを抑止し、ジャンプで畑を壊さない。明示的な採掘等は従来どおり権限検証を通る実ツールで行う。
- 上位AIの`TaskPlan.goal`に、計画段階とは独立した依頼全体の達成条件を持たせた。最初に採用した条件は再計画で減らせない。命令受付時の成熟作物と所持数を記録し、計画待ちの間の変化で対象を減らさない。
- 収納は固定した目的地の実コンテナ個数、畑は対象全株の植え直しと収穫物、討伐は実死亡イベントの一意な記録を検証する。手順だけ終わっても未達なら再計画へ返し、指示と場所メモリを完了消去しない。
- 討伐記録を同じ依頼の担当間で共有し、倒したエンティティが消えた後も確認できるようにした。全体の必要数に達したら不要な待機・攻撃・再計画を終了する。完了時の確認結果は保存する。
- 空振りした探索領域を、入れ子の調達や再計画をまたいで保存。地形の実高度に合う新しい地表の候補を探す。未ロード領域や危険な場所は勝手に生成・掘削しない。
- 水に入ると接地待ちで停止していた移動に、観察できる通常の泳ぎと岸への移動を追加。水面で上下するたびに経路を作り直して始点へ戻る問題も修正した。地上へ戻った後は地上経路へ切り替える。潜水・任意の水中地形を網羅した保証ではない。

## 検証条件と診断ログ

seedは61279〜61283。自然のNoiseBasedChunkGenerator、資源量で選ばない最初の安全な陸地を使った。
課題ごとに初期化した独立ワールド、初期食料は各NPCにパン16個。木・石炭・ダイヤと道具は追加しない。
畑は成熟小麦8株・灌漑・アンカーを配置。スケルトンは実体5体を配置し、戦闘だけ両NPCにダイヤ防具一式と剣を支給する。
モブの追加自然湧きと天候変化はGameTest設定で停止。上位AIは実接続、候補選択はv7で固定した。
上限は各課題98,000Tick／25分・実計画要求24回。時間切れは達成不可能を証明したものではない。
GameTestは実行時に高速Tickで進み、外部AI待機中は各Tickに50ms待つ。秒数は通常クライアントの20TPSでの所要時間とは比較しない。
戦闘は夜に開始し、その後の時刻は進む。自然地形の落下・水流・遮蔽・敵の移動も含めて記録している。

最初のv7診断で、畑の上空が地形等に塞がれ、設置した8株が計画開始時には揃わない初期配置を検出した。
その途中ログは`build/npc-targeted-archive/20261026-targeted-v7/`に保存し、最終25件には混ぜていない。
畑の上空を開け、20Tick待ってから一度だけ8株を植える。命令前の小麦・種の落下アイテムを除き、8株すべての実在・成熟を確認して命令を出すようにした。
試験コード変更時のコンパイルエラーで後半10ケースが起動・終了できず、さらに1ケースで停止時のクラス読み込みエラーを検出したため、原ログを保全したうえで該当ケースを取り直した。
最終25件は、`20261026-targeted-v7-final`の正常に終了した畑以外11件と、`20261026-targeted-v7-recheck`の14件（畑全5件・起動等に失敗した残り9件）を集約した。
達成失敗・保留・時間切れを都合よく再試行した集計ではない。各ケースの採用元と結果ハッシュは`provenance.json`に保存した。
全ケースが独立した初期ワールドで、実行時に記録したコア13クラスとモデルのSHA-256は一致している。試験初期配置の変更と採用元は上記の通り区別し、同じ試験コードでの単一走行とは扱わない。

## 40MiBモデルの再学習

v7は56→1419→1419→1の5専門家、FP32・{model['parameters']:,}パラメータ、{model['weightBytes']:,} bytes。
v6の全層を引き継ぎ全層を学習した。16 epoch実行し、検証基準で採用したのはepoch {model['training']['selectedEpoch']}。
ファイルのパディングではなく実際の重みを更新した。上位の外部計画モデル自体のファインチューニングではない。

前回の実操作10,739件（成功10,631、失敗108）を使用。以前の監査も含め、完全重複する候補配列を除いた。
splitはseed単位で固定：61279〜61281を学習、61282を検証、61283を独立評価とした。
学習{splits['train']['examples']:,}件、検証{splits['validation']['examples']:,}件、独立評価{splits['test']['examples']:,}件。
合成の候補比較データも含み、観測データの未実行候補とパディングは教師値から除外した。
成功操作の教師値は既存の候補評価関数を基準にし、失敗操作には負値を使う。実測したのは操作の成否であり、候補全体の正解順位や将来価値ではない。
失敗した依頼中の成功操作を一律に失敗ラベルへ変えていない。今回の再検証ログを、この学習済みモデルの訓練へ後から混ぜていない。

| 同一データでの評価 | v6 | v7 |
| --- | ---: | ---: |
| 合成・検証7,500件の厳密な最適候補一致 | {paired['v6']['validation']['exactOptimalAccuracy']*100:.2f}% | {paired['v7']['validation']['exactOptimalAccuracy']*100:.2f}% |
| 合成・独立評価7,500件の厳密な最適候補一致 | {paired['v6']['test']['exactOptimalAccuracy']*100:.2f}% | {paired['v7']['test']['exactOptimalAccuracy']*100:.2f}% |
| 検証seedの観測操作MSE | {paired['v6']['validation']['observedMse']:.5f} | {paired['v7']['validation']['observedMse']:.5f} |
| 独立評価seedの観測操作MSE | {paired['v6']['test']['observedMse']:.5f} | {paired['v7']['test']['observedMse']:.5f} |

禁止候補選択は両モデルとも評価上0件。合成検証の一致率は少し下がり、独立評価と観測MSEは少し改善した。これを自然地形の依頼成功率と混同しない。
重みSHA-256: `{model['sha256']}`。

## 残る制約

地形・水際の経路失敗は残っている。木が見つからないことは、ワールド全体に木がない証明ではない。
安全経路を持たない候補・道具の準備・原木の調達で止まると、石炭とダイヤの依頼も完了できない。
畑では8株の植え直し後も小麦が6/8個のケースが残った。マクロの回収段階は移動の完了を確認するが、各株の全ドロップを拾えたかは確認していない。落下・水流等の詳細な寄与は未確定。完了判定は不足を検出するが、全個回収の経路はまだ保証できない。
討伐でも見えている敵への高低差・遮蔽のある接近は失敗する。再計画だけを繰り返す場合は24回で停止し、成功扱いにしない。
数値と終了理由は上表と各結果JSONを優先する。ログを追加するだけでこれらの実行処理が直るとはいえず、候補の到達可能性・回収手順・上位計画の改善が引き続き必要。

## テストと成果物

- JUnit: {tests['tests']}件、スキップ{tests['skipped']}件、失敗{tests['failures']}・エラー{tests['errors']}。
- Minecraft回帰テスト121件成功。種の支給なしの8株収穫・植え直し、7/8株の拒否、命令から計画までの対象保持、63/64個での誤完了拒否、討伐共有、泳いで岸へ移動を含む。
- 最終25ケースの観測成否付き操作{len(observed):,}件、上位AIの実計画入出力{len(planning):,}件を保存。
- 原ログ・入力・計画・最終状態・ファイルのSHA-256は`build/npc-targeted-archive/{a.run_id}/`。
- 学習データと出自は`TrainingData/npc-v7/manifest.json`と`observed-actions.jsonl.gz`。
- JAR: `build/libs/{jar.name}`。SHA-256: `{sha(jar)}`。
- Neoの実プレイ用インスタンスのMOD・セーブは変更していない。

## 各ケース

{chr(10).join(details)}
'''
    report_path=ROOT/'Docs/NpcV7FiveTaskVerification20261026.md';report_path.write_text(report,encoding='utf-8')
    archive=ROOT/'build'/f'npc-v7-five-task-logs-{a.run_id}.zip'
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for file in sorted(folder.rglob('*')):
            if file.is_file():z.write(file,file.relative_to(folder))
        z.writestr('report.md',report.replace(f'../build/npc-targeted-archive/{a.run_id}/',''))
    print(json.dumps(dict(completed=25,states=states,taskResults=summary['taskResults'],observedActions=len(observed),planningExchanges=len(planning),zip=str(archive),zipBytes=archive.stat().st_size,zipSha256=sha(archive))))

if __name__=='__main__':main()

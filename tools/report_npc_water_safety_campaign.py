"""Audit the 25 supplied-tool trials after shallow swimming and local emergency recovery."""
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
RUN='20261026-water-safety-tools-reviewed-v7'
PRIMARY='20261026-water-safety-tools-v7'
RECHECK='20261026-water-safety-supplied-command-v7'
BASE='20261026-spatial32-entities16-chunks7-v7'
SEEDS=list(range(61279,61284))
TASKS={'logs':'原木64収納','coal':'石炭64収納','farm':'収穫・植え直し','diamond':'協力ダイヤ10収納','skeleton':'協力スケルトン5討伐'}
prior.MISSIONS['logs']=0
TOOLS=('minecraft:diamond_pickaxe','minecraft:diamond_shovel','minecraft:diamond_axe')
def load(p):return json.loads(p.read_text(encoding='utf-8'))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--partial',action='store_true');p.add_argument('--run-id');a=p.parse_args()
    selected=a.run_id or (RECHECK if a.partial else RUN)
    folder=ROOT/'build/npc-targeted-archive'/selected
    rows=[];live=[];observed=[];planning=[];files=[];revisions=collections.defaultdict(set)
    for seed in SEEDS:
        for task in TASKS:
            case=folder/str(seed)/task
            if not (case/'archive.json').exists():
                current=ROOT/f'build/npc-targeted-results/{seed}-{task}.json'
                try:
                    if (case/'server.log').exists() and current.stat().st_mtime>=(case/'server.log').stat().st_ctime:
                        r=load(current);o=r.get('leaderState',{})
                        live.append(dict(seed=seed,task=task,state=r['state'],seconds=round(r['wallSeconds']),plans=r.get('planningRequests'),
                            status=r.get('status'),stored=r.get({'logs':'logsInChest','coal':'coalInChest','farm':'cropsReplanted','diamond':'diamondsInChest','skeleton':'npcSkeletonKills'}[task]),
                            water=o.get('in_water'),air=o.get('air'),safety=o.get('water_safety')))
                except (OSError,json.JSONDecodeError):pass
                continue
            meta=load(case/'archive.json');r=load(case/'result.json')
            if meta.get('diagnosticOnly'):continue
            assert meta['serverExitCode']==0 and r['state'] not in ('running','setup_failed','infrastructure_failed')
            assert sha(case/'result.json')==meta['resultSha256'] and sha(case/'planning-exchanges.jsonl')==meta['planningSha256']
            assert r['safeWaterDepth']==5 and r['realTimePhysics'] and r['maxMissionWallSeconds']==360 and r['maxActualPlanningRequests']==8
            supplied=task in ('logs','coal','diamond');assert r['initialTools']==(9 if supplied else 0)
            if task in ('coal','diamond'):
                command=r['outcomes'][0]['instruction']
                assert '支給された道具' in command and '道具を作って' not in command,'Old crafting instruction is not the supplied-tool test'
            for key in ['initialObservation']+(['initialWorkerObservation'] if task in ('diamond','skeleton') else []):
                counts=r[key]['item_counts'];assert all(counts.get(t,0)==(3 if supplied else 0) for t in TOOLS),f'{seed}/{task}/{key}: incorrect initial tools'
            if task=='farm':assert r['fixtureMatureCropsAtCommand']==8 and r['fixtureCropDropsAtCommand']==0
            if a.partial:
                rows.append(dict(seed=seed,task=task,state=r['state']));continue
            old=load(ROOT/'build/npc-targeted-archive'/BASE/str(seed)/task/'result.json')
            assert r['origin']==old['origin'] and r['chest']==old['chest'],'Initial terrain selection changed'
            records=[];examples=[]
            for path in sorted((case/'journal').rglob('*.jsonl')):
                seal=next(e for e in meta['journals'] if Path(e['path'])==path.relative_to(folder));assert sha(path)==seal['sha256']
                data=[json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line.strip()];records.extend(data)
                examples.extend(prior.observations(data,str(path.relative_to(folder)),sha(path),seed,task,r['state']))
                files.append(dict(path=str(path.relative_to(folder)),bytes=path.stat().st_size,sha256=sha(path)))
            assert sum(e['kind']=='campaign_mission' for e in records)==1
            ops=collections.defaultdict(collections.Counter);errors=collections.Counter();vitals=[]
            for e in records:
                if e['kind']=='tool_result':
                    if e['details'].get('ok'):ops[e['actorName']][e['text']]+=1
                    else:errors[e['details'].get('error','unknown')]+=1
                if e['kind']=='campaign_vitals':vitals.append(dict(actor=e['actorName'],**e['details']))
            fighters=[actor for actor,c in ops.items() if any(c[t] for t in ('attack','defeat','hunt'))]
            givers=[actor for actor,c in ops.items() if c['give_item']]
            cooperation=len(fighters)>=2 if task=='skeleton' else bool(givers) and len(ops)>=2 if task=='diamond' else None
            mission=r['leaderState']['mission_goal']
            if r['state']=='passed':assert mission['verified'] is True
            row=dict(seed=seed,task=task,state=r['state'],reason=r['reason'],seconds=(r.get('outcomes') or [{}])[0].get('wallSeconds',r['wallSeconds']),
                caseWallSeconds=r['wallSeconds'],logs=r.get('logsInChest',0),coal=r.get('coalInChest',0),diamonds=r.get('diamondsInChest',0),
                replanted=r.get('cropsReplanted',0),wheat=r['leaderState'].get('item_counts',{}).get('minecraft:wheat',0),kills=r.get('npcSkeletonKills',0),
                mission=mission,cooperationConfirmed=cooperation,fullTaskVerified=r['state']=='passed' and cooperation is not False,
                fightingActors=fighters,supplyDeliveringActors=givers,toolFailures=dict(errors),successfulOperationsByActor={n:dict(c) for n,c in ops.items()},
                observedActions=len(examples),planningRequestsAttempted=r['planningRequests'],planningResponses=len(r['planningExchanges']),
                unfinishedAtStop=max(0,min(r['planningRequests'],8)-len(r['planningExchanges'])),
                initialTools={key:r[key]['item_counts'] for key in ['initialObservation']+(['initialWorkerObservation'] if task in ('diamond','skeleton') else [])},
                waterSamples=sum(v['water'] for v in vitals),flowSprintSamples=sum(v['water'] and v['current'] and v['sprinting'] for v in vitals),
                minimumAir=min((v['air'] for v in vitals),default=r['leaderState'].get('air',300)),
                emergencyStarts=sum(e['kind']=='water_emergency_start' for e in records),emergencyEnds=sum(e['kind']=='water_emergency_end' for e in records),
                deaths=[dict(actor=e['actorName'],**e['details']) for e in records if e['kind']=='death'],result=str((case/'result.json').relative_to(folder)))
            rows.append(row);observed.extend(examples)
            for name,digest in r['runtimeClassSha256'].items():revisions[name].add(digest)
            for e in r['planningExchanges']:planning.append(dict(seed=seed,task=task,state=r['state'],**e))
    states=dict(collections.Counter(r['state'] for r in rows))
    if a.partial:print(json.dumps(dict(completed=len(rows),planned=10 if selected==RECHECK else 25,retainedCases=15 if selected==RECHECK else 0,states=states,live=live)));return
    assert len(rows)==25 and len(revisions)==24
    model=load(ROOT/'src/npc/resources/data/bep_3davater_companion/ai/action-policy.json')
    baseline=load(ROOT/'build/npc-targeted-archive'/BASE/'verification.json');assert model['sha256']==baseline['weightsSha256']
    jar=ROOT/'build/libs/bep_3davater_companion-26.3-0.10.0.jar'
    harness={name:sorted(values) for name,values in revisions.items() if name in ('NpcCampaignGameTests','Run')}
    production={name:values for name,values in revisions.items() if name not in harness}
    assert len(production)==22 and all(len(v)==1 for v in production.values()),'Mixed production revisions'
    provenance=load(folder/'provenance.json')
    for source in provenance['cases']:assert sha(folder/str(source['seed'])/source['task']/'result.json')==source['resultSha256']
    with zipfile.ZipFile(jar) as z:
        for name,values in production.items():
            path='dev/avatarcraft/npc/'+(name+'.class' if name in ('CompanionEntity','NpcChunkLoading') else 'ai/'+('NpcCampaignGameTests$Run.class' if name=='Run' else name+'.class'))
            assert hashlib.sha256(z.read(path)).hexdigest()==next(iter(values))
        assert hashlib.sha256(z.read('data/bep_3davater_companion/ai/action-policy.bin')).hexdigest()==model['sha256']
    tests=collections.Counter()
    for path in (ROOT/'build/test-results/test').glob('TEST-*.xml'):
        suite=ET.parse(path).getroot();tests.update({k:int(suite.get(k,0)) for k in ('tests','failures','errors','skipped')})
    assert not tests['failures'] and not tests['errors']
    regression=ROOT/'build/npc-water-navigation-regression-v2.log';assert 'All 130 required tests passed' in regression.read_text(encoding='utf-8')
    for name,data in [('observed-actions',observed),('planning-exchanges',planning)]:
        (folder/(name+'.jsonl.gz')).write_bytes(gzip.compress(''.join(json.dumps(e,ensure_ascii=False,separators=(',',':'))+'\n' for e in data).encode(),mtime=0))
    summary=dict(runId=RUN,completedCases=25,states=states,cases=rows,journals=files,
        fullTaskVerified={task:sum(r['fullTaskVerified'] for r in rows if r['task']==task) for task in TASKS},
        weightsSha256=model['sha256'],weightBytes=model['weightBytes'],modelRetrained=False,
        runtimeClassSha256={n:next(iter(v)) for n,v in production.items()},harnessClassSha256=harness,provenance=provenance,unitTests=dict(tests),gameTests=130,
        observedActions=len(observed),observedOutcomes=dict(collections.Counter(str(e['outcome']) for e in observed)),planningResponses=len(planning),
        emergencyStarts=sum(r['emergencyStarts'] for r in rows),emergencyEnds=sum(r['emergencyEnds'] for r in rows),
        waterSamples=sum(r['waterSamples'] for r in rows),flowSprintSamples=sum(r['flowSprintSamples'] for r in rows),jarSha256=sha(jar),
        baselineRun=BASE,baselineFullTaskVerified=baseline['fullTaskVerified'])
    (folder/'verification.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    labels={'passed':'正常終了','blocked':'保留','failed':'未達終了','timeout':'時間切れ'};table=[];comparison=[];details=[]
    for seed in SEEDS:
        cells=[]
        for task in TASKS:
            r=next(r for r in rows if r['seed']==seed and r['task']==task);n=r[{'logs':'logs','coal':'coal','farm':'replanted','diamond':'diamonds','skeleton':'kills'}[task]]
            cells.append(f"{labels.get(r['state'],r['state'])}{'・単独' if r['state']=='passed' and r['cooperationConfirmed'] is False else ''}（{n}）")
        table.append('| '+str(seed)+' | '+' | '.join(cells)+' |')
    for task in TASKS:comparison.append(f"| {TASKS[task]} | {baseline['fullTaskVerified'][task]}/5 | {summary['fullTaskVerified'][task]}/5 |")
    for r in rows:
        errors=' / '.join(f'{count}件: {error}' for error,count in sorted(r['toolFailures'].items(),key=lambda p:-p[1])[:3]) or 'なし'
        details.append(f"- {r['seed']} / {TASKS[r['task']]}: {r['state']}、{r['seconds']:.1f}秒、実計画の完了{r['planningResponses']}件・要求試行{r['planningRequestsAttempted']}回。{r['reason'] or '実状態で目標を確認'}。"
            +f" 水中サンプル{r['waterSamples']}、逆流ダッシュ条件のサンプル{r['flowSprintSamples']}、退避開始/終了{r['emergencyStarts']}/{r['emergencyEnds']}、最低酸素{r['minimumAir']}。主な操作失敗: {errors}。"
            +(f" 小麦所持{r['wheat']}。" if r['task']=='farm' else '')+(f" 死亡: {r['deaths']}。" if r['deaths'] else '')
            +f" [結果JSON](../build/npc-targeted-archive/{RUN}/{r['result'].replace(chr(92),'/')})")
    report=f'''# 浅い水域の経路・ローカル退避・逆流ダッシュと道具支給による25課題検証

同じ5 seed（61279〜61283）で5課題を独立実行した。内訳は{states}。協力を含めた全条件の確認は{sum(summary['fullTaskVerified'].values())}/25件。

| seed | {' | '.join(TASKS.values())} |
| --- | --- | --- | --- | --- | --- |
{chr(10).join(table)}

括弧内は指定チェストへの原木・石炭・ダイヤの実収納数、植え直した株数、NPCに帰属する討伐数。畑は小麦8個の回収も必要。「単独」は数量を満たしても両NPCの実参加を確認できなかったケースで、協力課題全体の成功には含めない。

| 課題 | 前回・協力を含む全条件 | 今回・協力を含む全条件 |
| --- | --- | --- |
{chr(10).join(comparison)}

## 実装

- 既知の水深1〜5ブロックは水面遊泳の経路候補に含め、陸→水→陸を一つの経路として検索する。底・水面・頭上空間・身体の衝突を確認し、深さ6以上・不明な列は通常経路から除く。水面が歩行用の硬い足場になったわけではない。採掘・設置の保護判定は維持する。
- 地形観測の経路候補も同じ水深規則を使い、歩行/遊泳を区別する。回収候補と作業経路の到着判定にも実際の水面の高さを反映した。
- 深い／深さ不明の水で頭が沈んだ場合、実深度5超、または酸素120以下を検出すると、通常の命令処理より先にローカル退避する。モデル呼び出しを待たず、読取り可能な空気のある場所を身体の衝突を考慮して探索する。天井があれば迂回し、実際の浮上・移動で呼吸できたら元の指示へ戻る。指示や場所メモリを完了消去しない。
- 緊急退避中は通常の移動・作業ツールを優先させない。既存タスクの期限切れ等は退避後の通常の失敗・再計画処理に戻す。空気の補充・無敵化・瞬間移動・ブロック生成はしていない。観測範囲内に到達可能な空気がない閉鎖空間等では救命を保証できない。
- 身体が触れている水の流れを調べ、移動時はダッシュを有効化する。通常の移動制御に水平推進を加え、衝突を保ったまま上限速度内で流れに逆らう。あらゆる水流・落下流を突破できる保証ではない。
- 収穫の移動終了直後に次の株へ進んでいた処理は、通常の10Tickごとの拾得処理を待てるよう12Tick待機する。遠隔拾得や収穫物の生成はしない。

## 試験条件と支給品

原木・石炭・ダイヤの各ケースでは、各NPCにダイヤのつるはし・シャベル・斧を各3本、計9本支給した。協力ダイヤは両者とも同じ9本。初期インベントリを個別に検査した。木・鉱石・燃料・製作設備・架橋材は追加支給しない。道具があっても、周囲に存在しない原木等を生成して成功にはしない。
畑は前回と同じ成熟小麦8株・灌漑・アンカー、種や追加道具はなし。討伐は実体スケルトン5体、両NPCにダイヤ防具一式・剣、NORMAL・夜間開始。食料は各NPCにパン16個。自然湧き・天候変化はGameTestで停止。地形と指定チェストの初期座標は前回と一致した。
今回は各タスクのTickに50ms待機を入れ、最大20TPSの通常に近い物理で実行した。1ケース最大6分・上位計画の実要求8回。未完了の応答待ちは終了時に打ち切り、完了済み計画だけを計画入出力として保存する。時間切れは達成不可能の証明ではない。
前回は持ち込み道具なし・高速Tick・最大25分/24計画だった。水の修正・道具支給・進行速度・上限を同時に変えており、表の差を水の修正だけの因果効果とはみなさない。各条件1試行、未達を成功するまで取り直していない。

## 検証とデータ

ゲーム内回帰130件成功。今回追加した6項目は、水深5の陸水陸横断、水深6の経路除外、深い水からの退避、天井迂回、低酸素退避、水流に逆らうダッシュである。退避中にモデルセッションを呼ばないこと、空気や位置を人工的に書き換えず実際に呼吸位置へ動くことを確認した。
JUnit {tests['tests']}件・失敗{tests['failures']}・エラー{tests['errors']}・スキップ{tests['skipped']}。今回の25ケースで、水中の計測{summary['waterSamples']}サンプル、流れの中でダッシュしていた計測{summary['flowSprintSamples']}サンプル、緊急退避の開始{summary['emergencyStarts']}件・終了{summary['emergencyEnds']}件を記録した。これらはタスク成功とは別の動作確認指標である。
モデルは前回と同じv7・{model['weightBytes']:,} bytes、SHA-256 `{model['sha256']}`。今回は再学習していない。選択され実行された操作{len(observed):,}件にだけ成否を付け、未実行候補に報酬は付けていない。完了した実計画入出力{len(planning):,}件。
初回は石炭・ダイヤの旧文言「道具を作って」が残り、支給ダイヤ道具があるのに石のつるはしを新規製作する計画を検出した。今回の条件に合わせ、この10件は全て「支給された道具を使って採掘・収納」の文言へ揃えて初期化し直した。初回ログは`{PRIMARY}`に保存し、診断中断・起動抑止を課題の失敗として混ぜていない。
採用した25件は初回の原木・畑・討伐15件と、`{RECHECK}`の修正文言による石炭・ダイヤ10件。成功したケースだけを選び直した集計ではない。出自と結果ハッシュは`provenance.json`に保存した。
全25ケースで製品コード22クラスのハッシュが一致し、配布JARとも一致する。試験器の2クラスは各ケース・採用元ごとのハッシュを別に保存した。試験中に製品コードやモデルは更新していない。JARのSHA-256は`{sha(jar)}`。原ログ・初期持ち物・バイタル・最終状態・計画は`build/npc-targeted-archive/{RUN}/`。Neoの実プレイ用MOD・セーブは変更していない。

## 各ケース

{chr(10).join(details)}
'''
    (ROOT/'Docs/NpcWaterSafetyToolsVerification20261026.md').write_text(report,encoding='utf-8')
    archive=ROOT/'build'/f'npc-water-safety-logs-{RUN}.zip'
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for path in sorted(folder.rglob('*')):
            if path.is_file():z.write(path,path.relative_to(folder))
        z.write(regression,'evidence/regression.log');z.write(ROOT/'build/npc-water-safety-release-build.log','evidence/build.log')
        z.writestr('report.md',report.replace(f'../build/npc-targeted-archive/{RUN}/',''))
    with zipfile.ZipFile(archive) as z:assert z.testzip() is None
    print(json.dumps(dict(completed=25,states=states,fullTaskVerified=summary['fullTaskVerified'],waterSamples=summary['waterSamples'],flowSprintSamples=summary['flowSprintSamples'],
        emergencyStarts=summary['emergencyStarts'],emergencyEnds=summary['emergencyEnds'],zip=str(archive),zipBytes=archive.stat().st_size,zipSha256=sha(archive))))

if __name__=='__main__':main()

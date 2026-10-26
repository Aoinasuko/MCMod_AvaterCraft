"""Generate an evidence-based report; stopped or unattempted missions never count as passes."""
import collections
import gzip
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parent.parent

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    archive=ROOT/'build/npc-campaign-archive'
    runs=[json.loads((archive/str(seed)/'result.json').read_text(encoding='utf-8')) for seed in range(61279,61284)]
    model=json.loads((ROOT/'src/npc/resources/data/bep_3davater_companion/ai/action-policy.json').read_text(encoding='utf-8'))
    manifest=json.loads((ROOT/'TrainingData/npc-v6/manifest.json').read_text(encoding='utf-8'))
    assert model['version']==6 and model['training']['passed'] and model['weightBytes']==41945660
    weights=ROOT/'src/npc/resources/data/bep_3davater_companion/ai/action-policy.bin'
    assert sha(weights)==model['sha256'] and weights.stat().st_size==model['weightBytes']
    with gzip.open(ROOT/'TrainingData/npc-v6/observed-actions.jsonl.gz','rt',encoding='utf-8') as f:
        observed=[json.loads(line) for line in f]
    actions=collections.Counter(e['worldSeed'] for e in observed)
    selectors=collections.Counter()
    raw_by_sha={sha(p):[json.loads(line) for line in p.read_text(encoding='utf-8').splitlines()] for p in archive.rglob('*.jsonl')}
    for e in observed:selectors[raw_by_sha[e['sourceSha256']][e['policyLine']-1]['details'].get('selector','unknown')]+=1
    counts=collections.Counter(o['state'] for r in runs for o in r['outcomes'])
    counts['not_attempted']=sum(r['remainingMissionsNotAttempted'] for r in runs)
    unit=collections.Counter()
    for path in (ROOT/'build/test-results/test').glob('TEST-*.xml'):
        suite=ET.parse(path).getroot()
        for key in ('tests','failures','errors','skipped'):unit[key]+=int(suite.get(key,'0'))
    gamelog=(ROOT/'build/npc-v6-final-gametest.log').read_text(encoding='utf-8',errors='replace')
    assert 'All 116 required tests passed' in gamelog
    assert unit['failures']==unit['errors']==0
    jar=ROOT/'build/libs/bep_3davater_companion-26.3-0.10.0.jar'
    benchmark=json.loads((ROOT/'build/npc-policy-v6-benchmark.json').read_text(encoding='utf-8'))
    result=dict(missionCounts=dict(counts),campaignPolicyVersion=5,shippedPolicyVersion=6,
        observedActions=len(observed),observedSelectors=dict(selectors),unitTests=dict(unit),gameTests=116,
        model=dict(bytes=model['weightBytes'],parameters=model['parameters'],sha256=model['sha256'],
            dimensions=model['dimensions'],training=model['training']),
        jar=dict(path=str(jar),sha256=sha(jar)),benchmark=benchmark,worlds=runs)
    (ROOT/'build/npc-campaign-verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    rows=[]
    names=['原木64収納','石炭64収納','収穫・植え直し','協力ダイヤ10収納','協力スケルトン5討伐']
    for r in runs:
        states={o['mission']:o['state'] for o in r['outcomes']}
        statuses=['成功' if states.get(i)=='passed' else '中断' if i in states else '未実施' for i in range(5)]
        rows.append('| '+str(r['seed'])+' | '+' | '.join(statuses)+' | '+str(actions[r['seed']])+' |')
    val=model['training']['validation'];test=model['training']['independentTest']
    baseline=model['training']['baseline']
    report=f'''# NPCの場所参照・場所メモリと5 seed採取・40MiB学習（2026-10-26）

場所メモリのライフサイクルと指示受付時の位置参照を実装し、実ログによるv6モデルの学習・組み込みを完了した。
5 seedの全25命令の成功確認は達成していない。6命令を試行し1成功・5中断、残り19命令はワールド中断条件に従って未実施。
畑・協力ダイヤ採掘・スケルトン討伐には今回の5 seed試験では到達しなかった。配置や実行をしたものとして数えない。

## 場所メモリと自然言語の参照

- 検証済みの命令完了時に8枠の個人場所メモリと依頼の参照情報をすべて消去する。段階計画の切り替えでは消さない。
- `forget_location(name)`でAIが不要と判断した任意の名前を削除できる。未登録名の再削除も成功し、`removed`で実際の削除有無を返す。
- `remember_location(name,x,y,z)`と`remember_reference(name,reference)`を提供する。8枠満杯時に無関係な場所を自動で追い出さず、先に不要な名前を削除する。
- 依頼を受けた瞬間のサーバー側の観察を凍結し、非同期の解釈・計画要求に渡す。プレイヤーの後の移動で「ここ」を更新しない。再起動を挟んでも依頼参照を保存する。
- 実行器に日本語の固定文を列挙する方式を使わず、上位AIが共通の参照情報から意味を選び、ツールが確定した座標を保存する。

| 表現の例 | 共通の参照情報 |
| --- | --- |
| ここのチェスト／この箱／this chest | 指したブロックとその種類、指示時の利用可能なチェスト。視線を優先し、近くに一つだけある場合も候補にする |
| 私が立っているチャンク | `command_context.speaker.chunk`。負座標を含め16×16の包含境界を計算 |
| 今君がいるところ | `command_context.addressee.blockPosition` |
| 私の後ろ・君の右 | 各主体の指示時の位置、向き、水平forward/rightベクトル |
| 見ているブロック | `command_context.pointedBlock`。視線の観察距離は8ブロック |

`reference`は`speaker_position`、`npc_position`、`pointed_block`、`delivery_chest`に対応する。
チャンクは範囲、個人場所メモリは点として扱う。上下の作業範囲が曖昧な指示から全高の破壊を推測せず、追加観察や確認を行うよう指示する。
複数のチェストが残って指示対象を確定できない場合は任意の最寄りを収納先にしない。共有アンカーは個人8枠から独立しており完了後も残る。
「新しくチェストを置いて収納する」計画では、設置した実チェストを使う従来の処理を維持する。
共通参照を渡す仕組みを検証したものであり、あらゆる自然言語表現の正解を保証する評価ではない。

## 5 seedでの実測

| seed | {' | '.join(names)} | 観測成否付き操作 |
| --- | --- | --- | --- | --- | --- | --- |
{chr(10).join(rows)}

- seed 61279・61281: 探索範囲で原木を確保できず保留。ワールド全体に木が無いことを証明したわけではない。
- seed 61280: 原木56個の段階で、残った木への安全な接近と範囲作業が失敗。
- seed 61282: 原木9個などを所持した段階で、経路失敗のクールダウンから復旧できず保留。
- seed 61283: 原木64個を指定チェストへ実収納し、完了直後の個人メモリが空であることを確認。その後の石炭64個は経路・道具準備で保留。

資源の探索不足と経路実装の失敗を分けて記録した。保留を検出したワールドは残りの命令へ進めていない。
コードには試験用畑8株・アンカー・種8個、戦闘用の実スケルトン5体と両NPCへのダイヤ装備支給を実装したが、該当段階は未実施でありその自然ワールドでの動作は未検証。

5 seedは61279〜61283に事前固定し、通常のNoiseBasedChunkGeneratorを使用。開始点は原点周辺256以内の最初の安全な陸地で、木・石炭・ダイヤの量で選ばない。
空の収納チェストを配置し、NPCに初期食料としてパン16個を支給。最初の3命令はソロ、後続2命令から2人。道具・自然資源の追加配置はしない。
各命令には98,000Tick／25分の上限を設け、時間切れと資源不在を混同しない。今回は時間切れではなく保留による中断。
raw結果中の旧フィールド`resourceSearchRadius=48`は制限に使われていないメタデータであり、実際の1回のブロック検索は半径16、移動探索は別予算。ソースの表記は修正済み。

採取は修正後のv5で行い、これらのログを使ってv6を学習した。5 seedの結果をv6での完走結果として扱わない。
rawログ・結果・サーバーログは`build/npc-campaign-archive/<seed>/`に退避し、各ログのSHA-256を記録。

## 40MiBモデルとデータ

v6は56→1419→1419→1を5専門家に分けたFP32ネットワーク。{model['parameters']:,}パラメータ、{model['weightBytes']:,} bytes（約40.002MiB）。
隠れ層を拡張し、元モデルの関数を近似維持する重みの引き継ぎと対称性の微小な崩しを行って全パラメータを16 epoch学習した。パディングでファイルサイズを増やしていない。
採用epochは{model['training']['selectedEpoch']}。上位のCodex計画モデル自体を再学習したものではなく、NPC内蔵の候補選択モデルを更新した。

新しい実ログから抽出した観測操作は{len(observed):,}件（成功2732・失敗43）。未実行候補と観測データの埋め草には成否ラベルを付けない。
選択方式の内訳は{dict(selectors)}。単一の実行可能候補しかない場面も含むため、件数すべてが難しい候補比較の例とは限らない。
原木・石炭に偏り、今回の新規実ログには畑・ダイヤ・戦闘の実行例がない。モデルを大きくしたことだけでこれらの成功率が上がったとは主張できない。

| split | 件数 | 内訳 |
| --- | ---: | --- |
| 学習 | 62,051 | 合成60,000＋実操作2,051（今回のseedと以前のNeo監査） |
| 検証 | 7,705 | 合成7,500＋seed 61282の実操作205 |
| 独立評価 | 8,219 | 合成7,500＋seed 61283の実操作719 |

seed 61279〜61281を学習、61282を検証、61283を独立評価に固定。候補配列が完全一致する例をsplit間から除外し、同じワールドの途中の行をランダムに分割しない。
従来のNeo監査は学習専用。合成データには既知の候補スコア、実操作には選ばれた一つの観測成否だけを使う。

| 評価 | 検証 | 独立評価 |
| --- | ---: | ---: |
| 厳密な最適候補一致（合成7,500件） | {val['exactOptimalAccuracy']*100:.2f}% | {test['exactOptimalAccuracy']*100:.2f}% |
| スコア差0.04を許容した一致（同上） | {val['optimalAccuracy']*100:.2f}% | {test['optimalAccuracy']*100:.2f}% |
| 禁止候補選択率（同上） | 0% | 0% |
| 観測成否ラベルへのMSE | {val['observedMse']:.4f} | {test['observedMse']:.4f} |

検証ワールドの観測MSEは初期{baseline['observedMse']:.4f}から{val['observedMse']:.4f}へ低下した。
これらは候補選択／操作結果の評価で、自然ワールドのミッション成功率ではない。未実行候補の反実仮想の成否は評価していない。

重みSHA-256: `{model['sha256']}`。

## 最終検証・成果物

- JUnit: {unit['tests']}件中{unit['tests']-unit['skipped']}実行、{unit['skipped']}スキップ、失敗0・エラー0。
- Minecraft GameTest: 116件成功。新規の視線優先・別チェスト・非同期中の移動・負座標チャンク・保存復元・全8枠消去・共有アンカー保持を含む。
- 24候補の順位付けのみ: p50 {benchmark['p50Microseconds']/1000:.2f}ms、p95 {benchmark['p95Microseconds']/1000:.2f}ms。ワールド走査・経路探索・通信を含むNPC全体のTick時間ではない。
- `build/libs/{jar.name}` SHA-256: `{sha(jar)}`。
- 学習データ: `TrainingData/npc-v6/manifest.json`、`observed-actions.jsonl.gz`。raw由来とsplit、ハッシュを保持。
- 詳細な機械可読結果: `build/npc-campaign-verification.json`。
- Neoの実インスタンス`D:/Game/Minecraft_Curse/Instances/Neo`のMOD・セーブは変更していない。

今回残った課題は自然地形の高い木・障害物への接近と、失敗候補の切り替え後にも同じ経路制約へ戻ること。40MiBへの拡張だけで解決済みとはせず、未実施19命令と併せて残件とする。
'''
    (ROOT/'Docs/NpcCampaignV6Verification20261026.md').write_text(report,encoding='utf-8')
    readme=f'''# NPC v6の実操作データと40MiBモデル

5 seedの実ログから{len(observed):,}件の実行した操作と成否を抽出した。成功2732・失敗43は個々の操作の結果であり、ミッションの成功数ではない。
25予定命令のうち6試行・1成功・5中断・19未実施。中断したワールドの後続命令は実行していない。畑・協力ダイヤ採掘・戦闘の新しい実操作例は含まれない。

`observed-actions.jsonl.gz`は実行時の56特徴量をそのまま保存する。`worldSeed`、`mission`、rawログSHA、行番号、actionId、outcomeで原記録へたどれる。
候補の選択と実際のtool_startの引数、およびactionIdでtool_resultを結合した。未実行の候補とパディングはmask=falseで成否を捏造しない。

学習はseed 61279〜61281、検証は61282、独立評価は61283。全候補配列の完全重複をsplit間で除外する。
学習62,051件（合成60,000＋以前のNeo監査を含む観測2,051）、検証7,705件、独立評価8,219件。データのSHA-256はmanifest.json。
観測データを候補全体の正解順位として扱わない。評価の候補一致率は合成各7,500件に対する値で、実操作は観測MSEを別に報告する。

モデルは56→1419→1419→1×5専門家、FP32・{model['parameters']:,}パラメータ、{model['weightBytes']:,} bytes。v5から隠れ層を拡張して全層を学習した。
16 epoch、採用epoch {model['training']['selectedEpoch']}、重みSHA `{model['sha256']}`。
上位の外部計画モデルのファインチューニングではない。自然地形のミッションを完走できる保証はなく、木・石炭の失敗を含む限定されたデータである。

再生成には5 seedのrawジャーナルを`build/npc-campaign-archive/<seed>/`へ置き、`python tools/train_npc_policy_v6.py`を実行する。
初回のwarm startにはSHA `23bf31432ffd11ca5a4cbbe316a0632ff13fd4600c01639eadda6423948a86dc`のv5重みを`--initial`で指定する。
ローカルの`initial-policy.bin`が存在すればその不変の重みから再学習する。rawがない新環境では自動的に合成データだけへ切り替えずエラーにする。
rawログ・分割済みの大きいファイル・初期重みはローカル成果物。再配布用の実操作アーカイブとmanifestをこのフォルダに保持する。

詳細: [検証報告](../../Docs/NpcCampaignV6Verification20261026.md)。
'''
    (ROOT/'TrainingData/npc-v6/README_ja.md').write_text(readme,encoding='utf-8')
    print(json.dumps(dict(counts=dict(counts),observed=len(observed),selectors=dict(selectors),unit=dict(unit),modelSha=model['sha256'],jarSha=sha(jar))))

if __name__=='__main__':main()

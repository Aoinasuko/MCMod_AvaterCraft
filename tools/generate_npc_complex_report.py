"""Write the Japanese engineering report from measured scenario/audit results."""
from pathlib import Path
import collections
import datetime
import hashlib
import json
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
analysis = json.loads((ROOT / 'build/npc-complex-analysis.json').read_text(encoding='utf-8'))
if analysis['results'] != {'PASS': 15, 'FAIL': 5}:
    raise SystemExit('This is the original diagnostic report generator. For the repaired runtime, use tools/report_npc_complex_fixes.py.')
audit_path = ROOT / 'build/npc-complex-planning-audit-final.json'
audit = {r['id']: r for r in json.loads(audit_path.read_text(encoding='utf-8'))}
controls = json.loads((ROOT / 'build/npc-complex-control-analysis.json').read_text(encoding='utf-8'))
assert len(analysis['scenarios']) == len(audit) == 20
assert all(r.get('requestSubmitted') for r in audit.values())
status_counts = collections.Counter(r['status'] for r in audit.values())
accepted = sum(status_counts[s] for s in ['VALID_FINITE_PLAN_UNEXECUTED', 'VALID_FIRST_PHASE_ONLY'])
test_suites = [ET.parse(p).getroot() for p in (ROOT / 'build/test-results/test').glob('TEST-*.xml')]
unit_counts = {field: sum(int(s.get(field, 0)) for s in test_suites)
               for field in ['tests', 'failures', 'errors', 'skipped']}
brief = {
    '01': '監視地点から3ブロック超の追撃。持ち場制約を攻撃処理が保持しない。',
    '02': '3波の骨12・腐肉9・火薬6を各専用チェストへ収納。参照上位計画要求8回。',
    '03': '穴掘り・注水・8区画の耕起と播種。種8個の実消費、空バケツへの変化を確認。',
    '04': '実鉱石24個からダイヤ防具4種をレシピで制作。ダイヤ残数0。',
    '05': '原木64・原鉄17・パン5を全量移送。私物の食料は保持。',
    '06': '混在16原木を集め、板材・棒・木の斧を用意し、板材8を収納。',
    '07': '敵追撃が通常移動と別のネイティブ経路を使い、閉じたゲートで経路失敗。',
    '08': '4種類・計8株を収穫し植え直す。未成熟株を保持。',
    '09': '鉄8は完成。未加工原木を燃やして金4用の燃料を残せず、無い原木を探索。',
    '10': '非スタックの鉄の剣42本を、手持ち容量を超えて複数往復で全量移送。',
    '11': 'スケルトン→残りの敵を討伐。村人へのNPCからの攻撃を禁止。',
    '12': '段差・閉じたゲートの先の仲間へパン8個を渡して帰還。',
    '13': '水際の鉄5・金3を実回収し、拠点へ収納。',
    '14': '1個採掘後に道具破損。予備の鉄ツルハシを探す前に木ツルハシ新規調達へ進む。',
    '15': '石炭64を回収。優先庫へ32、予備庫へ32を収納し、手元の石炭0。',
    '16': '約380ブロック先から鉄64を取って戻り、拠点へ収納。',
    '17': '仲間の原鉄3の実配達を待ち、燃料準備・精錬・鉄ツルハシ制作まで完了。',
    '18': 'ネザーへは移動。帰還時にポータル内でクールダウン300が再設定され続ける。荷物7ダイヤは保持。',
    '19': '他NPC予約中の金入りかまどを変更せず、空いているかまどで鉄3を完成。',
    '20': '採掘途中に指示を停止し、120Tick以上経過後も旧収納を実行しない。',
}
labels = {'PLAN_ERROR': '契約拒否', 'VALID_FIRST_PHASE_ONLY': '初回フェーズ受理', 'VALID_FINITE_PLAN_UNEXECUTED': '有限計画受理', 'BLOCKED': '保留'}
lines = [
    '# NPC複合シナリオ20件の検証結果', '',
    '2026-10-25。Minecraft 26.3 / NeoForge 26.3.0.95 / Java 25。', '',
    '## 結論', '',
    f'ゲーム実行系の正式20パターンは **{analysis["results"].get("PASS", 0)}成功・{analysis["results"].get("FAIL", 0)}失敗**。指定5件のうち、スポナー監視は持ち場を離れて失敗し、仕分け・畑造成・ダイヤ防具一式・チェスト全量移送は成功した。', '',
    '現時点では **処理と計画契約の更新を優先**する。今回、ローカルモデルの重みは変更していない。同じ重みで、上位手順だけを変更した比較試験3件はすべて成功した。容量拡張が必要だと判定する根拠はまだ不足している。', '',
    f'別に、実Codex OAuthで日本語20命令の初回計画を監査した。形式上の受理は **{accepted}/20**、契約拒否は **{status_counts["PLAN_ERROR"]}/20**。受理された計画のゲーム内実行までを検証した値ではない。', '',
    '## 検証を分離した理由', '',
    '- ゲーム実行試験：参照TaskPlan、または現在の観測に合わせる決定的な参照上位計画をNpcMindへ渡し、ローカル行動モデル・実経路・レシピ・Mob・転送・最終状態を検証した。ゲーム結果やアイテムをAIの返答だけで成功にしていない。',
    '- 計画監査：管理済みCodex OAuth、ホストの既定モデル（モデル名の上書きなし）で20命令を実際に要求し、TaskPlanの厳密なデコーダで検証した。合成した観測を使い、ゲーム操作ツールはAIから実行できない設定とした。',
    '- この二つを合算した「実AIによる20件の一貫したE2E成功率」は測っていない。参照上位計画の要求時間にはネットワーク・推論時間が含まれない。',
    '- 監視・仕分けは有限の観測窓で後から複数回発生する対象を試験した。無期限の安定稼働を保証する試験ではない。',
    '- 正式結果はパターンごとに環境を分けて順次実行。同時20件の予備試験ではAI要求枠の競合があり、正式集計から分離した。',
    '- テスト用の資材・鉱脈・設備を開始時に用意している。例えば04は露出した24鉱石、鉄ツルハシ、作業台が前提。無資材・未知の洞窟からの冒険全体を成功としたものではない。', '',
    '## 正式20パターン', '',
    '| ID | パターン | 実行系 | ゲーム時間秒 | 実AI初回計画 | 実測した結果 |',
    '|---|---|---|---:|---|---|',
]
for case in analysis['scenarios']:
    lines.append(f'| {case["id"]} | {case["title"]} | {"成功" if case["status"] == "PASS" else "失敗"} | {case["ticks"] / 20:.1f} | {labels.get(audit[case["id"]]["status"], audit[case["id"]]["status"])} | {brief[case["id"]]} |')
lines += [
    '', 'ゲーム時間は20Tick/秒で換算。高速な隔離GameTestで進めたゲーム時間であり、実AI待ち時間を含む実プレイ所要時間ではない。GameTestログの全21件には共通の補助1件を含むため、この表は`complex_01`〜`complex_20`のみを集計する。', '',
    '## 失敗の原因と更新対象', '',
    '### 01：スポナー監視の持ち場離脱', '',
    '`GUARD_ANCHOR_VIOLATION`を実座標で確認。出現したスケルトンへの追撃が監視半径3を超えた。現在の`attack`は近接追撃の1操作で、継続監視のanchor・対象種・追撃半径を保持するGoalではない。重みが守るべき制約を候補生成・移動処理に持たせていない。', '',
    '優先更新：`guard`相当の永続Goal、近隣スポーン検知、追撃禁止／許可半径、射程外の敵への武器切替と待機継続。外部AIへ敵1体ごとに依頼せず、設定済み条件をローカルで評価する。', '',
    '### 07：閉じたゲートを含む戦闘経路', '',
    '`NpcSkills.entityAction`の追撃は`getNavigation().moveTo(target, speed)`を直接使用しており、通常の`move_to`で使う広域の歩行・ゲート処理と入口が異なる。`No path to target entity`が再現した。予備実行には成功する場合もあり、敵位置による間欠性がある。', '',
    '比較試験では同じ重みで、通常の`move_to`を使って先にゲートを通り、その後に攻撃すると成功。優先更新は対象の隣接位置の探索、通常移動と追撃の経路統合、移動中の対象への再探索と猶予。比較は各1回で、モデルの統計的A/B試験ではない。', '',
    '### 09：連続精錬の燃料・材料の機会費用', '',
    '原鉄8・原金4・原木4から鉄8はできたが、次の金4で停止した。最初に一部の原木を板材に加工した後、燃料選択で残る未加工原木を先に燃やし、残った板材だけでは後続バッチに不足。存在しない追加原木を探索し続けた。かまど自体の実燃焼は動いている。', '',
    '原木4を先に板材16へ加工する比較計画では成功。優先更新は燃料の変換効率、後続バッチの見積り、材料として残す在庫の予約、燃やす優先度を扱う資源プランナ。ローカル順位付けの増量だけでは、燃料選択の貪欲なコードは変わらない。', '',
    '### 14：破損後の予備ツールを探さない', '',
    '耐久1の石ツルハシで1個を掘った後、近くのチェストに鉄ツルハシがあっても木ツルハシの新規調達を開始し、無いオーク原木を探索した。`LocalTaskRunner.prepareTool`は`world.preparation`が調達候補を返すと、未実施の`find_chests`へ進まない。', '',
    '破損後に予備の鉄ツルハシを明示的に取り出す比較計画は成功。優先更新は「手持ちの適合品→既知／近隣チェストの上位互換品→製作・採集」の順序と破損イベントでの候補再構築。正しいチェスト候補が作られていない段階のモデル拡張は根本対策にならない。', '',
    '### 18：ポータル内で帰還待ちを続ける', '',
    'ネザー到着と荷物7ダイヤの保持は確認。帰還でタイムアウトした。追加診断の最終状態は`minecraft:the_nether`、足元`minecraft:nether_portal`、`portalCooldown=300`。Minecraftはクールダウン中にポータルへ触れると待ち時間を再設定するため、内部で待つだけでは再利用可能にならない。', '',
    '優先更新は「旧ポータルから離脱→実クールダウン0を待つ→ポータルの内部へ再進入→次元変更を確認」という`travel_dimension`内部の状態制御。上位AIによる追加手順でも回避の余地はあるが、今回はそこまでの往復成功を主張していない。', '',
    '## 実AIが拒否された原因', '',
    '合成観測の初期数量・個人の食料・チーム情報を整合させた正式入力を使用。生成された生の`agentMessage`のみを記録し、認証・アカウント・MCP設定のRPC内容は保存していない。入力構築に問題があった2件は修正後に再要求し、正式20件へ差し替えた。パイロットと修正前記録は別ファイルへ残している。', '',
    '拒否された生JSONでは、`plan_next.target`に「次の段階で行うこと」の日本語説明が入っていた。現実装は`target`を空文字または`namespace:id`形式に制約しており、JSON Schemaの単なるstringとは条件が一致していない。`Invalid task step`となり計画全体を採用できない。原指示理解が通っていても発生する契約の不一致である。', '',
    '優先更新：制御ステップの`target/block/location/arguments`の空文字ルールをスキーマ・プロンプトへ明記し、残務は専用のGoal/継続コンテキストに分ける。無関係なツールや引数まで許可する緩和はしない。拒否理由にフィールド名を含め、限定した形式再生成を行う。', '',
    f'受理{accepted}件のうち、初回フェーズ受理{status_counts["VALID_FIRST_PHASE_ONLY"]}件、有限計画受理{status_counts["VALID_FINITE_PLAN_UNEXECUTED"]}件。どちらも全体の成功を意味しない。英語文はデータセットに用意したが、今回の実AI主評価は日本語20件。', '',
    '## モデル拡張の判断', '',
    f'ローカル重みは{analysis["localModelBytes"]:,} bytes（約20MiB）、SHA-256 `{analysis["localModelSha256"]}`で固定。正式試験の選択ログ{sum(analysis["selectors"].values()):,}件中、候補1つの直接処理が{analysis["selectors"].get("single-legal-action",0):,}件、学習モデルの順位付けが{analysis["selectors"].get("learned-ranker",0):,}件だった。', '',
    '01・07・18は、記録された選択が直接処理であり、監視制約・追撃経路・ポータル再利用の制御を直す必要がある。09・14には学習モデルを使う探索もあるが、その前段で材料を消費し切る／予備ツール候補を作らないことが先に起きている。候補集合が欠けたまま容量を増やす根拠はない。', '',
    '処理修正後に同じ20パターンと複数シードで再評価し、「実行可能な正解候補があるのに誤順位が続く」状況が残れば再学習を検討する。その後に同じ学習・検証分割で容量のA/B比較を行う。指標は任務成功率、無駄操作、再計画回数、Tick時間の上位分位とし、重み容量だけを目的にしない。今回は容量変更・再学習を実施していない。', '',
    '## 更新の優先順位', '',
    '1. 計画契約を一致させ、Goalと継続条件を明示する。攻撃の成功と敵の死亡を区別する。',
    '2. 監視・仕分けの継続タスクと戦闘をローカル状態機械として実行し、追撃に通常移動のソルバを使う。',
    '3. 予備ツールを先に探し、原木の燃料加工効率・将来の必要量・材料予約を資源プランナで扱う。',
    '4. ポータル離脱・クールダウン・再進入・実次元の確認を内部状態として扱う。',
    '5. 同時多数NPCでのAI要求待ちには公平なキューを用意する。処理を整えてから順位付けデータと容量を評価する。', '',
    '## 再現方法と証跡', '',
    '```powershell',
    '.\\tools\\dev.ps1 -Task runNpcComplexGameTest',
    'python tools/analyze_npc_complex_scenarios.py',
    '# 比較3件：通常の実装と重みをそのまま使い、上位の手順だけを変更',
    '$env:AVATARCRAFT_COMPLEX_CASES = "07,09,14"',
    '$env:AVATARCRAFT_COMPLEX_CONTROL = "true"',
    '.\\tools\\dev.ps1 -Task runNpcComplexGameTest',
    'python tools/analyze_npc_complex_scenarios.py --controls',
    '```', '',
    '通常の複合診断では失敗をそのまま失敗にするため、現状はGradleが非0で終了する。成功したことにするための期待失敗アサーションは使っていない。各正式ケースは固有環境で順次実行する。比較後は上記2環境変数を消してから通常の全件診断を実行する。', '',
    '実AI監査は`AVATARCRAFT_COMPLEX_LIVE=1`、`AVATARCRAFT_CODEX_EXECUTABLE`を設定し、`gradlew test --tests dev.avatarcraft.npc.ai.NpcComplexPlanningAuditTest`で明示的に実行する。正式出力先は`AVATARCRAFT_COMPLEX_AUDIT_OUTPUT=build/npc-complex-planning-audit-final.json`で指定できる。認証は既存の管理済みOAuthを使用し、キーやトークンをチャット・リポジトリへコピーしない。', '',
    f'通常ビルド成功。JUnitは{unit_counts["tests"]}件中{unit_counts["tests"] - unit_counts["skipped"]}件成功、明示実行が必要な{unit_counts["skipped"]}件はスキップ、失敗{unit_counts["failures"]}件・エラー{unit_counts["errors"]}件。既存のMinecraft GameTest 88件も成功。これは複合診断の未達5件とは分けた回帰検証である。', '',
    '- `src/npc/java/dev/avatarcraft/npc/ai/NpcComplexGameTests.java`：20件の実ゲーム試験・参照計画。',
    '- `src/test/resources/npc-complex-scenarios.json`：日英20命令・制限時間。',
    '- `src/test/java/dev/avatarcraft/npc/ai/NpcComplexPlanningAuditTest.java`：実AIの厳密な計画監査。',
    '- `build/npc-complex-results/`、`build/npc-complex-evidence/`：正式20件の実測状態と実行ログ。',
    '- `build/npc-complex-analysis.json`：正式集計・失敗ツール・候補選択。',
    '- `build/npc-complex-control-analysis.json`：比較3件とポータル追加診断。',
    '- `build/npc-complex-planning-audit-final.json`：整合済み入力による正式20命令の計画結果。',
    '- `build/npc-complex-gametest-final.log`：正式20件の専用サーバーログ。',
    '- `build/npc-complex-baseline-regression.log`：既存GameTest 88件の成功ログ。', '',
    '本番VPS・プレイ中ワールドへの配置や変更は実施していない。変更したのは診断・監査・集計・実行用の設定と資料であり、上記の残る機能問題を修正済みとはしていない。',
]
(ROOT / 'Docs/NpcComplexScenarioReview.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
manifest = {'verifiedAt': datetime.datetime.now().astimezone().isoformat(), 'physical': analysis['results'],
            'planningAudit': dict(status_counts), 'planningExecutedInWorld': False,
            'localModelBytes': analysis['localModelBytes'], 'localModelSha256': analysis['localModelSha256'],
            'localWeightsChanged': False, 'productionVpsChanged': False, 'baselineGameTestsPassed': 88,
            'unitTests': unit_counts,
            'evidenceFiles': {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in [
                'build/npc-complex-analysis.json', 'build/npc-complex-control-analysis.json',
                'build/npc-complex-planning-audit-final.json', 'src/test/resources/npc-complex-scenarios.json']}}
(ROOT / 'build/npc-complex-verification.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(manifest, ensure_ascii=False, indent=2))

"""Report measured reference-plan and live-provider Minecraft outcomes after runtime fixes."""
from pathlib import Path
import datetime
import hashlib
import json
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
physical = json.loads((ROOT / 'build/npc-complex-analysis.json').read_text(encoding='utf-8'))
live = json.loads((ROOT / 'build/npc-complex-live-analysis.json').read_text(encoding='utf-8'))
assert len(physical['scenarios']) == len(live['scenarios']) == 20
assert all(s['status'] in {'PASS', 'FAIL'} for s in live['scenarios'])
assert all(s['liveAiExecutedInWorld'] for s in live['scenarios'])
before_sha = (ROOT / 'build/npc-complex-before-fixes/model-sha256.txt').read_text().strip()
assert physical['localModelSha256'] == live['localModelSha256'] == before_sha
for s in live['scenarios']:
    rows = [json.loads(line) for line in (ROOT / s['evidence']).read_text(encoding='utf-8').splitlines()]
    assert rows and all(row['actor'] == s['actor'] for row in rows), s['id']
    if s['id'] == '01':
        guard_start = next((i for i, row in enumerate(rows)
                            if row['kind'] == 'tool_start' and row['text'] == 'guard_post'), None)
        assert guard_start is not None, 'Live watch must actually start guard_post'
        assert not any(row['kind'] == 'planning_start' for row in rows[guard_start + 1:]), 'Watch must not pause for replanning'
assert next(s for s in physical['scenarios'] if s['id'] == '01')['oraclePlanningRequests'] == 1

suites = [ET.parse(p).getroot() for p in (ROOT / 'build/test-results/test').glob('TEST-*.xml')]
unit = {key: sum(int(s.get(key, 0)) for s in suites) for key in ['tests', 'failures', 'errors', 'skipped']}
baseline_log = (ROOT / 'build/npc-complex-fixed-baseline.log').read_text(encoding='utf-8', errors='replace')
baseline_match = re.search(r'All (\d+) required tests passed', baseline_log)
baseline = int(baseline_match.group(1)) if baseline_match else None
assert baseline is not None
assert unit['failures'] == unit['errors'] == 0
build_log = (ROOT / 'build/npc-complex-fixed-build-final.log').read_text(encoding='utf-8', errors='replace')
assert 'BUILD SUCCESSFUL' in build_log
by_id = {s['id']: s for s in live['scenarios']}
bad_plans = sum(len(s['planningErrors']) for s in live['scenarios'])
plans = sum(len(s['generatedPlans']) for s in live['scenarios'])
fmt = lambda s: '成功' if s == 'PASS' else '失敗'
lines = [
    '# NPC複合20パターン：処理修正と実AIからの実行検証', '',
    '2026-10-25 / Minecraft 26.3 / NeoForge 26.3.0.95 / Java 25。', '',
    f'参照計画による実行系：**{physical["results"].get("PASS", 0)}/20成功**。実Codex OAuthの生成計画をMinecraftで実行：**{live["results"].get("PASS", 0)}/20成功**。', '',
    f'ローカル重みは **{physical["localModelBytes"]:,} bytesのまま**。開始時と終了時のSHA-256は `{before_sha}` で一致。再学習・容量拡張は行っていない。', '',
    f'実AIの採用計画は{plans}件、計画要求の失敗記録は{bad_plans}件。受理のみの旧監査とは異なり、生成された計画を通常のNpcMind/LocalTaskRunnerへ渡し、実ブロック・アイテム・Mob・次元・位置で判定した。', '',
    '## 修正した処理', '',
    '- 監視：固定アンカーを持つ継続監視`guard_post`と有限時間の`guard`を追加。継続監視は停止指示までローカルで動き、60秒ごとの再計画待ちで空白を作らない。追撃経路を範囲内に制限し、一般の防御候補で制限を迂回させない。携帯している弓・矢を実消費して射撃し、食事・被弾後の帰還も行う。照準が移動時の体の向きを上書きする問題も修正。',
    '- 戦闘：追撃を通常移動と同じ歩行ソルバ・ドア／ゲート処理へ統合。対象の隣接位置、位置変化による再探索、停滞を扱う。`defeat`は1発の命中で終わらず実死亡まで攻撃する。',
    '- 燃料：原木を直接燃やさず、加工用に保持する。直接の`smelt`でも、携帯原木で足りる場合は実際の登録レシピと燃焼時間から板材の加工量を決めて再開する。入力用の原木、道具、防具を燃料として消費しない。手の中の適合する燃料も評価する。',
    '- 道具：手持ちの適合品がなければ、まずチェストを観察し、使える予備・上位互換品を製作や新規採集より優先する。',
    '- ポータル：接触領域から歩いて離れ、実クールダウン0を待ち、経路を再開して再進入する。帰還は実次元と荷物・拠点位置で確認する。',
    '- 計画契約：target/blockはIDまたは空文字。`plan_next`を別スキーマ枝にし、target/block/location/argumentsを空、positionを[]に固定する。残務は元の指示と検証済み進捗で保持する。拒否フィールドを明示し、形式エラーに限り再生成は1回だけ行う。', '',
    '実AI試験で追加検出した操作契約も修正した。`use_on_block`は使用品を主手へ移すため、同じ品の連続使用にslot=41を使えるようにした。バケツはuseOnだけでなく、指定面を狙うバニラuse経路へ接続した。', '',
    '## 20パターンの結果', '',
    '| ID | 試験 | 参照計画での実行 | 実AI→実行 | 実AI要求数 | 実AI試験の壁時計秒 |',
    '|---|---|---|---|---:|---:|',
]
for s in physical['scenarios']:
    a = by_id[s['id']]
    lines.append(f'| {s["id"]} | {s["title"]} | {fmt(s["status"])} | {fmt(a["status"])} | {a["livePlanningRequests"]} | {a["wallSeconds"]:.1f} |')
for s in live['scenarios']:
    if s['status'] != 'PASS':
        lines.extend(['', f'未達 {s["id"]}: `{s["error"]}`。'])
lines += [
    '', '## 条件と検証上の区別', '',
    '- 実AIには参照TaskPlanを渡していない。元の日本語指示、試験で指定する実座標、実インベントリと実ブロックの観測を渡す。複数フェーズ・再計画も実AIが生成する。英語命令はカタログにあるが、この実AI評価は日本語。',
    '- 発話生成は評価対象外とし、作業計画の生成と通常のNPC実行処理を検証した。',
    '- 再計画の配置情報を初期状態で固定してしまう試験側の問題を修正し、要求時に現在のブロック状態を再取得するようにした。中断した旧入力の実行は正式結果へ加算しない。正式表は各ケースの最新の完了結果を集計し、修正後の再実行は対応ログとアクターUUIDで追跡する。',
    '- 01は固定の持ち場で遠距離Mobを倒せるよう、実物の弓と矢64本を支給し、スポナー室を壁で囲った。旧試験の近接装備のみ・開放空間とは同一条件のA/Bではない。敵やドロップを成功時に強制削除せず、矢・武器耐久・被ダメージはバニラ処理。',
    '- 01は自発的な追撃の到達点を半径3以内とし、通常の被弾ノックバック後の帰還を別に扱う。最大の実変位と追撃経路先もJSONへ保存する。NPCの位置を書き換えて制限していない。',
    '- 監視と仕分けは複数回出現する対象を有限時間で試験した。無期限稼働や同時多数NPCの負荷試験ではない。',
    '- 検証専用GameTestサーバーで実行した。ネットワーク待ちの間は試験イベントとMobのAIを休止して高速シミュレーションの時間の歪みを避ける。通常プレイの通信待ち中の戦闘安全性を保証する結果ではない。通常のブロックエンティティTickは継続する。',
    '- 採掘・加工の資材、鉱脈、道具、設備をフィクスチャに配置している。無資材の新規ワールドからの完全自律冒険を成功としたものではない。', '',
    '## 回帰検証', '',
    f'- 通常ビルド成功。JUnit：{unit["tests"] - unit["skipped"]}件成功、{unit["skipped"]}件スキップ、失敗{unit["failures"]}・エラー{unit["errors"]}。',
    f'- 通常のMinecraft GameTest：{baseline if baseline is not None else "成功ログ未確認"}件成功。直接精錬で原木から実際に燃料を加工する回帰試験を追加。', '',
    '## 証跡と再実行', '',
    '- `build/npc-complex-before-fixes/`：修正前の結果と重みハッシュ。',
    '- `build/npc-complex-results/`、`build/npc-complex-evidence/`、`build/npc-complex-analysis.json`：最終の参照計画試験。',
    '- `build/npc-complex-live-results/`、`build/npc-complex-live-evidence/`、`build/npc-complex-live-analysis.json`：生成計画と実行結果、アクターごとのジャーナル。',
    '- `build/npc-complex-fixed-baseline.log`：通常GameTestの回帰ログ。',
    '- `build/npc-complex-live-e2e-20-v2.log`、`build/npc-complex-live-final-fixups.log`：実AI20件の一括試験と、燃料改善後の協力作業・最終ポータル処理の再試験。',
    '- `build/npc-complex-live-continuous-guard.log`：実AIが継続監視を開始し、監視中の再計画なしで60秒以降のスポーンにも対応した試験。',
    '- `build/npc-complex-fix-verification.json`：集計と証跡ハッシュ。', '',
    '```powershell',
    '.\\tools\\dev.ps1 -Task runNpcComplexGameTest',
    'python tools/analyze_npc_complex_scenarios.py',
    '$env:AVATARCRAFT_COMPLEX_E2E = "true"',
    '$env:AVATARCRAFT_CODEX_EXECUTABLE = (Get-Command codex.exe | Select-Object -First 1).Source',
    '.\\tools\\dev.ps1 -Task runNpcComplexGameTest',
    'python tools/analyze_npc_complex_scenarios.py --live',
    'Remove-Item Env:AVATARCRAFT_COMPLEX_E2E',
    '.\\tools\\dev.ps1 -Task runNpcGameTest *> build/npc-complex-fixed-baseline.log',
    '.\\tools\\dev.ps1 -Task build *> build/npc-complex-fixed-build-final.log',
    'python tools/report_npc_complex_fixes.py',
    '```', '',
    '実AIの再実行は利用枠を使う。認証は既存の管理済みOAuthを利用し、トークンをプロジェクトへコピーしていない。VPSへの配置や配布パッケージ更新は行っていない。', '',
]
(ROOT / 'Docs/NpcComplexFixVerification.md').write_text('\n'.join(lines), encoding='utf-8')
manifest = {
    'verifiedAt': datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).isoformat(),
    'physical': physical['results'], 'liveAiExecutedInWorld': live['results'],
    'generatedPlans': plans, 'planningErrors': bad_plans, 'unit': unit, 'baselineGameTestsPassed': baseline,
    'localModelBytes': physical['localModelBytes'], 'localModelSha256': before_sha, 'localWeightsChanged': False,
    'artifacts': {name: hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in [
        'build/npc-complex-analysis.json', 'build/npc-complex-live-analysis.json',
        'build/npc-complex-fixed-baseline.log', 'Docs/NpcComplexFixVerification.md']}}
(ROOT / 'build/npc-complex-fix-verification.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(json.dumps({k:v for k,v in manifest.items() if k != 'artifacts'}, ensure_ascii=True))

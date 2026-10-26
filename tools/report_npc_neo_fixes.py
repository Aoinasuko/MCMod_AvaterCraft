"""Verify the shipped classes/weights against the natural-world evidence and write a local report."""
import datetime
import gzip
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def read(path):
    return json.loads((ROOT/path).read_text(encoding='utf-8'))

def sha(path):
    return hashlib.sha256((ROOT/path).read_bytes()).hexdigest()

def main():
    run_path=Path('build/npc-natural-results/61279.json')
    run=read(run_path)
    assert run['state']=='passed' and run['storedDiamonds']==10, 'Natural mission has not passed'
    assert run['initialTools']==0 and run['placedResources']==0 and run['livePlanning']
    assert run['generator']=='NoiseBasedChunkGenerator'
    jar=Path('build/libs/bep_3davater_companion-26.3-0.10.0.jar')
    model=read(Path('src/npc/resources/data/bep_3davater_companion/ai/action-policy.json'))
    assert model['version']==5 and model['training']['passed']
    weights=Path('src/npc/resources/data/bep_3davater_companion/ai/action-policy.bin')
    assert sha(weights)==model['sha256']
    for key in ('validation','independentTest'):
        metrics=model['training'][key]
        assert metrics['exactOptimalAccuracy']>=.98 and metrics['illegalSelectionRate']==0
    with zipfile.ZipFile(ROOT/jar) as z:
        for name,expected in run['runtimeClassSha256'].items():
            assert hashlib.sha256(z.read('dev/avatarcraft/npc/ai/'+name+'.class')).hexdigest()==expected, name+' changed after natural QA'
        assert hashlib.sha256(z.read('data/bep_3davater_companion/ai/action-policy.bin')).hexdigest()==model['sha256']
    counts=dict(tests=0,skipped=0,failures=0,errors=0)
    for path in (ROOT/'build/test-results/test').glob('TEST-*.xml'):
        root=ET.parse(path).getroot()
        for key in counts:counts[key]+=int(root.attrib.get(key,0))
    assert counts['tests']>0 and counts['failures']==counts['errors']==0
    log=(ROOT/'build/npc-neo-final-regression.log').read_text(encoding='utf-8',errors='replace')
    assert 'BUILD SUCCESSFUL' in log and 'failed at ' not in log
    game_tests=int(re.findall(r'(\d+) GAME TESTS COMPLETE',log)[-1])
    character=read(Path('build/npc-blocked-live-result.json'))
    assert character['speech'] and '作業を保留しています' not in character['speech']
    archive=Path('TrainingData/npc-v5/observed-actions.jsonl.gz')
    with gzip.open(ROOT/archive,'rt',encoding='utf-8') as f:observed=[json.loads(line) for line in f]
    assert len(observed)==222 and sum(e['outcome'] for e in observed)==209
    result=dict(dateJst=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).isoformat(),
        naturalMission={k:run[k] for k in ('seed','state','storedDiamonds','gameTicks','wallSeconds','planningRequests',
                                         'initialTools','initialFoodPerNpc','placedResources','mobSpawning','generator')},
        naturalEvidenceSha256=sha(run_path),jar=dict(path=str(jar),sha256=sha(jar)),
        runtimeClassSha256=run['runtimeClassSha256'],junit=counts,gameTests=game_tests,
        model=dict(version=5,weightBytes=model['weightBytes'],sha256=model['sha256'],
                   validation=model['training']['validation'],independent=model['training']['independentTest']),
        observedData=dict(examples=222,successes=209,failures=13,path=str(archive),sha256=sha(archive)),
        blockedSpeech=character)
    target=ROOT/'build/npc-neo-fix-verification.json'
    target.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    report=f'''# Neo環境のNPC修正と自然地形での検証

2026年10月26日。二人のNPCが道具を持たずに開始し、実AIの計画と通常の実行処理でダイヤモンド10個を採掘し、指定チェストに10個収納した。最終版の主要9クラスと重みを検証時のSHA-256で照合した。

## 修正内容

- 経路と候補切り替え：失敗した対象を候補生成時に除外し、下方を含む複数の鉱石を扱う。すべて除外されたときも、観測した対象への方向を保って階段探索へ戻る。近距離の探索未完了は、ロード・支持・保護・危険を検査する局所作業経路へ切り替える。
- 帰還：歩行記録を利用し、小さな落差を含む帰路は中継点として再検証する。必要なら実際の掘削・設置で段差を直す。記録だけで歩いたことや到着したことにはしない。
- 資源と設備：所有者ごとに実在する作業台・かまどを記憶して再利用する。携帯設備の配置を依存解決へ統合し、斜面で作業台だけを何個も作る分岐を削除した。長い地下採掘の前に補修用木材を確保し、坑道では安全な側面を掘って設備を置ける。足場材の採集がさらに足場を要求する循環、自分の帰路を足場材として採掘する動作を防ぐ。
- 一括採集：必要数の実ドロップがそろうまでは高所の幹を続けて処理し、毎回地上へ降りて足場を作り直すことを避ける。必要数になったら回収する。
- 協力：担当者が不足品・必要数・実所持数・停滞時間を共有し、待機側が材料を集めて実配達する。元の数量・収納先を維持し、支援回数を制限する。委任は同じ所有者・同じ次元の128ブロック以内。再計画時に前の処理を停止し、処理の競合を防ぐ。
- 保留通知：性格・話し方と確認済みの停止理由から非同期で生成する。発話には操作権限を与えず、新しい指示や停止後に古い通知を流さない。接続失敗・時間切れ用の文は性格JSONの `blockedMessage` でも指定できる。

実AIで確認した保留通知：

> {character['speech']}

## データセットと再学習

48入力から56入力へ拡張し、高さ差、経路の確認状態、移動・編集コスト、失敗、設備、仲間の進捗を渡す。候補の実距離を使い、未確認の経路を確認済みと同一視しない。

実ログ222件は209成功・13失敗。実行した候補にだけラベルを付け、未実行の候補の成否は不明のまま保持する。これに従来の合成候補48,000件と地形・経路の合成候補12,000件を加えて16epoch学習した。実ログは学習側にまとめ、検証・独立評価の各7,500件とは分離した。自然地形の試行は学習に入れていない。

独立評価の厳密な最適候補選択率は{model['training']['independentTest']['exactOptimalAccuracy']*100:.2f}%、実行不能候補の選択率は0%。教師スコア差0.04以内なら{model['training']['independentTest']['optimalAccuracy']*100:.2f}%。これらは候補選択の評価で、ゲームの依頼完走率ではない。中間層の幅や層数は増やさず、入力追加によって重みは21,180,020 bytesになった。

詳細は `Docs/NpcPolicyV5.md`、データと再現手順は `TrainingData/npc-v5/README_ja.md`。元の生ログがなくても、保存した観測済みデータから学習・検証・独立評価のファイルを同じSHA-256で再生成できることを確認した。

## 自然地形の完走条件と結果

| 項目 | 結果・条件 |
| --- | --- |
| 地形 | NoiseBasedChunkGeneratorによる通常地形、seed {run['seed']} |
| 開始地点 | {run['start']}。地表の安全と樹木だけで森林地点を選び、鉱石位置は選定に使わない |
| 指定チェスト | {run['chest']}、開始時は空 |
| 初期所持品 | 二人とも道具なし。食料はパン16個ずつ |
| 追加配置 | 指定チェストのみ。鉱石・道具・作業台・かまど・採掘路の追加配置なし |
| 計画 | 実Codex OAuth。参照手順や鉱石座標を渡さず、元の日本語命令と通常のNPC観察を使用 |
| 最終収納数 | {run['storedDiamonds']}個。実チェストの中身で判定 |
| 実行時間 | {run['wallSeconds']:.1f}秒、{run['gameTicks']:,}ゲームTick |
| 計画要求 | {run['planningRequests']}件 |

Minecraft 26.3 / NeoForge 26.3.0.95の隔離GameTestワールドで行った。元のNeo環境のワールドは変更していない。敵の自然スポーンと天候変化はGameTest設定で無効であり、敵対Mobを含む新規サバイバル全体の完走試験ではない。計画待ち中は同時要求数を数え、1件だけ終了したことで残りの待ち時間が高速シミュレーションされないようにした。

初回の完走は約313.7秒だった。その後、作業台の重複製作と帰路の扱いを修正し、最終版を新規生成した同じseedで再実行した。途中の失敗・修正中断は成功数へ含めていない。同じseedでの再実行であり、多数seedの成功率を測った結果ではない。

## 回帰検証と成果物

- 通常ビルド成功。JUnit {counts['tests']}件中、失敗0・エラー0・スキップ{counts['skipped']}件。
- 通常Minecraft GameTest {game_tests}件成功。下方鉱石への切り替え、離れた既設かまどの再利用、待機側からの実物配達、小さな落差のある帰路の実修復を含む。
- 実AIで性格付き保留通知を確認。単体試験で操作権限なしと遅れて返った通知の取消を確認。
- 修正版JAR：`{jar.as_posix()}`。SHA-256：`{result['jar']['sha256']}`。
- 重みSHA-256：`{model['sha256']}`。

証跡は `build/npc-natural-results/61279.json`、`build/npc-natural-final-revision.log`、`build/npc-neo-final-regression.log`、`build/npc-blocked-live-result.json`、`build/npc-neo-fix-verification.json`。自然試験の生ジャーナルは `run-npc-natural-qa-61279/gametestserver/gametestworld/bep_3davater_companion/journal/` にある。次のGameTestで初期化される前に保存する。
'''
    (ROOT/'Docs/NpcNeoFixVerification20261026.md').write_text(report,encoding='utf-8')
    print(json.dumps(dict(natural=result['naturalMission'],junit=counts,gameTests=game_tests,jarSha256=sha(jar)),ensure_ascii=False))

if __name__=='__main__':main()

# 開発・検証ツール

## Javaのビルドとテスト

Java 25を用意して `gradlew.bat build`（Windows）または `./gradlew build`（macOS/Linux）を実行します。Windowsでは `dev.ps1 -Task build`、`test`、`runClient` も使用できます。出力先は `build/libs/`、開発用ゲームディレクトリは `run/` です。

アバターなしで単体テストを実行できます。変換済みアバターを使う7件の統合テストは、環境変数 `AVATARCRAFT_TEST_AVATAR` で `.avc` のパスを指定した場合に実行します。未指定の場合はスキップし、指定先が存在しない場合は失敗します。外部アバターやその画像・テクスチャはGitへ追加しないでください。

```powershell
$env:AVATARCRAFT_TEST_AVATAR = 'path/to/test-avatar.avc'
.\tools\dev.ps1 -Task test
Remove-Item Env:AVATARCRAFT_TEST_AVATAR
```

統合テストには、このExporterで標準モーションの割当を使って変換したHumanoidアバターを指定します。頭、腰、左右の腕・手・脚とそのメッシュ、およびPhysBone設定（physicsVersion=2）を含むデータが必要です。体格や独自モーションによって、一人称の表示範囲や水平姿勢の期待値が異なる場合があります。専用のボーン・物理フィクスチャを使う単体テストは外部ファイルに依存しません。

## Unity MCP

`unity_mcp.py` は、別途導入したUnity MCPへ接続する任意の開発用クライアントです。MODやExporterの通常利用には不要です。認証情報や特定のインスタンスID・ポートをリポジトリへ保存しません。

Unity MCPが作成したインスタンス記述JSONを `--descriptor` または環境変数 `UNITY_MCP_DESCRIPTOR` で指定します。Windowsで指定を省略した場合は、ユーザーの `LOCALAPPDATA/UnityMCP/instances/` にJSONが1件だけあるときに限り自動選択します。複数ある場合は明示的に指定してください。

```powershell
python tools/unity_mcp.py --descriptor path/to/instance.json tools/call tools/unity-status.json
python tools/unity_mcp.py --descriptor path/to/instance.json code tools/validate-motions.cs
```

C#スクリプトでは、Hierarchyで明示的に選択したHumanoidアバターのAnimatorを使用します。アバター名でシーンを検索しません。MCPのC#実行環境にはSystem、System.Linq、UnityEngine、UnityEditorが必要です。

- `unity-status.json` / `unity-compile.json` / `unity-probe.json`：接続先の状態・コンパイル・パッケージ情報を確認します。
- `unity-refresh.json`：AssetDatabaseを更新します。
- `unity-export.json`：選択したアバターをプロジェクト直下の `AvatarCraftExports/avatar.avc` へ変換します。同名ファイルは更新されます。
- `inspect-*.cs`：選択したアバターの物理・モーション・Humanoidサンプリングを調べます。
- `validate-physbone-export.cs`：選択したアバターにあるPhysBoneの型から一時フィクスチャを生成して検証します。対応するSDKが必要です。
- `validate-motions.cs`：モーション検証とプレビュー画像を作成します。`Assets/AvatarCraft/` に設定・編集用Clipを生成し、画像は `Library/AvatarCraftDiagnostics/motion-preview/` へ保存します。
- `validate-humanoid-motion.cs`：一時プレビューシーンでHumanoidのカーブを検証します。
- `capture-generated-motions.cs` → `refresh-generated-motions.cs`：未編集の生成Clipのハッシュを記録し、その後も一致するClipだけ更新します。記録はUnityプロジェクト内の `Library/AvatarCraftDiagnostics/` に保存します。実行前に対象プロジェクトの変更を保存してください。
- `refresh-motion-library.cs`：標準ライブラリのキャッシュを破棄します。
- `refine-jump-transition.cs`：既定の切替時間を使う既存プロファイルを更新します。実行前に対象プロジェクトの変更を保存してください。

これらは開発用スクリプトです。生成するアセットや出力ファイルは検証プロジェクト側へ置かれ、リポジトリ固有の絶対パスには書き込みません。

## モーション生成と配布

`python tools/build_motion_library.py` で標準の `motions.json` を生成します。ビルド後に `python tools/package_release.py` で配布用JAR・UPM tarball・日本語ガイド・チェックサムを `PublishPackage/` へまとめます。既存の同じ版は上書きしません。


## NPCとOAuthの検証

`tools/dev.ps1 -Task runNpcGameTest` は `run-npc-qa/` の独立したGameTestワールドを使います。`AVATARCRAFT_CODEX_LIVE=1` を指定した `-Task test` は、サインイン済みCodex CLIの実接続試験も行います。`AVATARCRAFT_CODEX_EXECUTABLE` は試験用のCLIパス指定です。実接続試験は利用枠を使います。通常のテストではスキップします。

`tools/dev.ps1 -Task runNpcComplexGameTest` は `run-npc-complex-qa/` の隔離ワールドで複合20シナリオを順次実行します。参照上位計画を使い、実ブロック・インベントリ・Mobの状態で成否を判定します。未対応の機能も診断するため、失敗時は非0終了します。`python tools/analyze_npc_complex_scenarios.py` で実測結果とジャーナルを `build/npc-complex-analysis.json` と `build/npc-complex-evidence/` に集計します。`AVATARCRAFT_COMPLEX_CASES=07,09,14` で対象を限定でき、`AVATARCRAFT_COMPLEX_CONTROL=true` は比較用の参照手順へ切り替えます。比較結果の集計には `--controls` を付け、通常試験へ戻す前に両環境変数を削除してください。

`NpcComplexPlanningAuditTest` は、別途 `AVATARCRAFT_COMPLEX_LIVE=1` を指定したときだけ、日英カタログの日本語20命令を実Codexで計画します。生成計画の形式を検査し、ゲーム内では実行しません。`AVATARCRAFT_COMPLEX_AUDIT_OUTPUT=build/npc-complex-planning-audit-final.json` で正式出力先を指定できます。実行系と計画監査の成功率は分けて扱います。集計後の `python tools/generate_npc_complex_report.py` は、正式実行結果・比較結果・正式計画監査を読み、`Docs/NpcComplexScenarioReview.md` と証跡ハッシュ付きの `build/npc-complex-verification.json` を生成します。

実AIからゲーム内実行までを試す場合は、`AVATARCRAFT_COMPLEX_E2E=true` と `AVATARCRAFT_CODEX_EXECUTABLE` を設定して `runNpcComplexGameTest` を実行します。利用枠を消費します。`NpcComplexLiveBrain` が管理済みOAuthで計画し、NPCの実行系へ渡します。参照手順は渡しません。実測した配置・指定座標を各計画要求時に取得し、最終状態で成否を判定します。高速GameTestによる時間の歪みを避けるため、ネットワーク待ちの間は試験イベント・MobのAIを休止します。通常プレイの通信待ち中の安全性・遅延の試験とは区別してください。`python tools/analyze_npc_complex_scenarios.py --live` で `build/npc-complex-live-results/` とサーバーのジャーナルを集計します。次のGameTest起動でワールドが初期化される前に集計してください。通常の参照試験へ戻すときは `AVATARCRAFT_COMPLEX_E2E` を削除します。

修正後の最終資料は `python tools/report_npc_complex_fixes.py` で `Docs/NpcComplexFixVerification.md` に生成します。通常ビルド、通常GameTestの成功ログ、参照20件、実AI20件の集計と、各NPCの証跡が必要です。旧 `generate_npc_complex_report.py` は修正前の15成功・5失敗の診断専用です。

開発クライアントは `AVATARCRAFT_QA_WORLD` に既存の検証用ワールドフォルダ名を指定すると、そのワールドを直接開きます。

`validate-visible-bounds.cs` は一時メッシュを生成し、インポートされた境界の余白・未使用頂点を除いて寸法を求め、ルートの縮尺を一度だけ適用することをUnityで確認します。シーンは保存しません。

NPCのHTTPプロバイダーはJUnitの模擬APIで検証します（外部APIキー不要）。実環境の設定は `config/avatarcraftnpc-ai.toml` です。開発GUIは提供画像に合わせて209×272の論理領域を使い、小画面では描画と入力を同倍率に縮小します。

0.4.0以降、NPC用JARには再配布が許可されたタートブだけを同梱します。`tools/package_release.py` は同梱モデルの名前・エントリ・ハッシュと作者クレジットを検証し、他のモデルを含む場合は失敗します。

## lilToon素材の検証

`tools/generate_surface_shader.py` はUnityの `Resources/AvatarCraft/surface.json` を共通定義として、Java用定義とGLSLのアトラス参照関数を同期します。定義を編集した場合は生成後にビルドしてください。

`tools/dev.ps1 -Task test` でCPU側のアトラス・合成とGPU検証用フィクスチャを生成します。Pythonへ `moderngl` と `Pillow` を導入し、`python tools/validate_surface_gpu.py` で実GPUのコンパイル・描画ピクセルを検証できます。Minecraftのウィンドウや操作には触れず、非表示の独立したOpenGLコンテキストを使います。結果は `build/surface-qa/` へ保存します。

## 非公開プレイヤー同期の検証

`PrivateAvatarTest` は複数のプロトコルクライアントを模し、実際のタートブと表情設定を暗号化して受け渡します。暗号・宛先拘束・他人の装着拒否・遅れての参加・改ざん・容量予約・時間切れ・退出時の削除を確認します。GameTestの `private_avatar_wire` は実際のNeoForgeパケットCODECとMinecraftサーバー宛てのサイズ上限を検証します。クライアント実機2台の画面確認とは別の検証です。

## ローカル行動モデル

`python tools/generate_npc_curriculum.py`で日英216,000組の命令・観察・計画・候補データを生成し、`python tools/train_npc_policy.py`でversion 4の5専門家を学習します。48→1000→1000→1を専門家ごとに持ち、計5,255,005パラメータ・21,020,020 bytesです。各候補では1専門家だけを使用します。既定は学習192,000組・40epoch、検証12,000組・独立評価12,000組。初期重みは生成先の`initial-policy.bin`へ保存します。言語理解は接続AI、ローカルモデルは構造化候補の順位付けです。推論はJavaのみで、利用者にPython・GPUの導入は不要です。`tools/export_npc_policy_examples.py`のログ抽出はレビュー資料用で、未レビューの選択結果を正解として自動学習しません。`tools/NpcCurriculumCheck.java`は全想定計画を実際のTaskPlanの契約で検証します。詳しくは [構成と学習手順](../release/NPC_LocalAI_ja.md)。

`tools/NpcPolicyBenchmark.java` はJava 25のソースファイル実行で、コンパイル済みNPCクラス・NPCリソース・Gsonをクラスパスに指定して動かします。24候補の評価だけを測り、ワールド探索・経路探索・API応答時間は含みません。

`tools/NpcToolCatalog.java` は同じクラスパスで実際の`NpcTools`定義をJSONへ抽出します。出力を`build/npc-tools.json`へ保存し、`python tools/generate_npc_tool_docs.py`で`Docs/NpcTools.md`と配布用`release/NPC_Tools_ja.md`を生成します。全57種の引数名・型と日本語の実行条件を同期し、説明のないツールがあれば失敗します。

## アバターアンカーの検証

`tools/dev.ps1 -Task runAnchorGameTest`は専用の`run-anchor-qa/`ワールドで、所有者をまたぐ共有・保存復元・改名と撤去・編集セッション・実レシピ・周囲8ブロックの収納と取り出し・満杯時の誤収納防止・採掘ドロップを確認します。`runNpcGameTest`にも同じ試験を含めます。JUnitの`AnchorIndexTest`はMinecraftを起動せずにIDの正規化・重複・保存形式を確認します。

実AIの計画生成から収納完了まで試す場合だけ、`AVATARCRAFT_ANCHOR_LIVE=true`を設定して`runAnchorGameTest`を実行します。設定済みのCodex OAuthを使い、外部AIへ実際に要求します。結果は`build/anchor-live-result.json`へ出力し、通常の回帰試験では外部AIを呼びません。

`runAnchorClient`は`run-anchor-ui-qa/`の`Anchor UI QA`ワールドを開きます。画面上のID入力・保存・再表示を確認するための独立した開発実行です。配布JARでは開発用GameTestを登録しません。配布パッケージの作成時にはアンカーのクラス・提供画像と同一のテクスチャ・新しい2レシピも検証します。

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

`python tools/train_npc_policy.py` はversion 3のトレーナーを呼び、開発用のNumPy・PyTorchで48→1000→1000→1、1,051,001パラメータ・4,204,004 bytesの候補ランキングモデルを再学習します。既定は合成96,000例・100epoch、検証6,000例・独立評価4,000例です。推論はJavaのみで、利用者にPython・GPUの導入は不要です。`tools/export_npc_policy_examples.py` でゲームログからレビュー用の候補を抽出できます。学習と検証データは別に用意し、レビュー済みの正解だけを追加します。詳しくは [構成と学習手順](../release/NPC_LocalAI_ja.md)。

`tools/NpcPolicyBenchmark.java` はJava 25のソースファイル実行で、コンパイル済みNPCクラス・NPCリソース・Gsonをクラスパスに指定して動かします。24候補の評価だけを測り、ワールド探索・経路探索・API応答時間は含みません。

`tools/NpcToolCatalog.java` は同じクラスパスで実際の`NpcTools`定義をJSONへ抽出します。出力を`build/npc-tools.json`へ保存し、`python tools/generate_npc_tool_docs.py`で`Docs/NpcTools.md`と配布用`release/NPC_Tools_ja.md`を生成します。全51種の引数名・型と日本語の実行条件を同期し、説明のないツールがあれば失敗します。

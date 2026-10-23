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

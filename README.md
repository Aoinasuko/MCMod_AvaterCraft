# AvatarCraft

Minecraft Java **26.3** / NeoForge **26.3.0.95** / Java **25** 向けのアバターMODと、Unity **2022.3** 向け変換ツールです。

## 使い方

1. UnityのPackage Managerで `unity/Packages/dev.avatarcraft.exporter/package.json` を `Add package from disk...` から追加します。配布版の `.tgz` は `Add package from tarball...` から追加できます。
2. Humanoid Animatorのあるアバターのルートを選び、`Tools > AvatarCraft > Avatar export and motions` を開きます。34動作それぞれのモーション、再生速度・切替時間を選び、設定を保存して `.avc` を出力します。任意のAnimationClipによる差し替えと、編集用39本の `.anim` の生成にも対応します。Modular AvatarはNDMF経由で複製上に適用します。詳しくは [設定ガイド](release/Guide_ja.md)。
3. `.avc` をMinecraftの `config/avatarcraft/avatars/` に配置し、MODのJARを `mods/` に配置します。
4. ワールド内でJISキーボードの **@** を押すと、プレビュー画像付き一覧を開きます。キーはMinecraftの操作設定から変更できます。英語配列では `[` の物理位置です。
5. アバターを選択し、「表情設定」でイベントごとのシェイプキーを調整します。検索、ページ切替、プレビュー、まばたきの最小／最大間隔、保存に対応します。Esc／キャンセルで未保存の編集を破棄します。

選択は `config/avatarcraft/selection.json`、表情は `config/avatarcraft/expressions/<avatar-id>.json` に保存します。アバターを再変換しても同じIDなら表情設定は維持されます。ファイル更新後は一覧の「再読込」で読み直し、アバターを選択し直してください。

初期設定は2〜4秒間隔のまばたきです。対応する形状があれば `blink` を閉眼に、`vrc.v_aa` を食事の口に自動割当します。被ダメージの初期表情は閉眼です。

## 開発

```powershell
.\tools\dev.ps1 -Task build
.\tools\dev.ps1 -Task test
.\tools\dev.ps1 -Task runClient
```

Java 25が必要です。通常はWindowsで `gradlew.bat build`、macOS/Linuxで `./gradlew build` を実行できます。`dev.ps1` は一時ディレクトリを短いパスへ設定するWindows用の補助スクリプトです。

ビルド成果物は `build/libs/avatarcraft-0.1.0.jar` です。開発クライアント用の `.avc` は `run/config/avatarcraft/avatars/` に配置します。アバターデータはリポジトリに含めません。

テストは外部アバターなしで実行できます。変換済みアバターを使う7件の統合テストは、`AVATARCRAFT_TEST_AVATAR` 環境変数で検証用 `.avc` を指定した場合に実行します。指定しない場合はスキップします。検証データの条件とUnity用の開発ツールは [tools/README.md](tools/README.md) を参照してください。

配布物はビルド後に `python tools/package_release.py` で `PublishPackage/` へ生成できます。同じ版が存在する場合は上書きしません。

## 現在の対応範囲

- ローカルプレイヤーの三人称モデルとGUI上の3Dプレビュー。人型向けの34動作・39種類のモーション、上半身動作と被ダメージの重ね合わせ、左右の手への対応、頭の視線、手持ちアイテムのボーンへの追従。
- 一人称の腕・手・指・袖をアバターへ置換。空手、攻撃、持ち替え、左右の地図表示に対応。既存 `.avc` の再変換は不要です。
- 複数フレームのBlendShape、イベント別表情、独立したまばたき。
- PhysBoneのAngle / Hinge / Polar制限、制限軸、伸縮上限、各カーブ、有効状態、複数子・入れ子の設定。古い `.avc` は制限が含まれないため再変換してください。
- lilToon設定の読取と、基本色・UV・透過・影境界・リム・発光・アウトラインの近似描画。

VRChatの非公開PhysBoneソルバーとの完全一致、Animator FX／Expression Menuの実行、Minecraft防具・エリトラの外観、他プレイヤーへの共有・自動配布は未対応です。lilToonのノーマルマップ・MatCap・多層テクスチャや特殊マスクは保存対象を一部含みますが描画には未対応です。アバターファイルはMODに同梱しません。

詳しい導入手順は [導入ガイド](release/README_ja.md)、設定と対応範囲は [設定ガイド](release/Guide_ja.md) を参照してください。

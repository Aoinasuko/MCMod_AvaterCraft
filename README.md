# AvatarCraft

Minecraft Java **26.3** / NeoForge **26.3.0.95** / Java **25** 向けのアバターFramework MOD、AIアバターNPCのサブMODと、Unity **2022.3** 向け変換ツールです。

## 使い方

1. UnityのPackage Managerで `unity/Packages/dev.avatarcraft.exporter/package.json` を `Add package from disk...` から追加します。配布版の `.tgz` は `Add package from tarball...` から追加できます。
2. Humanoid Animatorのあるアバターのルートを選び、`Tools > AvatarCraft > Avatar export and motions` を開きます。34動作それぞれのモーション、再生速度・切替時間を選び、設定を保存して `.avc` を出力します。任意のAnimationClipによる差し替えと、編集用39本の `.anim` の生成にも対応します。Modular AvatarはNDMF経由で複製上に適用します。詳しくは [設定ガイド](release/Guide_ja.md)。
3. `.avc` をMinecraftの `config/avatarcraft/avatars/` に配置し、MODのJARを `mods/` に配置します。
4. ワールド内でJISキーボードの **@** を押すと、プレビュー画像付き一覧を開きます。キーはMinecraftの操作設定から変更できます。英語配列では `[` の物理位置です。
5. アバターを選択し、「表情設定」でイベントごとのシェイプキーを調整します。検索、ページ切替、プレビュー、まばたきの最小／最大間隔、保存に対応します。Esc／キャンセルで未保存の編集を破棄します。

選択は `config/avatarcraft/selection.json`、表情は `config/avatarcraft/expressions/<avatar-id>.json` に保存します。アバターを再変換しても同じIDなら表情設定は維持されます。ファイル更新後は一覧の「再読込」で読み直し、アバターを選択し直してください。

初期設定は2〜4秒間隔のまばたきです。対応する形状があれば `blink` を閉眼に、`vrc.v_aa` を食事の口に自動割当します。被ダメージの初期表情は閉眼です。

## マルチプレイの非公開アバター

選択中のモデルと表情設定を暗号化してサーバーへ送信し、周囲のプレイヤーは表示専用キャッシュとして受信します。装着できるのはアップロードした本人だけで、サーバーはMinecraftの認証済みUUIDを検証します。受信側のアバター一覧やNPC選択には追加しません。復元・検証が済むまで通常スキンを表示します。

専用サーバーは `online-mode=true` が必要です。詳細、キャッシュと利用規約上の確認点は [マルチプレイガイド](release/Multiplayer_ja.md) を参照してください。

## AIアバターNPC

NPC用サブMODは、クラフトした召喚石からアバターを召喚し、Codex CLIのChatGPT OAuth認証、OpenAI互換API（LM Studio・Ollamaを含む）、Anthropic互換APIで動かします。右クリックで上下に並べたNPC・プレイヤーの持ち物、防具・主手・副手、性格JSON、アバター、作業指示、記録を操作できます。近くのNPCとの会話、作業中の定期発言、クラフトできる記録帳を備えます。標準モデル「タートブ」（作者：aoi_nasuko）を同梱し、共通のソウルストーンから召喚します。モデルと性格はGUIから変更でき、追加モデル・性格は外部ファイルとして読み込みます。近くの所有者のチャットを会話や作業指示として受け取れます。

召喚上限はサーバー設定で1人3体（-1で無制限）。倒れたNPCは所持品を落とし、所有者のリスポーン地点で待機します。所持品を保持する設定もあります。「呼び戻しの地図」から自分のNPCを別次元から呼び戻せます。通常会話は同一次元・半径64以内、重要通知は所有者へ距離を問わず届きます。レシピと設定は [NPCガイド](release/NPC_Guide_ja.md) を参照してください。

サーバー側でCodex CLIへログインし、NPCを使うクライアントとサーバーへ両方のJARを導入します。導入・レシピ・認証・保存先・APIは [NPCガイド](release/NPC_Guide_ja.md) を参照してください。

新しい日英の指示は現在のAIが計画へ変換し、通常の行動は同梱の約20MiB・5専門家の学習済みモデルで選択します。日英21.6万組の命令・状況データに、曖昧な参照、不足分、収納先、除外指定、確認が必要な例を含めています。仲間への担当割当、空の持ち物からの道具準備と実精錬、階段状の地下採掘、最大8件の場所記憶と回収・帰還に対応します。アバターアンカーに付けた場所IDはすべてのNPCで共有し、名前での移動と周囲のチェストへの収納・取り出しに使用できます。NPC周囲3×3チャンクを常時読み込みし、複雑な依頼は設定した上位モデルで段階的に計画します。木製ドアの開閉、樹種をまたぐ原木の採集と収納、成熟作物の収穫にも対応します。発言は別に30〜60秒ごと（既定45秒）に生成します。構成・対応範囲・再学習は [ローカル行動モデル](release/NPC_LocalAI_ja.md)、引数・条件は [全63種のツール一覧](release/NPC_Tools_ja.md) を参照してください。新規ワールドからドラゴン討伐までの完全自律完走は未検証です。

## 開発

```powershell
.\tools\dev.ps1 -Task build
.\tools\dev.ps1 -Task test
.\tools\dev.ps1 -Task runClient
.\tools\dev.ps1 -Task runNpcGameTest
```

Java 25が必要です。通常はWindowsで `gradlew.bat build`、macOS/Linuxで `./gradlew build` を実行できます。`dev.ps1` は一時ディレクトリを短いパスへ設定するWindows用の補助スクリプトです。

ビルド成果物はFrameworkの `build/libs/avatarcraft-0.7.1.jar` と、NPC用の `build/libs/avatarcraft-npc-0.7.1.jar` です。開発クライアント用の `.avc` は `run/config/avatarcraft/avatars/` に配置します。再配布が許可された標準のタートブだけをリポジトリとNPC用JARに含めます。その他のアバターは外部ファイルです。

テストは外部アバターなしで実行できます。変換済みアバターを使う7件の統合テストは、`AVATARCRAFT_TEST_AVATAR` 環境変数で検証用 `.avc` を指定した場合に実行します。指定しない場合はスキップします。検証データの条件とUnity用の開発ツールは [tools/README.md](tools/README.md) を参照してください。

配布物はビルド後に `python tools/package_release.py` で `PublishPackage/` へ生成できます。同じ版が存在する場合は上書きしません。

## 現在の対応範囲

- ローカル／他プレイヤーの三人称モデルとGUI上の3Dプレビュー。人型向けの34動作・39種類のモーション、上半身動作と被ダメージの重ね合わせ、左右の手への対応、頭の視線、手持ちアイテムのボーンへの追従。
- 一人称の腕・手・指・袖をアバターへ置換。空手、攻撃、持ち替え、左右の地図表示に対応。既存 `.avc` の再変換は不要です。
- 複数フレームのBlendShape、イベント別表情、独立したまばたき。
- PhysBoneのAngle / Hinge / Polar制限、制限軸、伸縮上限、各カーブ、有効状態、複数子・入れ子の設定。古い `.avc` は制限が含まれないため再変換してください。
- lilToon設定の読取と、基本色・UV・透過・影境界・リム・発光・アウトラインの近似描画。

VRChatの非公開PhysBoneソルバーとの完全一致、Animator FX／Expression Menuの実行、Minecraft防具・エリトラの外観は未対応です。lilToonの法線2層、MatCap2層、メイン2・3層目、色調補正と各種マスクの近似描画に対応します。設定ごとの対応範囲はガイドを参照してください。NPC用MODに同梱するアバターはタートブのみです。

NPCの防具・頭装備・エリトラのモデルは非表示にし、装備の性能は保持します。半透明とカットアウト、色のalpha、AlphaMaskの置換・乗算・加算・減算を近似描画します。

詳しい導入手順は [導入ガイド](release/README_ja.md)、設定と対応範囲は [設定ガイド](release/Guide_ja.md) を参照してください。

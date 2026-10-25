# B.E.P 3DAvater

Minecraft Java **26.3** / NeoForge **26.3.0.95** / Java **25** 向けの3つのMODと、Unity **2022.3** 向け変換ツールです。

| MOD | MOD ID・エントリーポイントクラス | 機能・依存 |
| --- | --- | --- |
| B.E.P 3DAvater Loader | `bep_3davater_loader` | モデル読込・共通描画・表情キーAPI。tortobu同梱。単体利用可能 |
| B.E.P 3DAvater Player Model | `bep_3davater_player` | プレイヤーの三人称・一人称を差し替え。Loaderが必要 |
| B.E.P 3DAvater Companion | `bep_3davater_companion` | AIアバターNPC。Loaderが必要。Player Modelなしで利用可能 |

プレイヤー表示だけならLoader＋Player Model、AIの仲間だけならLoader＋Companion、両方なら3つを導入します。Loader単体でモデルを選んでもプレイヤーの外見は変わりません。26.3-0.10.0は旧MOD ID・旧APIとの互換性を前提にしません。

## 使い方

1. 配布版の `.unitypackage` をUnityの `Assets > Import Package > Custom Package...` から導入します。開発時はPackage Managerの `Add package from disk...` で `unity/Packages/dev.avatarcraft.exporter/package.json` を追加する方法も使えます。二重導入は避けてください。
2. Humanoid Animatorのあるアバターのルートを選び、`Tools > B.E.P 3DAvater Exporter > Avatar export and motions` を開きます。34動作それぞれのモーション、再生速度・切替時間を選び、設定を保存して `.avc` を出力します。任意のAnimationClipによる差し替えと、編集用39本の `.anim` の生成にも対応します。Modular AvatarはNDMF経由で複製上に適用します。詳しくは [設定ガイド](release/index.html#export)。
3. `.avc` をMinecraftの `config/bep_3davater_loader/avatars/` に配置し、MODのJARを `mods/` に配置します。
4. ワールド内でJISキーボードの **@** を押すと、プレビュー画像付き一覧を開きます。キーはMinecraftの操作設定から変更できます。英語配列では `[` の物理位置です。
5. 上部のタブで設定するMODを選びます。Loaderではモデル一覧、頭部をまとめたスクロール＋検索式の表情編集、表情キーJSONの直接編集を行えます。モデルを選ぶだけでは着用しません。
6. Player Modelではモデルを選び「選択したモデルを着る」で着用します。Companionでは自分の召喚済みNPCをクリックし、モデル・性格を選んで保存します。導入したMODだけがタブに追加されます。

Unityツールは **B.E.P 3DAvater Exporter** です。ウィンドウ上部の **Language / 言語** から日本語・Englishを切り替えられます。[英語のエクスポート手順](release/index.html?lang=en#install)も同梱しています。

着用は `config/bep_3davater_loader/selection.json`、表情は `config/bep_3davater_loader/expressions/<avatar-id>.json` に保存します。アバターを再変換しても同じIDなら表情設定は維持されます。ファイル更新後は一覧の「再読込」で読み直し、Player Modelで再度着用してください。

初期設定は2〜4秒間隔のまばたきです。対応する形状があれば `blink` を閉眼に、`vrc.v_aa` を食事の口に自動割当します。被ダメージの初期表情は閉眼です。

## 表情キー

Loaderは文字列キー `idle`・`blink`・`eat`・`hurt` と任意の追加キーで表情を再生します。Minecraftの操作設定には通常・まばたき・食事・被ダメージの手動キーを用意し、初期状態は未割当です。同じキーの再入力または通常キーで解除できます。自動まばたきは共通再生機能です。

Player ModelとCompanionがそれぞれ食事・飲み物・被ダメージを判定し、Loaderへ `eat`・`hurt` を渡します。Loader自身はゲームイベントから表情を選びません。両方のイベントが重なる場合は被ダメージを優先します。表情キーの設定は [表情設定ガイド](release/player.html#expressions) を参照してください。

## マルチプレイの非公開アバター

選択中のモデルと表情設定を暗号化してサーバーへ送信し、周囲のプレイヤーは表示専用キャッシュとして受信します。装着できるのはアップロードした本人だけで、サーバーはMinecraftの認証済みUUIDを検証します。受信側のアバター一覧やNPC選択には追加しません。復元・検証が済むまで通常スキンを表示します。

専用サーバーは `online-mode=true` が必要です。詳細、キャッシュと利用規約上の確認点は [マルチプレイガイド](release/player.html#multiplayer) を参照してください。

## AIモデル設定

Companionを導入すると、@キーのシリーズ設定に「AIモデル」タブが追加されます。Codex OAuth、OpenAI API、Anthropic API、LM Studio、Ollama、OpenAI互換APIを選び、接続先URL・伏せ字のAPIキー・通常モデル・複雑な計画用モデルを設定できます。接続先からモデル一覧を取得して検索・選択でき、モデルIDの直接入力にも対応します。

「設定を保存」でホストPCの `config/bep_3davater_companion-ai.toml` へ保存し、ワールドを開き直すと反映します。専用サーバーへ接続中にこのPCの設定を編集しても、専用サーバー側のAI設定は変わりません。詳しくは [AIモデル設定ガイド](release/npc.html#ai)。

## AIアバターNPC

Companionは、クラフトした召喚石からアバターを召喚し、Codex CLIのChatGPT OAuth認証、OpenAI互換API（LM Studio・Ollamaを含む）、Anthropic互換APIで動かします。右クリックで上下に並べたNPC・プレイヤーの持ち物、防具・主手・副手、性格JSON、アバター、作業指示、記録を操作できます。近くのNPCとの会話、作業中の定期発言、クラフトできる記録帳を備えます。Loaderに同梱した標準モデル「tortobu」（作者：aoi_nasuko）を共通のソウルストーンから召喚します。Companionには一人称「ぼく」でおっとりした性格のサンプルを同梱します。モデルと性格はGUIから変更でき、追加モデル・性格は外部ファイルとして読み込みます。近くの所有者のチャットを会話や作業指示として受け取れます。

召喚上限はサーバー設定で1人3体（-1で無制限）。倒れたNPCは所持品を落とし、所有者のリスポーン地点で待機します。所持品を保持する設定もあります。「呼び戻しの地図」から自分のNPCを別次元から呼び戻せます。通常会話は同一次元・半径64以内、重要通知は所有者へ距離を問わず届きます。レシピと設定は [NPCガイド](release/npc.html) を参照してください。

サーバー側でCodex CLIへログインし、NPCを使うクライアントとサーバーへLoaderとCompanionのJARを導入します。導入・レシピ・認証・保存先・APIは [NPCガイド](release/npc.html) を参照してください。

新しい日英の指示は現在のAIが計画へ変換し、通常の行動は同梱の約20MiB・5専門家の学習済みモデルで選択します。日英21.6万組の命令・状況データに、曖昧な参照、不足分、収納先、除外指定、確認が必要な例を含めています。仲間への担当割当、空の持ち物からの道具準備と実精錬、階段状の地下採掘、最大8件の場所記憶と回収・帰還に対応します。アバターアンカーに付けた場所IDはすべてのNPCで共有し、名前での移動と周囲のチェストへの収納・取り出しに使用できます。NPC周囲3×3チャンクを常時読み込みし、複雑な依頼は設定した上位モデルで段階的に計画します。木製ドアの開閉、樹種をまたぐ原木の採集と収納、成熟作物の収穫にも対応します。発言は別に30〜60秒ごと（既定45秒）に生成します。構成・対応範囲・再学習は [ローカル行動モデル](Docs/NpcPolicyV4.md)、引数・条件は [全63種のツール一覧](Docs/NpcTools.md) を参照してください。新規ワールドからドラゴン討伐までの完全自律完走は未検証です。

## 開発

```powershell
.\tools\dev.ps1 -Task build
.\tools\dev.ps1 -Task test
.\tools\dev.ps1 -Task runClient
.\tools\dev.ps1 -Task runNpcGameTest
```

Java 25が必要です。通常はWindowsで `gradlew.bat build`、macOS/Linuxで `./gradlew build` を実行できます。`dev.ps1` は一時ディレクトリを短いパスへ設定するWindows用の補助スクリプトです。

ビルド成果物は `build/libs/bep_3davater_loader-26.3-0.10.0.jar`、`bep_3davater_player-26.3-0.10.0.jar`、`bep_3davater_companion-26.3-0.10.0.jar` です。開発クライアント用の `.avc` は `run/config/bep_3davater_loader/avatars/` に配置します。Unity MCPで再出力したtortobuだけをLoader用JARに含め、性格サンプルはCompanion用JARと配布物の `Examples/Personalities/tortobu.json` に含めます。その他のアバターは外部ファイルです。

テストは外部アバターなしで実行できます。変換済みアバターを使う7件の統合テストは、`AVATARCRAFT_TEST_AVATAR` 環境変数で検証用 `.avc` を指定した場合に実行します。指定しない場合はスキップします。検証データの条件とUnity用の開発ツールは [tools/README.md](tools/README.md) を参照してください。

配布物はビルド後に `python tools/package_release.py` で `PublishPackage/` へ生成できます。同じ版が存在する場合は上書きしません。

## 現在の対応範囲

- ローカル／他プレイヤーの三人称モデルとGUI上の3Dプレビュー。人型向けの34動作・39種類のモーション、上半身動作と被ダメージの重ね合わせ、左右の手への対応、頭の視線、手持ちアイテムのボーンへの追従。
- 一人称の腕・手・指・袖をアバターへ置換。空手、攻撃、持ち替え、左右の地図表示に対応。既存 `.avc` の再変換は不要です。
- 複数フレームのBlendShape、イベント別表情、独立したまばたき。
- PhysBoneのAngle / Hinge / Polar制限、制限軸、伸縮上限、各カーブ、有効状態、複数子・入れ子の設定。古い `.avc` は制限が含まれないため再変換してください。
- lilToon設定の読取と、基本色・UV・透過・影境界・リム・発光・アウトラインの近似描画。

VRChatの非公開PhysBoneソルバーとの完全一致、Animator FX／Expression Menuの実行、Minecraft防具・エリトラの外観は未対応です。lilToonの法線2層、MatCap2層、メイン2・3層目、色調補正と各種マスクの近似描画に対応します。設定ごとの対応範囲はガイドを参照してください。Loaderに同梱するアバターはtortobuのみです。

NPCの防具・頭装備・エリトラのモデルは非表示にし、装備の性能は保持します。半透明とカットアウト、色のalpha、AlphaMaskの置換・乗算・加算・減算を近似描画します。

詳しい導入手順は [導入ガイド](release/index.html)、設定と対応範囲は [設定ガイド](release/index.html#export) を参照してください。

MODのバージョンは `<Minecraftバージョン>-<MODバージョン>`（例：`26.3-0.10.0`）です。3つのJARと配布ZIPに同じ表記を使い、Unity Exporterのパッケージバージョンは `0.10.0` のままです。

配布ガイドは [release/index.html](release/index.html) をブラウザーで開いてください。日本語・English切り替え、Playerモデル設定、NPCMODの使い方をオフラインで閲覧できます。`python tools/generate_release_docs.py` で再生成、`python tools/validate_release_docs.py` でリンク・言語ブロックを検証します。

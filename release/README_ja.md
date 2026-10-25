# B.E.P 3DAvater 26.3-0.10.0

VRChat向けの人型アバターを読み込み、プレイヤー表示とAIの仲間を個別に導入できるMODです。モデルをUnity MCPで再出力したtortobuはLoader、性格「ぼく・おっとり」はCompanionに同梱します。

## 内容

- `Minecraft/mods/bep_3davater_loader-26.3-0.10.0.jar`：共通のLoader。tortobuを同梱。
- `Minecraft/mods/bep_3davater_player-26.3-0.10.0.jar`：プレイヤー差し替え。Loaderが必要。
- `Minecraft/mods/bep_3davater_companion-26.3-0.10.0.jar`：AIの仲間。Loaderが必要。
- `Unity/dev.avatarcraft.exporter-0.10.0.tgz`：Unityのアバター変換ツール。標準モーションを同梱。
- `Docs/Export_en.md`：B.E.P 3DAvater Exporterの英語手順。
- `Docs/Guide_ja.md`：モーション・表情・物理設定と制限事項。
- `Docs/Expression_API_ja.md`：表情キーの呼び出しと任意キーの追加。
- `Examples/Personalities/tortobu.json`：一人称「ぼく」でおっとりした性格の編集用サンプル。
- `Docs/Multiplayer_ja.md`：本人専用アバターのオンライン同期・制限・キャッシュ。
- `Docs/LilToon_ja.md`：素材・各種マスクの対応範囲と再変換手順。
- `Docs/AI_Settings_ja.md`：AI接続方式・APIキー・モデル選択。
- `Docs/NPC_Guide_ja.md`：NPCの導入、Codex OAuth認証、クラフトと操作方法。
- `Docs/NPC_LocalAI_ja.md`：ローカル行動モデル、協力作業、学習手順と制限事項。
- `Docs/NPC_Tools_ja.md`：既存・追加を含む全63種の行動ツールと引数。
- `CHANGELOG_ja.md`：この版の主な機能。
- `LICENSE.txt`：ライセンス表記。
- `Tortobu_Notice_ja.md`：同梱モデル「tortobu」の作者・権利表記。
- `manifest.json`、`SHA256SUMS.txt`：内容一覧とファイルの整合性確認用データ。

Loader用JARに標準モデル「tortobu」（作者：aoi_nasuko）の変換済みデータとテクスチャを同梱します。性格「ぼく・おっとり」はCompanion用JARと `Examples/Personalities/tortobu.json` に含みます。手元のアバターを変換する場合は、対応するUnityとVRChat SDK、Modular Avatar、lilToon等をアバタープロジェクトに用意してください。

## 動作環境

| 対象 | 確認したバージョン |
| --- | --- |
| Minecraft Java Edition | 26.3 |
| NeoForge | 26.3.0.95 |
| Minecraft実行用Java | 25 |
| Unity | 2022.3.22f1（変換ツールの対象は2022.3） |
| VRChat SDK | 3.10.4（PhysBone読取の確認環境） |

変換ツールはUnityの `com.unity.nuget.newtonsoft-json` 3.2.1を依存として指定しています。未導入の場合はPackage Managerの解決にインターネット接続が必要です。VRChat固有の設定を読み取るには、元のアバタープロジェクトに対応するSDK等を導入してください。

## 1. MinecraftへMODを導入

| MOD | MOD ID・エントリーポイントクラス | 機能・依存 |
| --- | --- | --- |
| B.E.P 3DAvater Loader | `bep_3davater_loader` | モデル読込・共通描画・表情キーAPI。tortobu同梱。単体利用可能 |
| B.E.P 3DAvater Player Model | `bep_3davater_player` | プレイヤーの三人称・一人称を差し替え。Loaderが必要 |
| B.E.P 3DAvater Companion | `bep_3davater_companion` | AIアバターNPC。Loaderが必要。Player Modelなしで利用可能 |

プレイヤー表示だけならLoader＋Player Model、AIの仲間だけならLoader＋Companion、両方なら3つを導入します。Loader単体でモデルを選んでもプレイヤーの外見は変わりません。26.3-0.10.0は旧MOD ID・旧APIとの互換性を前提にしません。

1. Minecraft 26.3用のNeoForge環境を用意します。
2. `Minecraft/mods/bep_3davater_loader-26.3-0.10.0.jar` を使用するゲームディレクトリの `mods/` に配置します。
3. 必要に応じて `bep_3davater_player-26.3-0.10.0.jar` と `bep_3davater_companion-26.3-0.10.0.jar` を追加します。マルチプレイでは使用するMODをクライアントとサーバーへ導入します。
4. Minecraftを起動します。Loaderがtortobuを自動で配置するため、標準モデルの利用にUnityでの変換は不要です。Companionの認証と召喚は `Docs/NPC_Guide_ja.md` を参照してください。

## 2. Unityでアバターを変換

1. この配布ZIPを展開し、アバターのUnityプロジェクトを開きます。プロジェクト内のアバターのルートにはHumanoidとして有効なAnimatorが必要です。
2. `Window > Package Manager` の `+` から `Add package from tarball...` を選び、`Unity/dev.avatarcraft.exporter-0.10.0.tgz` を指定します。
3. ツール上部のLanguage / 言語で日本語・Englishを切り替えられます。英語手順は `Docs/Export_en.md` を参照してください。アバターのルートを選択し、`Tools > B.E.P 3DAvater Exporter > Avatar export and motions` を開きます。
4. 動作ごとのモーションを確認・選択し、「設定を保存」からプロファイルを保存します。独自のAnimationClipへの差し替えもできます。
5. 「この設定で .avc を変換」でファイルを保存します。Modular Avatarを使用する場合は、元プロジェクトにNDMFを導入して複製上の適用を有効にしてください。

同じ変換ツールをローカルパッケージとして導入済みの場合は、その登録をPackage Managerから外してから配布版を導入してください。プロジェクト内のアバターやモーションプロファイルを削除する必要はありません。

## 3. ゲーム内でアバターを選択

1. 変換した `.avc` を、ゲームディレクトリの `config/bep_3davater_loader/avatars/` に配置します。フォルダがなければ作成します。
2. ワールド内でJISキーボードの **@** を押します。英語配列では **[** の位置です。操作設定からキーを変更できます。
3. Loaderタブでは編集するモデルを選びます。モデル一覧の選択だけでは着用しません。ファイルを更新した場合は「再読込」してください。
4. Player Modelタブでモデルを選び「選択したモデルを着る」を押すと、プレイヤーへ反映します。Companionタブでは自分の召喚済みNPCをクリックし、モデル・性格を変更して保存します。
5. Loaderの「表情・シェイプキー」は頭部をまとめたスクロール＋検索式です。「表情キーJSON編集」では任意キーを含むJSONを直接編集し、検証して保存できます。まばたきは初期値で2〜4秒ごとです。

一人称の腕は同じ `.avc` から抽出するため、腕のための追加変換は不要です。通常のブロックや食べ物の所持・使用では、Minecraft標準と同じアイテム表示になります。

## AI接続の設定

Companionを導入すると、@キーの設定に「AIモデル」タブが増えます。接続方式・URL・APIキー・モデルを設定し、保存後にワールドを開き直してください。APIキーは伏せ字表示で、このPCの設定ファイルに保存します。モデル一覧の検索・選択とモデルIDの直接入力に対応します。専用サーバーのAIはサーバーホスト側の設定を使います。手順は `Docs/AI_Settings_ja.md`。

## 保存先と更新

- アバターファイル：`config/bep_3davater_loader/avatars/*.avc`
- 選択：`config/bep_3davater_loader/selection.json`
- 表情の上書き設定：`config/bep_3davater_loader/expressions/<avatar-id>.json`
- Unityのモーション割当：保存した `AvatarCraftMotionProfile` アセット

更新時はMODのJARを入れ替え、Unity側も新しいパッケージへ更新します。設定ファイルとアバターを保持してください。PhysBone制限を含まない古い `.avc` は、新しい変換ツールで再変換が必要です。

## 困ったとき

- 一覧に出ない：ゲームディレクトリとファイルの拡張子を確認し、「再読込」してください。複数のランチャープロファイルを使う場合は配置先に注意してください。
- キーが反応しない：Minecraftの操作設定でB.E.P 3DAvater Loaderのキー割当・競合を確認してください。
- 変換メニューが出ない：Package Managerの導入完了とUnity Consoleのコンパイルエラーを確認してください。
- 衣装や揺れが違う：元のUnityプロジェクトに必要なSDK・NDMF等があるか確認して再変換してください。再現範囲は `Docs/Guide_ja.md` を参照してください。
- 描画・読込エラー：ゲームディレクトリの `logs/latest.log` のAvatarCraftに関する行を確認してください。

この版は開発初期版です。確認した機能と未対応の範囲は `Docs/Guide_ja.md` に記載しています。

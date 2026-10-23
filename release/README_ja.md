# AvatarCraft 0.5.0

VRChat向けの人型アバターを専用ファイルへ変換し、MinecraftのローカルプレイヤーやNPCとして表示するMODです。プレビュー付きアバター選択、ゲーム内の表情設定、人型モーション、PhysBoneの角度・伸縮制限、一人称の手と袖に対応します。

## 内容

- `Minecraft/mods/avatarcraft-0.5.0.jar`：共通のFramework MOD。
- `Minecraft/mods/avatarcraft-npc-0.5.0.jar`：AIアバターNPC用の追加MOD。
- `Unity/dev.avatarcraft.exporter-0.5.0.tgz`：Unityのアバター変換ツール。標準モーションを同梱。
- `Docs/Guide_ja.md`：モーション・表情・物理設定と制限事項。
- `Docs/LilToon_ja.md`：素材・各種マスクの対応範囲と再変換手順。
- `Docs/NPC_Guide_ja.md`：NPCの導入、Codex OAuth認証、クラフトと操作方法。
- `CHANGELOG_ja.md`：この版の主な機能。
- `LICENSE.txt`：ライセンス表記。
- `Tortobu_Notice_ja.md`：同梱モデル「タートブ」の作者・権利表記。
- `manifest.json`、`SHA256SUMS.txt`：内容一覧とファイルの整合性確認用データ。

NPC用JARに標準モデル「タートブ」（作者：aoi_nasuko）の変換済みデータとテクスチャを同梱します。VRChat SDK、Modular Avatar、lilToon、Minecraft、NeoForge、Unity本体は別途必要です。

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

1. Minecraft 26.3用のNeoForge環境を用意します。
2. Minecraftを終了し、`Minecraft/mods/avatarcraft-0.5.0.jar` を、使用するゲームディレクトリの `mods/` にコピーします。同じMODの旧JARがある場合は入れ替えてください。
3. NPCも利用する場合は追加の `avatarcraft-npc-0.5.0.jar` をクライアントとサーバーの両方へ導入します。プレイヤーの表示だけを使う場合はFrameworkだけで利用できます。
4. そのNeoForge環境でMinecraftを起動します。NPC用MODはタートブを自動で配置するため、標準モデルだけを使う場合はUnityでの変換は不要です。NPCの認証と召喚は `Docs/NPC_Guide_ja.md` を参照してください。

## 2. Unityでアバターを変換

1. この配布ZIPを展開し、アバターのUnityプロジェクトを開きます。プロジェクト内のアバターのルートにはHumanoidとして有効なAnimatorが必要です。
2. `Window > Package Manager` の `+` から `Add package from tarball...` を選び、`Unity/dev.avatarcraft.exporter-0.5.0.tgz` を指定します。
3. アバターのルートを選択し、`Tools > AvatarCraft > Avatar export and motions` を開きます。
4. 動作ごとのモーションを確認・選択し、「設定を保存」からプロファイルを保存します。独自のAnimationClipへの差し替えもできます。
5. 「この設定で .avc を変換」でファイルを保存します。Modular Avatarを使用する場合は、元プロジェクトにNDMFを導入して複製上の適用を有効にしてください。

同じ変換ツールをローカルパッケージとして導入済みの場合は、その登録をPackage Managerから外してから配布版を導入してください。プロジェクト内のアバターやモーションプロファイルを削除する必要はありません。

## 3. ゲーム内でアバターを選択

1. 変換した `.avc` を、ゲームディレクトリの `config/avatarcraft/avatars/` に配置します。フォルダがなければ作成します。
2. ワールド内でJISキーボードの **@** を押します。英語配列では **[** の位置です。操作設定からキーを変更できます。
3. 画像付き一覧でアバターを選択します。ファイルを更新した場合は「再読込」して選択し直します。
4. 「表情設定」で、通常・まばたき・食事・被ダメージのシェイプキーと重みを調整して保存します。まばたきは初期値で2〜4秒ごとです。

一人称の腕は同じ `.avc` から抽出するため、腕のための追加変換は不要です。通常のブロックや食べ物の所持・使用では、Minecraft標準と同じアイテム表示になります。

## 保存先と更新

- アバターファイル：`config/avatarcraft/avatars/*.avc`
- 選択：`config/avatarcraft/selection.json`
- 表情の上書き設定：`config/avatarcraft/expressions/<avatar-id>.json`
- Unityのモーション割当：保存した `AvatarCraftMotionProfile` アセット

更新時はMODのJARを入れ替え、Unity側も新しいパッケージへ更新します。設定ファイルとアバターを保持してください。PhysBone制限を含まない古い `.avc` は、新しい変換ツールで再変換が必要です。

## 困ったとき

- 一覧に出ない：ゲームディレクトリとファイルの拡張子を確認し、「再読込」してください。複数のランチャープロファイルを使う場合は配置先に注意してください。
- キーが反応しない：Minecraftの操作設定でAvatarCraftのキー割当・競合を確認してください。
- 変換メニューが出ない：Package Managerの導入完了とUnity Consoleのコンパイルエラーを確認してください。
- 衣装や揺れが違う：元のUnityプロジェクトに必要なSDK・NDMF等があるか確認して再変換してください。再現範囲は `Docs/Guide_ja.md` を参照してください。
- 描画・読込エラー：ゲームディレクトリの `logs/latest.log` のAvatarCraftに関する行を確認してください。

この版は開発初期版です。確認した機能と未対応の範囲は `Docs/Guide_ja.md` に記載しています。

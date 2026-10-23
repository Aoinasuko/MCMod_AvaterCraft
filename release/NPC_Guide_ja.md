# AIアバターNPCの導入と開発

Minecraft 26.3、NeoForge 26.3.0.95、Java 25向けです。NPCのゲーム処理はサーバーで動作します。

## 導入

- NPCを使うクライアントとサーバーの両方へ `avatarcraft-0.5.1.jar` と `avatarcraft-npc-0.5.1.jar` を配置します。プレイヤーのアバター表示だけを使う場合はFrameworkのJARだけで利用できます。
- 標準アバター「タートブ」（作者：aoi_nasuko）と性格プリセットをNPC用JARに同梱します。クライアント起動時に `config/avatarcraft/avatars/tortobu.avc` がない場合だけ展開します。同名の既存ファイルは上書きしません。
- 追加モデルは、表示する各クライアントの `config/avatarcraft/avatars/` に使用権のある `.avc` を配置します。追加モデルのネットワーク自動配布は行いません。
- 手元の追加モデルは外部 `.avc` として追加し、性格JSONの `avatar` にアバターIDまたは拡張子を除いたファイル名を指定します。IDの一致を優先します。

## AIの接続設定

Mods → AvatarCraft AI Companions → 設定 → Local の `ai` を開きます。設定ファイルは `config/avatarcraftnpc-ai.toml` です。変更後はワールド／サーバーを開き直してください。マルチプレイではサーバーを実行する環境の設定を使います。クライアント側のキーはサーバーへ送信しません。旧 `config/avatarcraftnpc/codex.json` は使用しません。

| 接続方式 | provider | baseUrl | model | apiKeyEnvironment |
| --- | --- | --- | --- | --- |
| Codex CLI OAuth | CODEX | 未使用 | 空文字でCLIの既定値 | 未使用 |
| OpenAI API | OPENAI | `https://api.openai.com/v1` | 利用可能なChat Completions対応モデルID | `OPENAI_API_KEY` |
| LM Studio | OPENAI | `http://localhost:1234/v1` | ロードしたモデルID | 認証不要なら空文字 |
| Ollama | OPENAI | `http://localhost:11434/v1` | インストールしたモデルID | 認証不要なら空文字 |
| Anthropic互換 | ANTHROPIC | `https://api.anthropic.com/v1` または互換サーバーのベースURL | 利用可能なMessages対応モデルID | `ANTHROPIC_API_KEY` |

LM Studio・Ollamaでは関数ツール呼び出しとJSON出力に対応するモデルを使用してください。モデルを自動でダウンロード・起動する機能はありません。`baseUrl` には `/chat/completions` や `/messages` を付けず、`/v1` までを指定します。接続先により対応API・ツール呼び出し精度は異なります。

APIキーは `apiKeyEnvironment` で指定した環境変数を優先します。空または未設定なら `apiKey` の値を使います。ローカルサーバーでキーを使わない場合は両方を空文字にします。`apiKey` に直接入力した値はローカルTOMLに平文で保存されるため、キー入りの設定ファイルを配布しないでください。設定ファイル、キー、認証情報をNPCの性格JSONや記録には保存しません。

```toml
[ai]
enabled = true
provider = "OPENAI"
model = "your-tool-capable-model-id"
baseUrl = "http://localhost:1234/v1"
apiKeyEnvironment = ""
apiKey = ""
requestTimeoutSeconds = 30
turnTimeoutSeconds = 180
maxToolCalls = 32
maxConcurrentTurns = 3
minimumRequestIntervalSeconds = 3
maxOutputTokens = 4096
```

HTTP接続はサーバースレッドの外で実行し、総ターン時間・応答サイズ・コンテキスト・ツール回数を制限します。発言用の同時実行枠を1つ確保します。停止や指示変更で古い処理を破棄し、HTTPエラー本文やAPIキーをゲーム内の記録へ出しません。プロバイダーのHTTPエラーに対して自動再送して重複操作する処理はありません。

### Codex OAuthを使う場合

この接続だけはAPIキー不要です。ホスト環境でCodex CLIを用意し、ユーザー自身が `codex login` でChatGPTにログインします。`codex login status` で確認し、`provider = "CODEX"` にします。PATHにCLIがない場合は `codexExecutable` にパスを設定します。`model` が空文字ならCodexの既定値を使います。

[Codex App Server](https://learn.chatgpt.com/docs/app-server)の動的ツールは実験的APIであり、検証したCLIは0.163.0です。更新時は互換性を確認してください。認証は[公式認証ガイド](https://learn.chatgpt.com/docs/auth)を参照してください。

### HTTP方式の参照仕様

- [OpenAI Function calling](https://developers.openai.com/api/docs/guides/function-calling)：Chat Completionsの `tool_calls` と `tool_call_id`。
- [Anthropic Tool calls](https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls)：Messagesの `tool_use` と `tool_result`。
- [LM Studio Tool Use](https://lmstudio.ai/docs/developer/openai-compat/tools)。
- [Ollama OpenAI compatibility](https://docs.ollama.com/api/openai-compatibility)。

## 召喚石と記録帳

作業台で次の配置を使います。

```text
I G I
G G G
I G I
```

Iは鉄インゴット（計4個）、Gは金インゴット（計5個）です。ソウルストーンを1個作成します。共通アイテム「ソウルストーン」を持って地面へ使用すると1つ消費し、標準のタートブを召喚します。モデルと性格は召喚後のGUIで変更できます。衝突する場所へは召喚しません。所有者は召喚したプレイヤーです。

本とアメジストの欠片を不定形クラフトすると「アバターの記録帳」になります。持って使用し、記録のあるNPCを切り替えて閲覧できます。所有者の記録だけを表示します。NPCが死亡・未ロードの場合も保存済みの記録は閲覧できます。

## 右クリック画面

右端のアイコンだけのボタンで4つのメニューを切り替えます。ホバーすると黄色の名前と灰色の説明が表示されます。小さいウィンドウでは全体を縮小し、スロットとクリック位置も同じ倍率に合わせます。

- 持ち物：上部にアバター・バニラの体力／空腹アイコン・防具4枠・主手／副手・NPCのバッグ36枠、下部にプレイヤーの持ち物とツールバーを同時表示します。通常クリック・Shiftクリックで受け渡せます。主手と副手へ直接アイテムを置いて装備できます。所有者以外は開けず、NPCから離れると閉じます。
- 性格・外見：JSONを編集して保存します。矢印でローカルのアバターを選ぶとJSONへ反映します。性格ボタンでプリセットを切り替えられ、選択中のモデルを保持します。変更後に「保存・実行」を押します。「一覧再読込」は更新した `.avc` を読み直します。
- 作業指示：日本語で具体的な作業を入力して「保存・実行」を押します。「作業停止」で作業の移動・操作・推論を止めます。待機中の自動追従は「追従: ON/OFF」で切り替えます。指示の変更でも古い推論結果を破棄します。
- 記録：会話、指示、ツール実行結果を新しい順に表示します。「過去の記録」で以前のページ、矢印でページ内を送れます。

性格には `id`、`name`、`avatar`、`instructions`、`speakingStyle`、`goal`、`allowedSkills`、`workSpeechSeconds`、`nearbyConversationSeconds` を設定します。作業中の発言は60秒、周辺会話は15〜3600秒です。JSONの許可スキルに未知の名前は指定できません。

追加の性格プリセットは、ホスト環境の `config/avatarcraftnpc/personalities/<id>.json` に配置します。GUIを開き直すと既定のタートブと有効な外部プリセットを最大12件読み込みます。ファイル名とJSONの `id` を一致させ、1ファイル16KiB以下にしてください。現在の性格JSONをコピーして `id`・`name`・`avatar`・話し方などを変更できます。追加モデルは各クライアントに別途必要です。

## 近くのプレイヤーとの会話

同じ次元で12ブロック以内、視線の通るNPCは通常チャットに応答します。名前を含めると該当NPCへ、指定がなければ最も近い所有NPCを優先して届けます。「二人とも」「みんな」「全員」などの呼びかけや複数の名前では最大4体へ届けます。

所有者の具体的な依頼をAIが作業指示と判断すると、GUIを開かずに作業を開始します。「作業を止めて」といった停止指示にも対応します。挨拶や質問には会話で応じ、所有者以外の発言から作業指示を変更しません。応答までに離れた場合や所有者・指示が変わった場合は古い結果を適用しません。AIの応答時間や指示の解釈により開始まで時間がかかる場合があります。

## 行動と保存

ゲーム操作は `observe`、`move_to`、`follow`、`equip`、`find_blocks`、`dig`、`place`、`attack`、`deposit`、`withdraw`、`give_item`、`find_chests`、`craft`、`eat`、`say`、`wait`、`stop` です。Codexへ公開するツール名には `npc_` を付け、Codexの組み込みツールとの衝突を避けています。周囲と持ち物を観察してから実行し、失敗結果も次の推論へ返します。

例：「周囲を確認して、近くの原木を1つ掘り、板材を作ってください」「近くの敵を攻撃してください」「私についてきてください」。道具・材料・収納先の座標等が必要な作業では、持ち物を渡して具体的に指示してください。ブロック検索は読み込まれた範囲で半径16、移動先は64ブロック以内です。収納・設置等には距離と視線の確認があります。クラフトは登録済みの作業台レシピを使い、大きなレシピには近くの作業台が必要です。アイテムを生成して材料を補いません。

`equip(slot, hand)` はバッグ0〜35と `main`／`off` の手を丸ごと交換します。空のバッグ枠を指定すると手に持つアイテムを戻せます。食事中は持ち替えを拒否します。観察結果はバッグ・防具・主手41・副手40を区別します。

`find_chests(radius)` で半径1〜16のチェスト位置と中身のスロット番号を調べ、4ブロック以内に近づいて `deposit(x,y,z,slot,count)` でバッグから収納、`withdraw(x,y,z,slot,count)` でチェストから取り出します。`give_item(entity_id,slot,count)` は4ブロック以内で見える別NPC・プレイヤーへバッグのアイテムを渡します。1回1〜64個で、受け入れられた個数だけを元の持ち物から減らし、余りは元の場所に残します。ロック・保護・塞がれたチェストは操作しません。ダブルチェストでは両側のロックを確認します。

待機中の自動追従は6ブロックを超えると開始、2.5ブロック以内で停止します。8ブロックを超えるとダッシュし、4.5ブロック以内で歩行へ戻します。見通しが一時的に遮られても経路探索を続けます。作業中は指定した行動を優先します。テレポートや次元間移動は行いません。

開発中の持ち物形式を変更しているため旧NPCの所持品との互換性はありません。新しく召喚したNPCで利用してください。性格の `allowedSkills` を個別に制限する場合は、必要な新スキルも追加してください。

体力20、攻撃・移動等はPlayerの属性を基準にし、装備と空腹・満腹度・消耗・自然回復・飢餓を扱います。戦闘では通常の装備属性を使い、敵対的なMobだけを攻撃対象にします。採掘は道具の速度・耐久・通常のドロップを使います。所有者が切断すると作業を止めます。

近くのNPC同士は視線と距離を確認して会話します。作業中は60秒ごとに進捗の発言生成を開始します。発言はCodexの応答時間や利用制限の影響を受けます。ワールドが一時停止している間はゲーム時間も進みません。

所持品・装備・性格・所有者・アバター・指示・空腹状態はエンティティとともに保存します。会話と行動ログは各ワールドの `avatarcraftnpc/journal/<owner-uuid>/<npc-uuid>/` にJSONLで保存します。8MiB単位に分割し、過去ログを自動削除しません。バックアップにはこのフォルダも含めてください。

## Framework API

共通MODの `dev.avatarcraft.api` は、クライアントに依存しない `AvatarActor`、`AvatarCatalog`、`AvatarAnimation` を提供します。Mobへ `AvatarActor` を実装し、CLIENT限定の登録処理で `AvatarActorRenderer` を登録すると、独立した表情・モーション・物理状態で描画できます。`avatarMining()` は採掘モーションの指定用です。クライアントの描画APIを専用サーバーの初期化コードから呼び出さないでください。

NPCの防具・頭装備・エリトラのモデルは描画しません。装備スロット、性能、耐久は通常どおり扱い、アバター本来の衣装を表示します。

lilToonの不透明・カットアウト・半透明モード、色のalpha、AlphaMaskの置換・乗算・加算・減算、UV、深度書込を描画に反映します。半透明は距離順に並べますが、重なり合う面や複数素材の順序はUnityと完全には一致しません。法線2層・MatCap2層・メイン2／3層目・色調補正・影／リム／発光／輪郭／ディゾルブのマスクにも対応します。新しい素材情報を使うには更新したUnityツールで再変換してください。詳しくは `LilToon_ja.md` を参照してください。VRChatのAnimator FX、Expression Menuや実行時スクリプトは動かしません。詳細はアバター設定ガイドを参照してください。

## 自動検証

`tools/dev.ps1 -Task build` はユニットテストを含みます。`-Task runNpcGameTest` は独立した検証ワールドで、召喚・上下インベントリ配置・防具・保存・採掘・設置・クラフト・収納・取り出し・NPC／プレイヤーへの受け渡し・満杯時の保存・食事・ダッシュ追従・記録を検証します。OAuthの実接続テストは `AVATARCRAFT_CODEX_LIVE=1` を明示して `-Task test` を実行した場合だけ有効になります。実接続テストはCodexの利用枠を使います。

HTTP方式はローカルの模擬APIで両プロトコル・ツール往復・停止・許可検証・重複呼び出し抑止・HTTPエラー処理を検証します。実サービスのキーやユーザー環境のモデルを使った接続結果は含みません。

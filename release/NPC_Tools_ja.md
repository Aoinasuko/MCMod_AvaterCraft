# NPCツール一覧

2026-10-24 開発版。Javaの`NpcTools`から抽出した契約に基づく全51種。従来19種（基本17種＋場所記憶2種）と今回追加32種を併記します。

通常の指示理解・発言のAIはツールを直接実行しません。計画を検証したサーバー側ローカル実行系が、許可された操作だけを呼び出します。API上の名前には`npc_`が付き、TaskPlanのatomic指定は`avatarcraftnpc:名前`です。

表の引数はすべて必須、未知の引数は拒否します。`integer`は整数、`number`は有限の数値、`string`は文字列です。通常の文字列は最大512文字。座標は現在の次元の実ワールド座標です。バッグ枠は0〜35、観察上の副手は40、主手は41です。コンテナ枠はそれぞれの観察結果から取得してください。

| 区分 | 名前・必須引数 | 動作・条件・結果 |
|---|---|---|
| 追加 | `acquire(item: string, count: integer)` | itemをバッグ等にcount=1〜256個確保する複合操作。実レシピ・採集・道具準備・設備配置・精錬へ再帰分解し、実所持数で判定する。最大2048操作・30分。 |
| 既存 | `attack(entity_id: integer)` | 近くの敵対Mob・ボス・エンドクリスタル等へ接近して1回攻撃する。命中は討伐完了を意味しない。プレイヤーや味方を対象にしない。 |
| 追加 | `await_teammate(entity_id: integer)` | 担当が実際に完了するまで待つ。entity_id=-1は全担当。失敗／取り消しは失敗を返す。待機上限は36000Tick。 |
| 追加 | `container_put(x: integer, y: integer, z: integer, slot: integer, container_slot: integer, count: integer)` | 4ブロック以内のアクセス可能なコンテナのcontainer_slotへバッグslotからcount=1〜64個を入れる。枠の制約・ロック・所有権・かまど担当を確認する。 |
| 追加 | `container_take(x: integer, y: integer, z: integer, container_slot: integer, count: integer)` | 同条件のコンテナのcontainer_slotからcount=1〜64個をバッグへ移す。満杯であふれた分を勝手に落とさない。 |
| 既存 | `craft(recipe: string)` | 登録済みrecipeを1回実行する。実材料を消費し、大きいレシピには近くの作業台が必要。 |
| 追加 | `delegate(entity_id: integer, item: string, count: integer)` | 48ブロック以内の同じ所有者の空いているNPCへitemをcount=1〜64個確保してリーダーへ渡す担当を割り当てる。entity_id=-1は自動選択。再帰委任は禁止。 |
| 追加 | `delegate_task(entity_id: integer, plan_json: string)` | 同じ条件の仲間へ有限TaskPlanを割り当てる。plan_jsonはJSON文字列で8192文字以内。入れ子委任・plan_nextは禁止。探索・次元往復・受け渡し等を明記する。 |
| 既存 | `deposit(x: integer, y: integer, z: integer, slot: integer, count: integer)` | 4ブロック以内のアクセス可能なチェストへバッグslotからcount=1〜64個を収納する。実移動数movedを返し、余りは手元に残す。 |
| 既存 | `dig(x: integer, y: integer, z: integer)` | 1ブロックへ接近して実際に採掘する。道具の速度・耐久・収穫条件・保護・通常ドロップを使う。現在の足元は掘らない。 |
| 追加 | `dismount()` | 乗っている対象から降りる。 |
| 追加 | `drop_item(slot: integer, count: integer)` | バッグslotからcount=1〜64個を実際に地面へ落とす。 |
| 既存 | `eat(slot: integer)` | バッグの食料を装備して食事を開始する。開始成功と食事完了は別。食事中状態・空腹・消費を観察して確認する。 |
| 既存 | `equip(slot: integer, hand: string)` | バッグslot=0〜35とhand=main/offを交換する。空枠は持ち物を手から戻す。食事中は拒否する。 |
| 追加 | `equip_armor(slot: integer)` | バッグslot=0〜35の装備可能アイテムと対応する防具枠を交換する。防具性能は有効、アバターへの防具モデル描画はしない。 |
| 追加 | `explore_structure(structure: string)` | 登録済みstructureをロード済みの歩ける地形で探索する。実移動した経路・発見を保存し、最大256地点。海・溶岩・壁を無条件で突破しない。 |
| 既存 | `find_blocks(block: string, radius: integer)` | ロード済み領域の半径1〜16・上下8で指定blockを探す。近い順の観察結果を返す。 |
| 既存 | `find_chests(radius: integer)` | 半径1〜16のアクセス可能なチェストの位置・空でないスロット・個数を観察する。ロックや塞がれたチェストは操作しない。 |
| 既存 | `follow(entity_id: integer, seconds: integer)` | 近くの実在するentity_idを1〜60秒追従する。離れたときは走る。所有者のIDはobserve.ownerから取得する。 |
| 既存 | `forget_location(name: string)` | nameに一致する記憶を削除して枠を空ける。 |
| 追加 | `furnace_put(x: integer, y: integer, z: integer, slot: integer, furnace_slot: integer, count: integer)` | 5ブロック以内の通常かまどへバッグslotからcount=1〜64個を入れる。furnace_slot=0は原料、1は燃料。出力枠への投入と別NPCのバッチへの干渉は拒否する。 |
| 追加 | `furnace_take(x: integer, y: integer, z: integer, furnace_slot: integer, count: integer)` | 同条件の通常かまどのfurnace_slot=0〜2からcount=1〜64個を取り出す。2は実出力。別NPCの担当バッチは操作しない。 |
| 既存 | `give_item(entity_id: integer, slot: integer, count: integer)` | 4ブロック以内かつ見えるNPC／プレイヤーへバッグslotからcount=1〜64個を渡す。実際に受け取った数だけ減らす。 |
| 追加 | `hunt(entity_id: integer)` | プレイヤー・ペット等を除く生物／ボスを1回攻撃する。食料や素材の狩猟用。討伐・ドロップは別途観察する。 |
| 追加 | `inspect_block(x: integer, y: integer, z: integer)` | 32ブロック以内のロード済みブロック・状態・硬さ・視線等を観察する。中身の参照には別にコンテナへの距離・利用権限が必要。 |
| 追加 | `inspect_entities(radius: integer)` | 半径1〜64の実在する対象を最大64件観察する。位置・速度・生存・可視・体力とドラゴン部位IDを返す。エンダーアイ・投射物を優先する。 |
| 追加 | `inspect_entity(entity_id: integer)` | 128ブロック以内の指定対象／ドラゴン部位1件の実位置・速度・可視・生存・体力を調べる。古いIDが消えた場合は失敗。 |
| 追加 | `inspect_structures(radius: integer)` | radius=0〜1チャンクのロード済み構造物参照を調べる。未ロード遠方の/locateは行わない。参照の存在は内部を踏破した証明ではない。 |
| 追加 | `interact_block(x: integer, y: integer, z: integer, face: string)` | 現在の主手で指定ブロックのfaceを右クリックする。faceと距離・視線の条件はuse_on_blockと同じ。GUIが開くことと取引や加工の完了は別。 |
| 追加 | `interact_entity(entity_id: integer)` | 4ブロック以内かつ見える非プレイヤー・非仲間NPCへ、現在の主手でバニラの右クリックを1回行う。 |
| 追加 | `jump()` | 通常のジャンプを1回要求する。ジャンプの受付は障害物を越えたことの証明ではない。 |
| 追加 | `look_at(x: number, y: number, z: number)` | 128ブロック以内の実座標へ照準を向ける。弓の使用等の前に指定する。照準の保持は最大1200Tick。 |
| 追加 | `mount(entity_id: integer)` | 4ブロック以内の対象へ通常の騎乗判定を通して乗る。プレイヤー・仲間NPCは不可。乗った後の全乗物の操縦には対応していない。 |
| 追加 | `move_precisely(x: number, y: number, z: number)` | move_toの到着許容を0.2にし、自動ダッシュを使わず歩く。ドロップ回収や狭い道向け。飛行・テレポートはしない。 |
| 既存 | `move_to(x: number, y: number, z: number)` | ロード済みの64ブロック以内へ移動する。8ブロック以上で走り、4.5未満で歩行へ戻す。ブロック下端中心から0.8以内を到着とし、実際の位置を返す。移動停滞や経路なしは失敗。 |
| 既存 | `observe()` | 自分の体力・空腹・所持品・主手／副手・近くの対象・現在の作業を観察する。 |
| 既存 | `place(x: integer, y: integer, z: integer, slot: integer)` | バッグslot=0〜35の実物ブロックを空いている指定座標へ1個置く。到達・視線・支持面・所有者の権限を確認する。 |
| 追加 | `place_workstation(item: string)` | 持っているminecraft:chest/crafting_table/furnaceを近くの支持された空所へ実配置し、座標・次元を返す。新設チェストは後続の収納先にも固定する。 |
| 追加 | `release_item()` | 現在のアイテム保持／使用を終了する。弓の通常チャージ発射はuse_itemが指定Tick後に行う。 |
| 既存 | `remember_location(name: string, x: integer, y: integer, z: integer)` | 観察できる近くの場所／チェストをname付きで記憶する。名前は32文字以内、最大8件。同名更新可、満杯時の自動上書きなし。次元も保存する。 |
| 既存 | `say(text: string, target_id: integer)` | 近くへ短い発言を行う。target_id=-1は特定の聞き手なし。通常の発言は独立したAI周期が担当する。 |
| 追加 | `set_sneaking(enabled: integer)` | enabled=0/1でスニークを解除／開始する。 |
| 追加 | `set_sprinting(enabled: integer)` | enabled=0/1でスプリントを解除／開始する。実際の移動は別操作。 |
| 追加 | `smelt(x: integer, y: integer, z: integer, item: string, count: integer)` | 指定座標の通常かまどでitem（出力）をcount=1〜64個精錬する。実原料と燃料を投入しバニラのTickを待ち、回収した実出力で判定する。 |
| 既存 | `stop()` | 現在の移動・操作を止める。所有者の依頼そのものの取り消しにはGUIの作業停止を使う。 |
| 追加 | `team_status(entity_id: integer)` | リーダーの現在の依頼に属する担当の保存状態（running/complete/blocked/cancelled）を返す。entity_id=-1は全担当。会話から完了を推測しない。 |
| 追加 | `travel_dimension(dimension: string)` | dimensionで指定した次元へ、付近24ブロック・上下8で確認した実在の有効ポータルまで歩き、バニラの次元移動を行う。ポータルや資材を生成しない。 |
| 追加 | `use_item(slot: integer, ticks: integer)` | バッグslot=0〜35または主手slot=41を使用する。ticks=0は即時使用、1〜1200はチャージ／保持。弓・盾・投射物・消費物等の通常処理を使う。プレイヤー模擬操作にはバッグ1〜35に空枠が1つ必要。 |
| 追加 | `use_on_block(x: integer, y: integer, z: integer, slot: integer, face: string)` | バッグslotのアイテムを指定ブロックのfaceへ使用する。face=up/down/north/south/east/west。5ブロック以内・視線・所有者権限が必要。火打石・バケツ・実エンダーアイ等にバニラ処理を使う。 |
| 既存 | `wait(seconds: integer)` | seconds=1〜60のゲーム時間だけ待つ。 |
| 既存 | `withdraw(x: integer, y: integer, z: integer, slot: integer, count: integer)` | 4ブロック以内のチェストのslotからcount=1〜64個をバッグへ取り出す。チェスト側の番号はfind_chestsで取得する。 |

## 許可スキル

性格JSONの`allowedSkills`で明示許可するほか、既存の基本権限から次の派生権限を認めます。複数の基本権限が並ぶ行はすべて必要です。

| 基本権限 | 派生して使える操作 |
|---|---|
| observe | remember_location, forget_location, inspect_block, inspect_entities, inspect_entity, inspect_structures |
| move_to | move_precisely, look_at, jump, set_sneaking, set_sprinting, mount, dismount, travel_dimension, explore_structure |
| place | place_workstation |
| deposit | container_put |
| withdraw | container_take |
| give_item | drop_item |
| dig ＋ craft | acquire |
| craft ＋ place | smelt, furnace_put, furnace_take |
| give_item ＋ observe ＋ craft | delegate, delegate_task, team_status, await_teammate |
| equip ＋ attack | equip_armor, use_item, release_item |
| place ＋ observe | use_on_block, interact_block, interact_entity |
| attack | hunt |

派生許可は入口の許可です。内部で実行する採掘・移動・工作等にも必要な権限と実物・距離・保護の条件があり、一つの権限だけで全工程が通るとは限りません。委任も相手の性格の権限を迂回しません。

## 複合操作・計画・成功判定

`acquire`、`smelt`、`delegate`、`delegate_task`、`explore_structure`等は複数Tickや複数の基本操作にまたがります。`attack`、`use_on_block`、`container_put`等は細かい1操作です。高性能モデルがこれらを有限の計画に組み合わせます。

TaskPlanには最大24手順・16KiBの上限があります。`action="tool"`、`target="avatarcraftnpc:look_at"`、`count=1`、`arguments="{\"x\":1,\"y\":65,\"z\":2}"`のように、`arguments`へ正確な引数オブジェクトをJSON文字列として指定します。非tool手順のargumentsは空文字です。

`plan_next`はツールではなく計画末尾の制御手順です。検証済みの進捗から次の上位計画を要求し、最大64段階で打ち切ります。委任先はこれを使えません。`prepare_tool`、`pickup`等もTaskPlanの目標であり、別のAPIツール名ではありません。

結果の`ok`はその操作の成否です。受け渡し・収納・回収は`moved`等の実個数で判断し、1回の攻撃成功を討伐済みとしません。アイテム使用はゲーム側に受理されたことを示すため、目的の状態変更・消費・命中は続けて観察します。発言内容で作業を完了させません。

チェスト・樽・シュルカーボックスの収納／取り出しと右クリックは、NPCをバニラのContainerUserとして開き、15Tickの開く時間を経て操作し、その後に閉じます。ダブルチェストの両側、複数NPC／プレイヤーとの同時利用を扱います。中断・死亡・移動・撤去・権限変更では操作前に閉じ、品物を移しません。観察だけでは開きません。エンダーチェストの右クリックは開閉だけを行い、所有者の専用インベントリへアクセスしません。

通常の遠距離移動、採掘先や対象への接近、ポータルまでの移動、構造物探索では距離でダッシュを切り替えます。空腹6以下、水中、スニーク中、段差や狭い経路等は走りません。精密移動は歩行のままで、終了・停止時に走行を解除します。

## 現時点の範囲

通常かまど、登録クラフト、実ポータル通過、実エンドフレームへのアイ挿入、弓のチャージと発射、ドラゴンへの実ダメージ等に対応します。溶鉱炉・燻製器の自動バッチ、取引・エンチャント・金床GUIの操作、任意の乗物操縦、すべてのプレイヤー専用アイテムの挙動は網羅していません。

エンダードラゴンへ至る工程を組むための操作を追加していますが、新規ワールドから討伐までの完全自律完走は未検証です。構造物の参照発見、内部探索、戦闘勝利は別の達成条件です。探索予算・権限・危険・経路・材料が不足すれば、理由を返して再計画または保留にします。

再生成は`tools/NpcToolCatalog.java`で`build/npc-tools.json`を出力後、`python tools/generate_npc_tool_docs.py`。ツールの増減・説明の欠落は生成時に失敗します。

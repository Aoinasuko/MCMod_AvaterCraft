"""Render Japanese tool docs from NpcToolCatalog's actual Java schema export."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EXISTING = set('observe move_to follow equip find_blocks dig place attack deposit withdraw give_item find_chests craft eat say wait stop remember_location forget_location'.split())
DETAILS = {
    'area_work': '指定した2点を含む範囲へ既存ツールのテンプレートを展開する。steps_jsonにid・tool・arguments・priority・after・when・expect・required・itemを記述。依存完了→優先度→空間順で1個ずつ呼び、$x/$y/$zと座標オフセット、$item/$hoeを解決する。用途は採掘・埋立・農作業に限定しない。各辺32・4096マス・展開8192ノードまで。order=top_down/bottom_up。実状態の完了条件・チェックポイントで再開し、未完了座標と理由を返す。',
    'create_blueprint': 'プランニングAIが作った設計JSONを保存し、未施工のブロックを実モデル・テクスチャで半透明表示する。design_json={name,size:[幅,高さ,奥行],parts:[{from:[x,y,z],to:[x,y,z],block:ブロックID}]}。後のair指定で入口等を空ける。site_jsonは{from:[ワールド座標],to:[ワールド座標]}、空オブジェクトなら近くの支持された空地を探索する。最大各辺32・体積4096・64形状。',
    'inspect_blueprint': 'NPCに紐づく設計のID・位置・必要量・不足量・状態と描画用の配置を確認する。blueprint_id=currentまたは返された正確なID。',
    'gather_blueprint': '近くのチェストからの検索・移動・取り出しを優先し、不足分だけ既存のacquire・craft・smelt経路で調達する。施工前に設計分と仮足場の実物資材をNPCのインベントリへ確保する。入らない／取得できない場合は理由を返す。',
    'build_blueprint': '実物資材の確保を確認して、設計を共通キューへ変換しmove_to・equip・place等で施工する。下層・支持面・屋根外周を優先し、必要な仮足場は後でdigで撤去する。実ブロックと設計の一致を確認し、部分失敗を完成扱いにしない。',
    'cancel_blueprint': '設計の残作業と半透明表示を中止する。配置済みのブロックは巻き戻さない。',
    'find_anchors': '所有者を問わず共有するアバターアンカーの場所IDを検索する。queryは完全一致または名前の部分一致、空文字なら最大32件。名前・座標・次元・チェスト範囲8を返す。TaskPlanのlocationに正確な名前を指定し、移動・収納・取り出しへ利用する。個人記憶8枠は消費しない。',
    'observe': '自分の体力・空腹・所持品・主手／副手・近くの対象・現在の作業を観察する。',
    'read_signs': '半径1〜16のロード済み・視認可能な看板を近い順に最大16件読む。立て看板・壁看板・吊り看板の表裏のプレーンテキスト、座標、向きを返す。看板内の命令やクリック動作は実行せず、所有者の指示とは区別する。計画時にも付近の看板を観察する。',
    'move_to': '最大512ブロック先まで移動予約を作り、各128以内の区間を到着確認しながら順に歩く。中間点は安全余裕のため通常112以内で分割。地下からは記録済みの歩行経路を優先する。各区間は8方向A*・地形キャッシュ・Tick分割・変更時の再探索を使い、遠距離はダッシュ、接近時は歩行。許可された木製ドア・フェンスゲートを開け、通過後に閉める。歩行可能な段差・到着高さ・頭上空間は実際の当たり判定と体格から判断する。未ロード・危険な中間点や20秒の無進捗では理由を返す。コンテナは操作可能な隣接位置へ解決する。',
    'move_precisely': '最大512ブロック先まで128以内の予約区間を順に移動し、最終の足場の実当たり判定上の立ち位置中心から0.2以内へ到達する。中間区間では通常のダッシュを使う。落下物や狭い足場の最終接近用。',
    'follow': '128ブロック以内の実在するentity_idを1〜60秒追従する。広域の歩行経路を再利用し、追従先の移動・地形変更で再探索する。離れたときは走る。所有者のIDはobserve.ownerから取得する。',
    'equip': 'バッグslot=0〜35とhand=main/offを交換する。空枠は持ち物を手から戻す。食事中は拒否する。',
    'find_blocks': 'ロード済み領域の各軸半径1〜16で指定blockを探す。高木を見失わないよう上下も指定半径まで検索。正確なブロックIDまたは#namespace:tagを受け付け、原木一般は#minecraft:logsを使う。近い順の観察結果を返す。',
    'dig': '1ブロックへ接近して実際に採掘する。道具の速度・耐久・収穫条件・保護・通常ドロップを使う。現在の足元は掘らない。',
    'place': 'バッグslot=0〜35の実物ブロックを空いている指定座標へ1個置く。到達・視線・支持面・所有者の権限を確認する。',
    'attack': '近くの敵対Mob・ボス・エンドクリスタル等へ接近して1回攻撃する。命中は討伐完了を意味しない。プレイヤーや味方を対象にしない。',
    'defeat': '敵対Mobへ通常移動と同じ広域経路・ドア／ゲート処理で接近し、実際の死亡を確認するまで繰り返し攻撃する。seconds=1〜60の上限で未達なら失敗を返す。1回の命中を討伐完了にしない。',
    'guard_post': '固定したx/y/z・対象mob・追撃radiusをローカルで保持し、所有者の停止／変更指示まで継続監視する。射撃・食事・帰還はguardと同じ。60秒ごとの再計画や敵ごとの外部AI呼出しで監視を中断しない。有限の時間指定にはguardを使う。',
    'guard': '指定x/y/zを固定した持ち場とし、mobで指定した敵対Mobをseconds=1〜60の間監視する。追撃経路はradius=1〜16の内側に制限し、範囲外へ追い続けない。携帯した実物の弓と矢を使って射撃し、必要なら食事する。被弾による物理的なノックバック後は持ち場へ戻る。継続監視はplan_nextで元の持ち場を引き継ぐ。矢・弓・食料は生成しない。',
    'hunt': 'プレイヤー・ペット等を除く生物／ボスを1回攻撃する。食料や素材の狩猟用。討伐・ドロップは別途観察する。',
    'deposit': '4ブロック以内の利用可能なチェストへバッグslotからcount=1〜64個を収納する。互換スタックと空き容量を確認し、実移動数movedを返す。満杯はCONTAINER_FULL。TaskPlanのdeposit目標では優先庫の満杯後に別の近隣チェストへ切り替え、部分収納済みの数を引き継ぐ。',
    'withdraw': '4ブロック以内のチェストのslotからcount=1〜64個をバッグへ取り出す。チェスト側の番号はfind_chestsで取得する。',
    'give_item': '4ブロック以内かつ見えるNPC／プレイヤーへバッグslotからcount=1〜64個を渡す。実際に受け取った数だけ減らす。',
    'find_chests': '半径1〜16のアクセス可能なチェストの位置・空でないスロット・個数を観察する。ロックや塞がれたチェストは操作しない。',
    'craft': '登録済みrecipeを1回実行する。実材料を消費し、大きいレシピには近くの作業台が必要。',
    'eat': 'バッグの食料を装備して食事を開始する。開始成功と食事完了は別。食事中状態・空腹・消費を観察して確認する。',
    'say': '近くへ短い発言を行う。target_id=-1は特定の聞き手なし。通常の発言は独立したAI周期が担当する。',
    'wait': 'seconds=1〜60のゲーム時間だけ待つ。',
    'stop': '現在の移動・操作を止める。所有者の依頼そのものの取り消しにはGUIの作業停止を使う。',
    'work_route': 'ブロック編集は128以内の観察座標への作業経路。goal=reachで128を超える場合は最大512までの歩行予約に切り替える。reach/mine/pickupは広域の歩行迂回を先に調べ、歩行で届かないと確認してから掘削・設置へ進む。未ロード境界・探索予算切れで未確定の場合は編集を始めない。goal=reachは移動、mineは採掘できる位置、bridgeは明示的な架橋、pickupは落下物の実座標へ接近。mode=dig/build/both、max_blocks=1〜128。土・丸石等を実消費する。bothは不足材料を実採集する。buildは持ち込んだ材料だけで設置し、資材採集でも掘らない。',
    'remember_location': '観察できる近くの場所／チェストをname付きで記憶する。名前は32文字以内、最大8件。同名更新可、満杯時の自動上書きなし。次元も保存する。',
    'forget_location': 'nameに一致する記憶を削除して枠を空ける。',
    'acquire': 'itemをバッグ等にcount=1〜256個確保する複合操作。実レシピ・採集・道具準備・設備配置・精錬へ再帰分解し、実所持数で判定する。最大2048操作・30分。',
    'smelt': 'アクセス可能なかまどで所持材料をバニラのTick処理で精錬し、実出力を受け取る。燃料の実燃焼時間・速度と必要量を確認し、道具・装備と原木を直接燃やさず必要数だけ投入、途中補充する。不足はFUEL_INSUFFICIENTで返し、部分出力と所有予約は保持する。acquireは原木から板材への実クラフトを含め、不足燃料を調達してから再開する。',
    'delegate': '48ブロック以内の同じ所有者の空いているNPCへitemをcount=1〜64個確保してリーダーへ渡す担当を割り当てる。entity_id=-1は自動選択。再帰委任は禁止。',
    'delegate_task': '同じ条件の仲間へ有限TaskPlanを割り当てる。plan_jsonはJSON文字列で8192文字以内。入れ子委任・plan_nextは禁止。探索・次元往復・受け渡し等を明記する。',
    'team_status': 'リーダーの現在の依頼に属する担当の保存状態（running/complete/blocked/cancelled）を返す。entity_id=-1は全担当。会話から完了を推測しない。',
    'await_teammate': '担当が実際に完了するまで待つ。entity_id=-1は全担当。失敗／取り消しは失敗を返す。待機上限は36000Tick。',
    'place_workstation': '持っているminecraft:chest/crafting_table/furnaceを近くの支持された空所へ実配置し、座標・次元を返す。新設チェストは後続の収納先にも固定する。',
    'travel_dimension': 'dimensionで指定した次元へ、付近24ブロック・上下8で確認した実在の有効ポータルまで歩き、バニラの次元移動を行う。ポータルや資材を生成しない。',
    'inspect_structures': 'radius=0〜1チャンクのロード済み構造物参照を調べる。未ロード遠方の/locateは行わない。参照の存在は内部を踏破した証明ではない。',
    'explore_structure': '登録済みstructureをロード済みの歩ける地形で探索する。実移動した経路・発見を保存し、最大256地点。海・溶岩・壁を無条件で突破しない。',
    'look_at': '128ブロック以内の実座標へ照準を向ける。弓の使用等の前に指定する。照準の保持は最大1200Tick。',
    'jump': '通常のジャンプを1回要求する。ジャンプの受付は障害物を越えたことの証明ではない。',
    'set_sneaking': 'enabled=0/1でスニークを解除／開始する。',
    'set_sprinting': 'enabled=0/1でスプリントを解除／開始する。実際の移動は別操作。',
    'equip_armor': 'バッグslot=0〜35の装備可能アイテムと対応する防具枠を交換する。防具性能は有効、アバターへの防具モデル描画はしない。',
    'drop_item': 'バッグslotからcount=1〜64個を実際に地面へ落とす。',
    'use_item': 'バッグslot=0〜35または主手slot=41を使用する。ticks=0は即時使用、1〜1200はチャージ／保持。弓・盾・投射物・消費物等の通常処理を使う。プレイヤー模擬操作にはバッグ1〜35に空枠が1つ必要。',
    'release_item': '現在のアイテム保持／使用を終了する。弓の通常チャージ発射はuse_itemが指定Tick後に行う。',
    'use_on_block': 'バッグslot=0〜35の品を主手へ交換し、指定ブロックのfaceへ使用する。使用後も品は主手に残る。同じ品の連続使用はslot=41、元のバッグ枠へ戻す場合はequipを使う。face=up/down/north/south/east/west。5ブロック以内・視線・所有者権限が必要。火打石・バケツ・実エンダーアイ等にバニラ処理を使う。バケツはuseOnで処理されない場合、指定面を狙ってバニラのuseを呼ぶ。',
    'interact_block': '現在の主手で指定ブロックのfaceを右クリックする。faceと距離・視線の条件はuse_on_blockと同じ。GUIが開くことと取引や加工の完了は別。',
    'interact_entity': '4ブロック以内かつ見える非プレイヤー・非仲間NPCへ、現在の主手でバニラの右クリックを1回行う。',
    'mount': '4ブロック以内の対象へ通常の騎乗判定を通して乗る。プレイヤー・仲間NPCは不可。乗った後の全乗物の操縦には対応していない。',
    'dismount': '乗っている対象から降りる。',
    'inspect_entities': '半径1〜64の実在する対象を最大64件観察する。位置・速度・生存・可視・体力とドラゴン部位IDを返す。エンダーアイ・投射物を優先する。',
    'inspect_entity': '128ブロック以内の指定対象／ドラゴン部位1件の実位置・速度・可視・生存・体力を調べる。古いIDが消えた場合は失敗。',
    'inspect_block': '32ブロック以内のロード済みブロック・状態・硬さ・視線等を観察する。中身の参照には別にコンテナへの距離・利用権限が必要。',
    'container_put': '4ブロック以内のアクセス可能なコンテナのcontainer_slotへバッグslotからcount=1〜64個を入れる。枠の制約・ロック・所有権・かまど担当を確認する。',
    'container_take': '同条件のコンテナのcontainer_slotからcount=1〜64個をバッグへ移す。満杯であふれた分を勝手に落とさない。',
    'furnace_put': '5ブロック以内の通常かまどへバッグslotからcount=1〜64個を入れる。furnace_slot=0は原料、1は燃料。出力枠への投入と別NPCのバッチへの干渉は拒否する。',
    'furnace_take': '同条件の通常かまどのfurnace_slot=0〜2からcount=1〜64個を取り出す。2は実出力。別NPCの担当バッチは操作しない。',
}


def main():
    tools = json.loads((ROOT / 'build/npc-tools.json').read_text(encoding='utf-8-sig'))
    names = {t['name'].removeprefix('npc_') for t in tools}
    assert names == DETAILS.keys(), names ^ DETAILS.keys()
    lines = [
        '# NPCツール一覧', '',
        f'2026-10-25 / 0.7.1。Javaの`NpcTools`から抽出した契約に基づく全{len(tools)}種。従来19種（基本17種＋場所記憶2種）と追加{len(tools)-len(EXISTING)}種を併記します。', '',
        '通常の指示理解・発言のAIはツールを直接実行しません。計画を検証したサーバー側ローカル実行系が、許可された操作だけを呼び出します。API上の名前には`npc_`が付き、TaskPlanのatomic指定は`avatarcraftnpc:名前`です。', '',
        '表の引数はすべて必須、未知の引数は拒否します。`integer`は整数、`number`は有限の数値、`string`は文字列です。通常の文字列は最大512文字。座標は現在の次元の実ワールド座標です。バッグ枠は0〜35、観察上の副手は40、主手は41です。コンテナ枠はそれぞれの観察結果から取得してください。', '',
        '| 区分 | 名前・必須引数 | 動作・条件・結果 |',
        '|---|---|---|',
    ]
    for t in tools:
        name = t['name'].removeprefix('npc_')
        args = ', '.join(f'{k}: {v["type"]}' for k, v in t['inputSchema']['properties'].items())
        lines.append(f'| {"既存" if name in EXISTING else "追加"} | `{name}({args})` | {DETAILS[name]} |')
    lines += [
        '', '## 許可スキル', '',
        '性格JSONの`allowedSkills`で明示許可するほか、既存の基本権限から次の派生権限を認めます。複数の基本権限が並ぶ行はすべて必要です。', '',
        '| 基本権限 | 派生して使える操作 |', '|---|---|',
        '| observe | remember_location, forget_location, inspect_block, inspect_entities, inspect_entity, inspect_structures, read_signs, find_anchors |',
        '| move_to | move_precisely, look_at, jump, set_sneaking, set_sprinting, mount, dismount, travel_dimension, explore_structure |',
        '| place | place_workstation |', '| deposit | container_put |', '| withdraw | container_take |',
        '| give_item | drop_item |', '| dig ＋ craft | acquire |',
        '| craft ＋ place | smelt, furnace_put, furnace_take |',
        '| move_to ＋ dig ＋ place ＋ observe | work_route |',
        '| move_to ＋ observe ＋ 実行する子ツールの権限 | area_work |',
        '| observe ＋ place | create_blueprint |',
        '| observe | inspect_blueprint, cancel_blueprint |',
        '| observe ＋ move_to ＋ withdraw ＋ dig ＋ craft | gather_blueprint |',
        '| observe ＋ move_to ＋ place | build_blueprint |',
        '| give_item ＋ observe ＋ craft | delegate, delegate_task, team_status, await_teammate |',
        '| equip ＋ attack | equip_armor, use_item, release_item |',
        '| place ＋ observe | use_on_block, interact_block, interact_entity |',
        '| attack | hunt |', '',
        '派生許可は入口の許可です。内部で実行する採掘・移動・工作等にも必要な権限と実物・距離・保護の条件があり、一つの権限だけで全工程が通るとは限りません。委任も相手の性格の権限を迂回しません。', '',
        '## 複合操作・計画・成功判定', '',
        '`acquire`、`smelt`、`delegate`、`delegate_task`、`explore_structure`等は複数Tickや複数の基本操作にまたがります。`attack`、`use_on_block`、`container_put`等は細かい1操作です。高性能モデルがこれらを有限の計画に組み合わせます。', '',
        'TaskPlanには最大24手順・16KiBの上限があります。`action="tool"`、`target="avatarcraftnpc:look_at"`、`count=1`、`arguments="{\\"x\\":1,\\"y\\":65,\\"z\\":2}"`のように、`arguments`へ正確な引数オブジェクトをJSON文字列として指定します。非tool手順のargumentsは空文字です。', '',
        '`plan_next`はツールではなく計画末尾の制御手順です。検証済みの進捗から次の上位計画を要求し、最大64段階で打ち切ります。委任先はこれを使えません。`prepare_tool`、`pickup`等もTaskPlanの目標であり、別のAPIツール名ではありません。', '',
        '結果の`ok`はその操作の成否です。受け渡し・収納・回収は`moved`等の実個数で判断し、1回の攻撃成功を討伐済みとしません。アイテム使用はゲーム側に受理されたことを示すため、目的の状態変更・消費・命中は続けて観察します。発言内容で作業を完了させません。', '',
        'チェスト・樽・シュルカーボックスの収納／取り出しと右クリックは、NPCをバニラのContainerUserとして開き、15Tickの開く時間を経て操作し、その後に閉じます。ダブルチェストの両側、複数NPC／プレイヤーとの同時利用を扱います。中断・死亡・移動・撤去・権限変更では操作前に閉じ、品物を移しません。観察だけでは開きません。エンダーチェストの右クリックは開閉だけを行い、所有者の専用インベントリへアクセスしません。', '',
        '通常の遠距離移動、採掘先や対象への接近、ポータルまでの移動、構造物探索では距離でダッシュを切り替えます。空腹6以下、水中、スニーク中、段差や狭い経路等は走りません。精密移動は歩行のままで、終了・停止時に走行を解除します。', '',
        '## 現時点の範囲', '',
        '通常かまど、登録クラフト、実ポータル通過、実エンドフレームへのアイ挿入、弓のチャージと発射、ドラゴンへの実ダメージ等に対応します。溶鉱炉・燻製器の自動バッチ、取引・エンチャント・金床GUIの操作、任意の乗物操縦、すべてのプレイヤー専用アイテムの挙動は網羅していません。', '',
        'エンダードラゴンへ至る工程を組むための操作を追加していますが、新規ワールドから討伐までの完全自律完走は未検証です。構造物の参照発見、内部探索、戦闘勝利は別の達成条件です。探索予算・権限・危険・経路・材料が不足すれば、理由を返して再計画または保留にします。', '',
        '再生成は`tools/NpcToolCatalog.java`で`build/npc-tools.json`を出力後、`python tools/generate_npc_tool_docs.py`。ツールの増減・説明の欠落は生成時に失敗します。', '',
    ]
    text = '\n'.join(lines)
    for path in ('Docs/NpcTools.md', 'release/NPC_Tools_ja.md'):
        (ROOT / path).write_text(text, encoding='utf-8')
    print(f'Wrote {len(tools)} tool contracts')


if __name__ == '__main__':
    main()

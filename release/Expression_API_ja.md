# Loaderの表情キーAPI

Loaderはモデル読込、共通描画、文字列キーからBlendShapeへの変換を提供します。食事・被ダメージのゲーム状態の判定は利用するMODで行います。自動まばたきはLoaderの共通プレイヤーが担当します。

標準キーは `idle`（通常）、`blink`（閉眼）、`eat`（食事）、`hurt`（被ダメージ）です。任意キーは英小文字・数字・`_`・`.`・`-`の1〜64文字です。`config/bep_3davater_loader/expressions/<avatar-id>.json` の `events` に追加できます。Loaderタブの「表情キーJSON編集」から直接編集し、検証して保存することもできます。

```json
{
  "formatVersion": 1,
  "blinkMinSeconds": 2,
  "blinkMaxSeconds": 4,
  "blinkDurationSeconds": 0.16,
  "events": {
    "idle": {},
    "blink": {},
    "eat": {},
    "hurt": {},
    "happy": {"メッシュ名:シェイプキー名": 100}
  }
}
```

シェイプキーはLoaderの表情設定に表示される実際の名前で指定します。重みは0〜100です。GUIから保存した表情は同じモデルを着用中のプレイヤーへ反映し、NPCも再読込された設定を参照します。外部エディターでJSONを変更した場合はLoaderの「再読込」を押し、Player Modelで再度着用してください。

独立したモデルを動かす場合は `AvatarAnimation` にキーを指定します。

```java
var animation = new dev.avatarcraft.api.AvatarAnimation(archive, seed);
animation.expressionKeys.set("happy");
animation.update(signals, seconds, position, bodyYaw);
// 表情を解除
animation.expressionKeys.clear();
```

同時に指定するキーは低い優先度から順に渡します。`set("eat", "hurt")` では重なるシェイプキーを `hurt` で上書きします。指定していないキーの重みは毎フレーム元に戻り、前の表情を残しません。キーの状態とまばたきの時計はアバターインスタンスごとに独立しています。

Mobを描画する拡張では、`AvatarActor.avatarExpressionKeys()` でキーの一覧を返し、Loaderの `AvatarActorRenderer` に登録します。Companionはこの方法で食事・飲み物から `eat`、被ダメージから `hurt` を指定します。Player Modelはプレイヤーの状態を判定し同じキーAPIを呼び出します。Loaderは `MotionSignals.hurt` や `useAction` を表情選択には使用しません。

操作設定のB.E.P 3DAvater Loaderカテゴリでは、通常・まばたき・食事・被ダメージの手動キーも割り当てられます（初期状態は未割当）。選択中モデルのキーを切り替え、同じキーまたは通常キーで解除します。Player Model導入時はローカルプレイヤーにも適用されます。手動キーの選択状態は他プレイヤーへ同期しません。

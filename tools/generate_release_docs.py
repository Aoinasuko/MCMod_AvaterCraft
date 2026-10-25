"""Generate the offline, bilingual release guide. No external packages or network needed."""
from html import escape
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "release"


def pair(ja, en, tag="div", cls=""):
    attr = f' class="{cls}"' if cls else ""
    return f'<{tag}{attr} data-i18n="ja" lang="ja">{ja}</{tag}><{tag}{attr} data-i18n="en" lang="en">{en}</{tag}>'


def section(anchor, number, ja_title, en_title, ja, en):
    return f'<section class="chapter" id="{anchor}"><div class="section-label">{number}</div>{pair(ja_title, en_title, "h2")}{pair(ja, en)}</section>'


def callout(ja_title, ja, en_title, en):
    return pair(f'<strong>{ja_title}</strong>{ja}', f'<strong>{en_title}</strong>{en}', cls="callout")


PAGES = {
    "index": dict(ja="導入・変換ガイド", en="Installation & export", eyebrow="GETTING STARTED", subtitle_ja="アバターを、<br><em>Minecraftへ。</em>", subtitle_en="Your avatar.<br><em>A new world.</em>",
        lead_ja="VRChatプロジェクトからアバターを変換し、Minecraftで使うまで。Unityツールの導入からゲーム内の設定まで、順番に案内します。",
        lead_en="Bring an avatar from its VRChat Unity project into Minecraft. Follow the steps from installing the exporter to configuring your model in game.",
        toc=[("install", "UnityPackageの導入", "Install the UnityPackage"), ("export", "ツールの使い方", "Use the exporter"), ("files", "変換データの配置", "Place your exported data"), ("settings", "ゲーム内での設定", "Configure in game"), ("help", "困ったとき・クレジット", "Help & credits")]),
    "player": dict(ja="Playerモデルの設定方法", en="Player model setup", eyebrow="PLAYER MODEL", subtitle_ja="自分のアバターで、<br><em>歩きはじめる。</em>", subtitle_en="Make the world<br><em>your own.</em>",
        lead_ja="Player Modelを追加して、プレイヤーの外見をアバターへ。着用、表情、一人称表示とマルチプレイの設定をまとめました。",
        lead_en="Add Player Model to use an avatar as your player appearance. Set up wearing, expressions, first-person arms and multiplayer display.",
        toc=[("prepare", "必要なMODとデータ", "Mods & model files"), ("wear", "モデルを着る・解除する", "Wear or remove a model"), ("expressions", "表情と一人称", "Expressions & first person"), ("multiplayer", "マルチプレイで使う", "Use in multiplayer"), ("help", "うまく表示されないとき", "Troubleshooting")]),
    "npc": dict(ja="NPCMODの使い方", en="NPC mod guide", eyebrow="COMPANION", subtitle_ja="アバターと、<br><em>一緒に冒険する。</em>", subtitle_en="A companion for<br><em>your next adventure.</em>",
        lead_ja="Companionを追加して、AIで動くアバターを仲間に。接続設定、召喚、モデルと性格の変更、作業の頼み方を案内します。",
        lead_en="Add Companion to turn an avatar into an AI-controlled companion. Configure a connection, summon an NPC, choose a personality and give it tasks.",
        toc=[("prepare", "導入と準備", "Install & prepare"), ("ai", "AIモデルの接続設定", "Connect an AI model"), ("summon", "NPCを召喚する", "Summon a companion"), ("customize", "モデル・性格の変更", "Model & personality"), ("tasks", "持ち物・会話・作業", "Inventory, chat & tasks"), ("journal", "記録帳と60分の統計", "Journal & 60-minute statistics"), ("recall", "呼び戻し・保存・設定", "Recall, saves & settings"), ("help", "接続・動作の確認", "Troubleshooting")]),
}


def cards():
    return '<div class="two-columns">' + ''.join(
        f'<a class="next-card" data-page-link href="{page}.html"><span class="arrow">↗</span>' + pair(ja, en, "b") + pair(jdesc, edesc, "small") + '</a>'
        for page, ja, en, jdesc, edesc in [
            ("player", "Playerモデルの設定方法", "Player model setup", "自分のプレイヤーに着せる・表情・オンライン表示", "Wear a model, configure expressions and play online"),
            ("npc", "NPCMODの使い方", "NPC mod guide", "AIへの接続・召喚・モデルと性格・作業指示", "Connect AI, summon NPCs, customize them and give tasks")]) + '</div>'


def main_content():
    flow = '<div class="flow" aria-label="Setup steps">' + ''.join(
        f'<a href="#{anchor}"><span class="step-no">0{i}</span>{pair(ja, en, "span")}{pair(jsmall, esmall, "small")}</a>'
        for i, (anchor, ja, en, jsmall, esmall) in enumerate([
            ("install", "ツールを入れる", "Install", "UnityPackageを導入", "Import the UnityPackage"),
            ("export", "アバターを変換", "Export", ".avcを書き出す", "Create an .avc file"),
            ("files", "ゲームへ配置", "Place files", "MODとデータを配置", "Install mods & model data"),
            ("settings", "ゲーム内で設定", "Configure", "@キーから選択", "Open settings with @ / [")], 1)) + '</div>'
    content = flow
    content += section("install", "01 / UNITY PROJECT", "VRChatプロジェクトへUnityPackageを導入する", "Install the UnityPackage in your VRChat project", """
<p>普段アバターを編集しているUnityプロジェクトを使います。変換するアバターのルートに、有効な<strong>Humanoid Animator</strong>が必要です。確認環境はUnity 2022.3.22f1です。</p>
<ol><li>配布ZIPを展開し、<code>Unity/BEP-3DAvater-Exporter-0.10.0.unitypackage</code>を確認します。</li>
<li>アバターのUnityプロジェクトを開き、SDK・シェーダーなどのコンパイルが終わるまで待ちます。Modular Avatarを使うアバターでは、そのプロジェクトにModular AvatarとNDMFも必要です。</li>
<li><strong>Assets &gt; Import Package &gt; Custom Package…</strong>から上記のUnityPackageを選択します。</li>
<li>インポート画面でファイルを選択したまま<strong>Import</strong>を押します。ツールは<code>Assets/BEP3DAvaterExporter/</code>へ入ります。</li>
<li>コンパイル後、メニューに<strong>Tools &gt; B.E.P 3DAvater Exporter</strong>が表示されることを確認します。</li></ol>
<div class="callout"><strong>既に導入済みのとき</strong>UPM版（Package Managerのローカル／tarball）とUnityPackage版を同時に入れないでください。UPM版を使っていた場合は、Package Managerからその登録を外してからUnityPackage版を導入します。アバターや保存したモーションプロファイルは残してください。</div>
<details><summary>Newtonsoft.Jsonのエラーが出る場合・UPM形式で導入する場合</summary><p>UnityPackageは依存パッケージを自動導入しません。Newtonsoft.Jsonが未導入の場合は、<strong>Window &gt; Package Manager &gt; + &gt; Add package by name…</strong>で<code>com.unity.nuget.newtonsoft-json</code>、バージョン<code>3.2.1</code>を追加します。インターネット接続が必要になる場合があります。</p><p>UPM版を使う場合はUnityPackageを導入せず、<strong>Add package from tarball…</strong>で<code>Unity/dev.avatarcraft.exporter-0.10.0.tgz</code>を選びます。UPM版は同じツールと依存パッケージ指定を含みます。</p></details>
""", """
<p>Use the Unity project where you normally edit your avatar. The avatar root must have a valid <strong>Humanoid Animator</strong>. The exporter was checked with Unity 2022.3.22f1.</p>
<ol><li>Extract the release ZIP and locate <code>Unity/BEP-3DAvater-Exporter-0.10.0.unitypackage</code>.</li>
<li>Open the avatar's Unity project and wait for its SDK and shaders to compile. If the avatar uses Modular Avatar, install Modular Avatar and NDMF in that project too.</li>
<li>Choose <strong>Assets &gt; Import Package &gt; Custom Package…</strong> and select the UnityPackage.</li>
<li>Keep the package files selected and click <strong>Import</strong>. The tool is installed under <code>Assets/BEP3DAvaterExporter/</code>.</li>
<li>After compilation, check for <strong>Tools &gt; B.E.P 3DAvater Exporter</strong> in the menu.</li></ol>
<div class="callout"><strong>Updating an existing installation</strong>Use one installation method per project. If you used the UPM version through Package Manager, remove its registration before importing the UnityPackage. Keep your avatar and saved motion profiles.</div>
<details><summary>Newtonsoft.Json errors / installing the UPM version</summary><p>The UnityPackage does not install package dependencies automatically. If Newtonsoft.Json is missing, open <strong>Window &gt; Package Manager &gt; + &gt; Add package by name…</strong> and add <code>com.unity.nuget.newtonsoft-json</code> version <code>3.2.1</code>. This may require an internet connection.</p><p>For UPM installation, skip the UnityPackage and use <strong>Add package from tarball…</strong> to select <code>Unity/dev.avatarcraft.exporter-0.10.0.tgz</code>. It contains the same tool and declares the dependency.</p></details>
""")
    content += section("export", "02 / EXPORTER", "ツールを使って、.avcへ変換する", "Export your avatar as an .avc file", """
<ol><li>Hierarchyでアバターのルートを選び、<strong>Tools &gt; B.E.P 3DAvater Exporter &gt; Avatar export and motions</strong>を開きます。</li>
<li>上部の<strong>Language / 言語</strong>から<strong>日本語</strong>または<strong>English</strong>を選び、アバターのルート欄を確認します。ルートをドラッグして指定することもできます。</li>
<li>動作ごとの「標準モーション」を選びます。34動作・39本の標準Clipを収録しています。「差し替えClip（任意）」には独自のAnimationClipを指定できます。</li>
<li>「確認」でプレビューし、再生速度（0.1〜4倍）と切替時間（0〜2秒）を調整します。「編集用 .anim を生成」は、編集用のClipをAssets以下に作成します。</li>
<li><strong>設定を保存</strong>でモーションプロファイルをAssets以下へ保存します。必要なアバターでは、複製上へのModular Avatar / NDMF適用を有効にします。</li>
<li><strong>この設定で .avc を変換</strong>を押し、保存先とファイル名を選びます。結果と警告を確認し、できた<code>.avc</code>を次の手順でゲームへ配置します。</li></ol>
<div class="callout"><strong>Unityで設定を変えたら、再変換</strong>衣装・素材・PhysBone・モーションの変更は、.avcを再出力して反映します。元のシーンアバターは保持されます。NDMFによる一時アセットがプロジェクト内に残る場合があります。</div>
<details><summary>変換で再現する範囲</summary><p>メッシュ、テクスチャ、Humanoid参照、BlendShape、対応するlilToon素材、モーション、PhysBone情報を変換します。UnityのシェーダーとVRChatの物理を近似するため、見た目や揺れは完全には一致しません。Animator FX、VRChat Expression Menu、実行時スクリプト、他者によるPhysBoneの掴みは動きません。Minecraftの防具・エリトラの外観もアバターへ合成しません。</p></details>
""", """
<ol><li>Select the avatar root in the Hierarchy. Open <strong>Tools &gt; B.E.P 3DAvater Exporter &gt; Avatar export and motions</strong>.</li>
<li>Choose <strong>日本語</strong> or <strong>English</strong> under <strong>Language / 言語</strong>. Check <strong>Avatar root</strong>; you can drag the root into this field.</li>
<li>Choose a <strong>Built-in motion</strong> for each action. The library includes 34 actions and 39 clips. Assign your own AnimationClip to <strong>Custom clip (optional)</strong> when needed.</li>
<li>Use <strong>Preview</strong> to check the result. Set playback speed (0.1–4×) and blend duration (0–2 seconds). <strong>Generate editable .anim clips</strong> creates clips in an Assets subfolder.</li>
<li>Click <strong>Save profile</strong> to save a motion profile in Assets. Enable <strong>Apply Modular Avatar / NDMF to an isolated clone</strong> if your avatar requires it.</li>
<li>Click <strong>Export .avc with this profile</strong>, select the destination and filename, then check the result and warnings. Place the exported <code>.avc</code> in Minecraft as described next.</li></ol>
<div class="callout"><strong>Changed something in Unity? Export again.</strong>Changes to clothing, materials, PhysBones and motions require a new .avc export. The original scene avatar is preserved. NDMF may leave generated temporary assets in the project.</div>
<details><summary>What conversion supports</summary><p>The exporter includes meshes, textures, Humanoid references, BlendShapes, supported lilToon material settings, motions and PhysBone data. Shader and physics behavior is approximated, so it may differ from Unity or VRChat. Animator FX, VRChat Expression Menus, runtime scripts and PhysBone grabbing by other users are not executed. Minecraft armor and elytra visuals are not combined with the avatar.</p></details>
""")
    content += section("files", "03 / MINECRAFT FILES", "MOD環境へ、変換したデータを置く", "Place the exported data in your modded game", """
<p><strong>Minecraft Java Edition 26.3 / NeoForge 26.3.0.95 / Java 25</strong>の環境を使います。配置先は、実際に起動するランチャープロファイルの<strong>ゲームディレクトリ</strong>です。</p>
<ol><li>ゲームを終了し、配布物の<code>Minecraft/mods/</code>から必要なJARをゲームディレクトリの<code>mods/</code>へコピーします。</li><li>変換した<code>.avc</code>を<code>config/bep_3davater_loader/avatars/</code>へ置きます。フォルダがなければ作成します。.avcは展開しません。</li><li>ゲームを起動します。起動中にデータを追加・更新した場合は、Loaderタブで「再読込」を押してください。</li></ol>
<div class="table-wrap"><table><thead><tr><th>使いたい機能</th><th>導入するMOD</th></tr></thead><tbody><tr><td>モデル一覧・表情設定</td><td>Loader</td></tr><tr><td>プレイヤーに着せる</td><td>Loader ＋ Player Model</td></tr><tr><td>AIの仲間を使う</td><td>Loader ＋ Companion</td></tr><tr><td>両方使う</td><td>Loader ＋ Player Model ＋ Companion</td></tr></tbody></table></div>
""", """
<p>Use <strong>Minecraft Java Edition 26.3 / NeoForge 26.3.0.95 / Java 25</strong>. Place files in the <strong>game directory</strong> used by the launcher profile you actually start.</p>
<ol><li>Close the game. Copy the required JARs from the release's <code>Minecraft/mods/</code> into the game directory's <code>mods/</code> folder.</li><li>Place the exported <code>.avc</code> in <code>config/bep_3davater_loader/avatars/</code>. Create the folder if needed. Do not extract the .avc archive.</li><li>Start the game. If you add or update files while it is running, click <strong>再読込</strong> (Reload) in Loader.</li></ol>
<div class="table-wrap"><table><thead><tr><th>What you want to use</th><th>Required mods</th></tr></thead><tbody><tr><td>Model list & expressions</td><td>Loader</td></tr><tr><td>A player avatar</td><td>Loader + Player Model</td></tr><tr><td>An AI companion</td><td>Loader + Companion</td></tr><tr><td>Both</td><td>Loader + Player Model + Companion</td></tr></tbody></table></div>
""")
    content += '<pre aria-label="Game directory layout">game-directory/\n├─ mods/\n│  ├─ bep_3davater_loader-26.3-0.10.0.jar\n│  ├─ bep_3davater_player-26.3-0.10.0.jar\n│  └─ bep_3davater_companion-26.3-0.10.0.jar\n└─ config/\n   └─ bep_3davater_loader/\n      └─ avatars/\n         └─ your-avatar.avc</pre>'
    content += callout("tortobuなら、変換なしで試せます", "Loaderは標準モデルtortobu（作者：aoi_nasuko）を同梱し、初回起動時にavatarsへ配置します。同名の既存ファイルは上書きしません。", "Try tortobu without exporting", "Loader includes tortobu by aoi_nasuko and places it in avatars on startup. Existing files with the same name are preserved.")
    content += section("settings", "04 / IN-GAME SETTINGS", "@キーから、ゲーム内で設定する", "Open the in-game series settings", """
<p>ワールド内で<kbd>@</kbd>（JIS配列）、またはUS配列の<kbd>[</kbd>に相当する位置のキーを押します。キーはMinecraftの操作設定で変更できます。導入したMODに合わせてタブが追加されます。</p>
<div class="tab-demo" aria-label="タブ構成の例"><span class="selected">Loader</span><span>Player Model</span><span>Companion</span><span>AIモデル</span></div>
<ul><li><strong>Loader：</strong>モデルを検索・選択します。「表情・シェイプキー」は頭部をまとめた検索＋縦スクロール式です。表示を「すべて」へ変えると他のキーも表示します。</li><li><strong>表情キーJSON編集：</strong>通常・まばたき・食事・被ダメージ、任意の表情キーを編集し、検証して保存します。設定は<code>config/bep_3davater_loader/expressions/&lt;avatar-id&gt;.json</code>へ保存されます。</li><li><strong>Player Model：</strong>モデルを選んで「選択したモデルを着る」を押します。</li><li><strong>Companion：</strong>召喚済みの自分のNPCをクリックして、モデルと性格を変更します。</li><li><strong>AIモデル：</strong>Companion導入時に表示されます。接続方式・APIキー・モデルを設定し、保存してからワールドを開き直します。</li></ul>
<div class="callout"><strong>一覧で選ぶだけでは、プレイヤーの外見は変わりません</strong>プレイヤーへの着用はPlayer Modelタブから行います。NPCへの適用はCompanionタブから保存してください。詳しい手順は下の各ページへ進んでください。</div>
""", """
<p>In a world, press <kbd>@</kbd> on a JIS keyboard, or the physical key corresponding to <kbd>[</kbd> on a US keyboard. Change the binding in Minecraft's controls if necessary. Installed mods add their own tabs.</p>
<div class="tab-demo" aria-label="Example tab layout"><span class="selected">Loader</span><span>Player Model</span><span>Companion</span><span>AIモデル</span></div>
<ul><li><strong>Loader:</strong> search and select models. <strong>表情・シェイプキー</strong> (Expressions / Shape keys) groups head morphs and supports search and continuous scrolling. Use the “all” filter to show other morphs.</li><li><strong>表情キーJSON編集</strong> (Expression-key JSON): edit idle, blink, eating, damage and custom keys; validate and save. Overrides are stored in <code>config/bep_3davater_loader/expressions/&lt;avatar-id&gt;.json</code>.</li><li><strong>Player Model:</strong> select a model and click <strong>選択したモデルを着る</strong> (Wear selected model).</li><li><strong>Companion:</strong> click one of your summoned NPCs to change its model and personality.</li><li><strong>AIモデル</strong> (AI Models): appears with Companion. Choose the connection method, API key and model. Save, then reopen the world.</li></ul>
<div class="callout"><strong>Selecting a model in Loader does not equip it</strong>Wear your avatar through Player Model. Save NPC changes through Companion. Continue to the relevant page below for detailed steps. English explanations retain the Japanese button labels where the game UI uses them.</div>
""")
    content += cards()
    content += section("help", "REFERENCE", "困ったとき・クレジット", "Troubleshooting & credits", """
<details><summary>変換メニューが出ない / コンパイルエラー</summary><p>Unity Consoleの最初のエラーを確認してください。Newtonsoft.Jsonなどの依存関係、アバターに必要なSDK・シェーダー、UPM版とUnityPackage版の重複を確認します。</p></details>
<details><summary>モデルが一覧に出ない / タブが足りない</summary><p>使用中のゲームディレクトリ、.avcの拡張子、avatars直下への配置を確認して「再読込」します。Player ModelとCompanionのタブは、それぞれのMODを導入したときだけ表示されます。モデルの読込エラーは<code>logs/latest.log</code>を確認してください。</p></details>
<details><summary>衣装・表情・揺れが違う</summary><p>必要なSDK・NDMF等を含む元プロジェクトで再変換します。表情名が異なる場合はLoaderでキーを手動調整します。VRChatと同一の描画・物理・Expression Menu動作を保証するものではありません。</p></details>
<p class="rights"><strong>クレジット：</strong>同梱モデル：tortobu / 作者：aoi_nasuko。Companionの同梱性格は一人称「ぼく」でおっとりした性格です。<br><strong>ライセンス：</strong>AvatarCraft — All Rights Reserved. アバターの利用条件はそれぞれの権利者の条件に従ってください。</p>
""", """
<details><summary>No export menu / compilation errors</summary><p>Check the first error in the Unity Console. Verify Newtonsoft.Json, required avatar SDKs and shaders, and that you have not installed both the UPM and UnityPackage versions.</p></details>
<details><summary>A model or tab is missing</summary><p>Check the active game directory, .avc extension and placement directly inside avatars, then click Reload. Player Model and Companion tabs appear only when their respective mods are installed. Check <code>logs/latest.log</code> for model-loading errors.</p></details>
<details><summary>Clothing, expressions or physics look different</summary><p>Re-export from the original project with its required SDK and NDMF packages. Adjust expression keys in Loader if morph names differ. Rendering, physics and Expression Menu behavior do not exactly match VRChat.</p></details>
<p class="rights"><strong>Credits:</strong> bundled model: tortobu / author: aoi_nasuko. The bundled Companion personality is gentle and laid-back and uses “ぼく” (boku) in Japanese.<br><strong>License:</strong> AvatarCraft — All Rights Reserved. Follow the rights holder's conditions for each avatar you use.</p>
""")
    content += '<details><summary>' + pair('開発テンプレートのライセンス表記', 'Development template license notice', 'span') + '</summary><pre>' + escape((ROOT / 'LICENSE').read_text(encoding='utf-8')) + '</pre></details>'
    return content


def player_content():
    content = section("prepare", "01 / PREPARE", "LoaderとPlayer Modelを導入する", "Install Loader and Player Model", """
<p><code>bep_3davater_loader-26.3-0.10.0.jar</code>と<code>bep_3davater_player-26.3-0.10.0.jar</code>を、ゲームディレクトリの<code>mods/</code>へ置きます。</p><p>アバターは<code>config/bep_3davater_loader/avatars/</code>へ配置します。同梱のtortobuを使う場合は独自の変換は不要です。<a data-page-link href="index.html#files">データ配置の手順を見る →</a></p>
""", """
<p>Place <code>bep_3davater_loader-26.3-0.10.0.jar</code> and <code>bep_3davater_player-26.3-0.10.0.jar</code> in the game directory's <code>mods/</code> folder.</p><p>Place your avatar in <code>config/bep_3davater_loader/avatars/</code>. You can use the bundled tortobu without exporting your own model. <a data-page-link href="index.html#files">See the file placement steps →</a></p>
""")
    content += section("wear", "02 / WEAR", "モデルを着る・解除する", "Wear or remove a model", """
<ol><li>ワールド内で<kbd>@</kbd>（JIS配列。US配列では<kbd>[</kbd>の位置）を押し、<strong>Player Model</strong>タブを開きます。</li><li>検索欄からモデルを絞り込み、使いたいモデルをクリックします。</li><li><strong>選択したモデルを着る</strong>を押します。選択だけでは反映されません。</li><li>画面を閉じ、視点切り替え（標準は<kbd>F5</kbd>）で三人称の表示を確認します。</li><li>Minecraft標準の外見へ戻すときは<strong>着用を解除</strong>を押します。</li></ol>
<div class="callout"><strong>同じモデルを再出力したとき</strong>.avcを置き換えて「再読込」を押し、更新後のモデルを選んで着用し直してください。</div>
""", """
<ol><li>In a world, press <kbd>@</kbd> on a JIS keyboard (the <kbd>[</kbd> key position on US keyboards) and open <strong>Player Model</strong>.</li><li>Filter the list with the search box, then click the model you want.</li><li>Click <strong>選択したモデルを着る</strong> (Wear selected model). Selection alone does not apply it.</li><li>Close the screen and switch perspective (default <kbd>F5</kbd>) to check the third-person view.</li><li>To restore the normal Minecraft appearance, click <strong>着用を解除</strong> (Remove model).</li></ol>
<div class="callout"><strong>After exporting the same model again</strong>Replace the .avc file, click Reload, then select and wear the updated model again.</div>
""")
    content += section("expressions", "03 / EXPRESSIONS", "表情と一人称表示を調整する", "Configure expressions and first-person display", """
<p><strong>Loader</strong>タブで着用中のモデルを選び、「表情・シェイプキー」を開きます。頭部のシェイプキーを検索し、各スライダーの重みを調整して保存します。「表情キーJSON編集」では任意キーも直接編集できます。</p>
<div class="table-wrap"><table><thead><tr><th>表情キー</th><th>使われる場面</th></tr></thead><tbody><tr><td><code>idle</code></td><td>通常の表情</td></tr><tr><td><code>blink</code></td><td>まばたき（初期値2〜4秒ごと）</td></tr><tr><td><code>eat</code></td><td>食事・飲み物の使用</td></tr><tr><td><code>hurt</code></td><td>被ダメージ</td></tr></tbody></table></div>
<p>イベントの判定はPlayer Model、キーの表情再生はLoaderが担当します。重なった場合は被ダメージが優先です。設定はモデル別のJSONへ保存され、.avcを直接書き換えません。</p><p>一人称の腕・手・指・袖も同じ.avcから読み込みます。腕だけの追加変換は不要です。一人称ではニュートラル姿勢と変換時の既定BlendShapeを使い、袖のPhysBoneやアイテム別の指の握り込みは行いません。</p>
""", """
<p>Select the worn model in <strong>Loader</strong> and open <strong>表情・シェイプキー</strong> (Expressions / Shape keys). Search head morphs, adjust their weight sliders and save. Use <strong>表情キーJSON編集</strong> to edit custom keys directly.</p>
<div class="table-wrap"><table><thead><tr><th>Expression key</th><th>When it is used</th></tr></thead><tbody><tr><td><code>idle</code></td><td>Default expression</td></tr><tr><td><code>blink</code></td><td>Blinking (every 2–4 seconds by default)</td></tr><tr><td><code>eat</code></td><td>Eating and drinking</td></tr><tr><td><code>hurt</code></td><td>Taking damage</td></tr></tbody></table></div>
<p>Player Model detects events and Loader plays the corresponding expression keys. Damage takes priority when events overlap. Settings are stored in a separate per-model JSON file without rewriting the .avc.</p><p>First-person arms, hands, fingers and sleeves use the same .avc. No separate arm export is needed. First person uses a neutral pose and exported default BlendShapes; sleeve PhysBones and item-specific finger grips are not simulated.</p>
""")
    content += section("multiplayer", "04 / MULTIPLAYER", "他のプレイヤーにも表示する", "Show your avatar to other players", """
<ol><li>サーバーと各クライアントへ、同じ版のLoader＋Player Modelを導入します。</li><li>専用サーバーは<code>online-mode=true</code>を使用します。オフライン認証や認証を無効にしたプロキシ構成には対応しません。</li><li>自分のローカルモデルを着用すると、選択中のモデルと表情設定が表示用に自動同期されます。未選択モデルはまとめて送信しません。</li><li>相手側は受信・検証の完了後にアバターを表示します。受信中や上限超過の場合は通常のMinecraftスキンを表示します。</li></ol>
<p>受信データは他人が着用するためのモデル一覧には追加されません。モデル変更・退出・切断時には該当する表示キャッシュを破棄します。同時表示は96ブロック以内の近い4人が対象です。</p>
<div class="callout"><strong>NPCの追加モデルは各クライアントへ配置</strong>プレイヤーの表示用同期をNPCへ流用しません。NPC用の.avcは、表示する各クライアントが用意します。暗号化した表示配信も、モデルごとの利用条件を変更するものではなく、改造クライアントからの抽出を完全に防ぐものでもありません。</div>
""", """
<ol><li>Install matching versions of Loader and Player Model on the server and each client.</li><li>Dedicated servers must use <code>online-mode=true</code>. Offline authentication and proxy setups that disable backend authentication are not supported.</li><li>Wear a local model. The selected model and expression settings are automatically synchronized for display. Unselected models are not uploaded together.</li><li>Other clients show the avatar after receiving and validating it. They use the normal Minecraft skin while loading or if resource limits are exceeded.</li></ol>
<p>Received data is not added to another player's wearable model list. Display caches are discarded on model changes, departure or disconnection. Up to four nearby players within 96 blocks use custom display at once.</p>
<div class="callout"><strong>Place additional NPC models on each client</strong>Player display synchronization is not used for NPC models. Each client displaying an NPC needs its .avc. Encrypted display distribution does not change an avatar's license and cannot completely prevent extraction by modified clients.</div>
""")
    content += section("help", "CHECKLIST", "うまく表示されないとき", "Troubleshooting", """
<ul><li><strong>Player Modelタブがない：</strong>LoaderだけでなくPlayer ModelのJARも導入し、ゲームを再起動します。</li><li><strong>モデルを選んでも変わらない：</strong>Player Modelで「選択したモデルを着る」を押してください。</li><li><strong>表情が変わらない：</strong>Loaderで対象モデルのシェイプキー名と重みを確認し、保存します。</li><li><strong>他人から通常スキンに見える：</strong>双方のMOD、サーバーの認証設定、同期状態、モデルの容量を確認します。</li><li><strong>服や腕の位置が違う：</strong>元のUnityプロジェクトでHumanoid・モーション・素材を確認して再出力します。防具やエリトラの見た目は合成しません。</li></ul>
""", """
<ul><li><strong>No Player Model tab:</strong> install the Player Model JAR as well as Loader, then restart the game.</li><li><strong>Selection does nothing:</strong> click Wear selected model in Player Model.</li><li><strong>Expressions do not change:</strong> check the model's morph names and weights in Loader, then save.</li><li><strong>Others see a normal skin:</strong> check both sides' mods, server authentication, synchronization status and model size.</li><li><strong>Clothes or arms are misplaced:</strong> check Humanoid settings, motions and materials in Unity and export again. Armor and elytra visuals are not combined with the avatar.</li></ul>
""")
    return content + cards()


def npc_content():
    content = section("prepare", "01 / PREPARE", "LoaderとCompanionを導入する", "Install Loader and Companion", """
<p><code>bep_3davater_loader-26.3-0.10.0.jar</code>と<code>bep_3davater_companion-26.3-0.10.0.jar</code>を、NPCを使うクライアントとサーバーの<code>mods/</code>へ配置します。Player Modelは、プレイヤーの外見も変える場合に追加します。</p><p>標準モデルtortobuはLoader、性格サンプルはCompanionに同梱しています。追加の.avcは表示する各クライアントの<code>config/bep_3davater_loader/avatars/</code>へ置きます。</p>
""", """
<p>Place <code>bep_3davater_loader-26.3-0.10.0.jar</code> and <code>bep_3davater_companion-26.3-0.10.0.jar</code> in <code>mods/</code> on each client and server using NPCs. Add Player Model if you also want to change player appearances.</p><p>Loader includes tortobu; Companion includes its sample personality. Place additional .avc files in <code>config/bep_3davater_loader/avatars/</code> on every client that displays them.</p>
""")
    content += section("ai", "02 / AI CONNECTION", "AIモデルへ接続する", "Connect an AI model", """
<ol><li><kbd>@</kbd>（US配列は<kbd>[</kbd>の位置）で設定を開き、<strong>AIモデル</strong>タブを選びます。</li><li>「接続方法」をクリックして方式を切り替え、AIを有効にします。</li><li>API方式では接続先URLとAPIキーを入力します。URLは通常<code>/v1</code>までで、<code>/chat/completions</code>や<code>/messages</code>は付けません。</li><li><strong>通常モデルを一覧から選択</strong>で取得・検索・選択します。モデルIDを直接入力することもできます。</li><li>必要なら「計画用モデルID」を指定します。空欄なら通常モデルを使います。</li><li><strong>設定を保存</strong>を押し、ワールドを開き直します。専用サーバーはサーバー側で設定を変更して再起動します。</li></ol>
<div class="table-wrap"><table><thead><tr><th>接続方法</th><th>URL・認証</th></tr></thead><tbody><tr><td>Codex / ChatGPT OAuth</td><td>ホスト環境のCodex CLIログインを使用。APIキー入力不要。</td></tr><tr><td>OpenAI API</td><td><code>https://api.openai.com/v1</code> ＋ APIキー</td></tr><tr><td>Anthropic API</td><td><code>https://api.anthropic.com/v1</code> ＋ APIキー</td></tr><tr><td>LM Studio</td><td><code>http://localhost:1234/v1</code>。先にサーバーとモデルを起動。</td></tr><tr><td>Ollama</td><td><code>http://localhost:11434/v1</code>。先にサーバーとモデルを用意。</td></tr><tr><td>OpenAI互換 API / URL指定</td><td>対応サーバーのURL・必要なキー・モデルIDを指定。</td></tr></tbody></table></div>
<div class="callout"><strong>編集するのは、このPCのAI設定です</strong>シングルプレイではこのPCがAIを実行します。専用サーバーへ接続中にこのタブを編集しても、リモートサーバーのAI設定は変わりません。サーバーホスト側の<code>config/bep_3davater_companion-ai.toml</code>を設定してください。</div>
<details><summary>APIキー・ローカルモデルの注意点</summary><p>入力キーは伏せ字表示ですが、ホストのTOMLへは平文保存されます。キー入り設定ファイルを配布しないでください。環境変数名を指定した場合、そこに値があると入力キーより優先します。認証不要のローカルサーバーではキーと環境変数名を空欄にできます。会話・関数ツール呼び出し・JSON出力に対応するモデルを選んでください。MODはモデルを自動ダウンロード・起動しません。一覧取得に非対応のAPIではモデルIDを直接入力します。</p></details>
<details><summary>Codex / ChatGPT OAuthの準備</summary><p>AIを実行するホスト環境へCodex CLIを用意し、<code>codex login</code>でログインしてください。<code>codex login status</code>で確認できます。PATHから見つからない場合はタブの「Codex CLI」欄へ実行ファイルのパスを入力します。モデルIDが空欄ならCLIの既定を使います。確認したCLIは0.163.0です。</p></details>
""", """
<ol><li>Open settings with <kbd>@</kbd> (the <kbd>[</kbd> position on a US keyboard) and select <strong>AIモデル</strong> (AI Models).</li><li>Click the connection-method button to cycle through methods and enable AI.</li><li>For API connections, enter the base URL and API key. Normally the URL ends at <code>/v1</code>; do not append <code>/chat/completions</code> or <code>/messages</code>.</li><li>Click <strong>通常モデルを一覧から選択</strong> to fetch, search and choose a normal model. You can also type the model ID directly.</li><li>Optionally specify a planning model. Leave it blank to use the normal model.</li><li>Click <strong>設定を保存</strong> (Save settings), then reopen the world. For a dedicated server, edit settings on the server host and restart it.</li></ol>
<div class="table-wrap"><table><thead><tr><th>Connection method</th><th>URL & authentication</th></tr></thead><tbody><tr><td>Codex / ChatGPT OAuth</td><td>Uses the host's Codex CLI login. No API key input required.</td></tr><tr><td>OpenAI API</td><td><code>https://api.openai.com/v1</code> + API key</td></tr><tr><td>Anthropic API</td><td><code>https://api.anthropic.com/v1</code> + API key</td></tr><tr><td>LM Studio</td><td><code>http://localhost:1234/v1</code>. Start the server and load a model first.</td></tr><tr><td>Ollama</td><td><code>http://localhost:11434/v1</code>. Prepare the server and model first.</td></tr><tr><td>Custom OpenAI-compatible API</td><td>Set the compatible server's URL, required key and model ID.</td></tr></tbody></table></div>
<div class="callout"><strong>This screen edits the AI settings on this PC</strong>In single player, this PC runs the AI. Editing this tab while connected to a dedicated server does not change that server's settings. Configure <code>config/bep_3davater_companion-ai.toml</code> on the server host.</div>
<details><summary>API keys and local models</summary><p>Keys are masked in the UI but stored in plaintext in the host's TOML. Do not distribute a populated config. If the named environment variable contains a value, it takes precedence over the entered key. Leave both fields blank for local servers without authentication. Choose a model supporting conversation, function tools and JSON output. The mod does not download or start models automatically. Enter the model ID manually if the endpoint does not provide a model list.</p></details>
<details><summary>Prepare Codex / ChatGPT OAuth</summary><p>Install Codex CLI on the AI host and sign in with <code>codex login</code>. Check it with <code>codex login status</code>. If it is not on PATH, set the executable path in the Codex CLI field. A blank normal model ID uses the CLI default. The checked CLI version is 0.163.0.</p></details>
""")
    content += section("summon", "03 / SUMMON", "ソウルストーンでNPCを召喚する", "Summon an NPC with a Soulstone", """
<p>作業台で鉄インゴット4個（I）と金インゴット5個（G）を次の配置にすると、ソウルストーンを1個作れます。</p>
<div class="recipe" role="img" aria-label="鉄 金 鉄、金 金 金、鉄 金 鉄"><span>I</span><span class="gold">G</span><span>I</span><span class="gold">G</span><span class="gold">G</span><span class="gold">G</span><span>I</span><span class="gold">G</span><span>I</span></div>
<ol><li>ソウルストーンを手に持ち、空きスペースのある地面へ使用します。</li><li>標準のtortobuが召喚されます。通常はストーンを1個消費します。</li><li>召喚したプレイヤーが所有者になります。既定の上限は1人3体です。</li></ol>
<p>同梱の性格は、一人称「ぼく」でおっとりした仲間です。モデルと性格は召喚後に変更できます。</p>
""", """
<p>Craft one Soulstone using four iron ingots (I) and five gold ingots (G) in this arrangement:</p>
<div class="recipe" role="img" aria-label="Iron Gold Iron; Gold Gold Gold; Iron Gold Iron"><span>I</span><span class="gold">G</span><span>I</span><span class="gold">G</span><span class="gold">G</span><span class="gold">G</span><span>I</span><span class="gold">G</span><span>I</span></div>
<ol><li>Hold the Soulstone and use it on ground with room for the NPC.</li><li>The default tortobu is summoned. Normally one Soulstone is consumed.</li><li>The summoning player becomes its owner. The default limit is three companions per player.</li></ol>
<p>The bundled personality is gentle and laid-back and uses “ぼく” (boku) in Japanese. You can change its model and personality after summoning.</p>
""")
    content += section("customize", "04 / CUSTOMIZE", "モデルと性格を変更する", "Change a companion's model and personality", """
<ol><li><kbd>@</kbd>から<strong>Companion</strong>タブを開きます。自分が召喚しているNPCが一覧に表示されます。</li><li>変更したいNPCをクリックし、左のモデル一覧から外見を選びます。</li><li>右の性格ボタンでプリセットを切り替えるか、性格JSONを編集します。</li><li><strong>モデル・性格を保存</strong>で対象のNPCへ反映します。未読込のNPCは、読み込まれてから再試行してください。</li></ol>
<p>独自の性格は、配布物の<code>Examples/Personalities/tortobu.json</code>をコピーして作れます。<code>id</code>とファイル名を一致させ、ホスト側の<code>config/bep_3davater_companion/personalities/&lt;id&gt;.json</code>へ置きます。1ファイル16KiB以下です。<code>name</code>は名前、<code>avatar</code>はモデルID、<code>instructions</code>は性格の説明、<code>speakingStyle</code>は口調です。</p><p>表情はLoaderで対象モデルの<code>eat</code>・<code>hurt</code>などを設定します。食事・被ダメージの判定はCompanionが行います。</p>
""", """
<ol><li>Open <strong>Companion</strong> from the <kbd>@</kbd> settings. The list shows NPCs you own.</li><li>Click the target NPC and choose its appearance from the model list on the left.</li><li>Use the personality button to cycle presets, or edit the personality JSON on the right.</li><li>Click <strong>モデル・性格を保存</strong> (Save model and personality). For an unloaded NPC, retry after it has loaded.</li></ol>
<p>To create a custom personality, copy <code>Examples/Personalities/tortobu.json</code> from the release. Match the filename to its <code>id</code> and place it on the host in <code>config/bep_3davater_companion/personalities/&lt;id&gt;.json</code>. Keep each file at or below 16 KiB. <code>name</code> controls the name, <code>avatar</code> references the model ID, <code>instructions</code> describes the personality, and <code>speakingStyle</code> sets its manner of speech.</p><p>Configure <code>eat</code>, <code>hurt</code> and other expression keys for that model in Loader. Companion detects eating and damage events.</p>
""")
    content += section("tasks", "05 / PLAY TOGETHER", "持ち物・会話・作業指示", "Inventory, conversation and tasks", """
<p>所有するNPCを右クリックすると、右端のアイコンから4つのメニューを選べます。アイコンにカーソルを重ねると説明が表示されます。</p>
<div class="table-wrap"><table><thead><tr><th>メニュー</th><th>操作</th></tr></thead><tbody><tr><td>持ち物</td><td>上にNPC、下にプレイヤーの持ち物。クリック・Shiftクリックで受け渡し。防具・主手・副手も設定。</td></tr><tr><td>性格・外見</td><td>性格JSON・モデル・プリセットを編集し「保存・実行」。</td></tr><tr><td>作業指示</td><td>指示を入力し「保存・実行」。「作業停止」で停止。「追従: ON/OFF」で待機中の追従を切り替え。</td></tr><tr><td>記録</td><td>会話・指示・作業の結果を閲覧。「過去の記録」で以前の履歴。</td></tr></tbody></table></div>
<h3>最初は、範囲と数を具体的に</h3><ul><li>「原木を16個集めて、このチェストに入れて」</li><li>「ついてきて」「ここで待って」</li><li>「座標100,65,100から104,67,104までの石を掘って。床はY64に残して」</li></ul>
<p>同じ次元・12ブロック以内で視線の通るNPCには、通常チャットでも話しかけられます。名前を入れると対象を選びやすくなります。所有者の依頼だけが作業指示を変更します。道具・食料・材料を持たせ、収納先の空きも用意してください。実際のアイテム・耐久・権限に従って作業します。</p>
<div class="callout"><strong>行き詰まったら、記録を確認</strong>移動先・資材・道具・許可・収納先が足りないと作業が保留になることがあります。足りない条件を整えて、範囲や数量を指定し直してください。新規ワールドからドラゴン討伐までの完全自律完走は未検証です。</div>
""", """
<p>Right-click an NPC you own. The icons on the right select four menus; hover over an icon for its description.</p>
<div class="table-wrap"><table><thead><tr><th>Menu</th><th>Controls</th></tr></thead><tbody><tr><td>Inventory</td><td>NPC slots above, player slots below. Click or Shift-click to transfer items. Set armor, main hand and off hand.</td></tr><tr><td>Personality & appearance</td><td>Edit personality JSON, model or preset; click 保存・実行 (Save / Run).</td></tr><tr><td>Task instruction</td><td>Enter a task and click 保存・実行. 作業停止 stops work. 追従: ON/OFF toggles following while idle.</td></tr><tr><td>Journal</td><td>Read conversations, instructions and results. 過去の記録 opens older records.</td></tr></tbody></table></div>
<h3>Start with a clear area and quantity</h3><ul><li>“Collect 16 logs and put them in this chest.”</li><li>“Follow me.” / “Wait here.”</li><li>“Dig the stone from 100,65,100 to 104,67,104. Leave the floor at Y64.”</li></ul>
<p>You can also use ordinary chat with a visible NPC in the same dimension within 12 blocks. Include its name to address it. Only the owner's requests change its task. Supply tools, food and materials, and leave space in the destination chest. Work uses real items, durability and permissions.</p>
<div class="callout"><strong>Check the journal when a task is blocked</strong>Missing paths, materials, tools, permissions or storage can suspend a task. Resolve the missing condition and give a clearer area or quantity. A fully autonomous run from a new world through defeating the Ender Dragon has not been verified.</div>
""")
    content += section("journal", "06 / JOURNAL", "記録帳と60分の統計", "Journal and 60-minute statistics", """
<p>アバターの記録帳を手に持って使用します。左右の矢印で自分のNPCを切り替え、上部のタブを選びます。一覧はマウスホイールまたは右のスライダーで移動できます。</p>
<ul><li><strong>直近の会話：</strong>会話・プレイヤーの発言・死亡記録・死亡時のセリフだけを新しい順に最大100件表示します。</li><li><strong>直近のログ：</strong>すべての種類の行動記録を新しい順に最大100件表示します。</li><li><strong>60分の統計：</strong>実時間の直近60分を1分ずつ60行で表示します。全体の合計と命令別の実行・失敗数も確認できます。</li></ul>
<div class="table-wrap"><table><thead><tr><th>統計</th><th>数えるタイミング</th></tr></thead><tbody><tr><td>計画</td><td>AIへプランニングを依頼したとき。</td></tr><tr><td>実行</td><td>ローカル実行器が操作命令を開始したとき。</td></tr><tr><td>失敗</td><td>その操作が失敗結果を返したとき。</td></tr><tr><td>リトライ</td><td>失敗後に代わりの操作を実行、またはプランニングを再試行したとき。</td></tr><tr><td>上位AI</td><td>ローカルでの再試行を経て、上位の復旧プランニングを実際に依頼したとき。通常の上位計画は含みません。</td></tr></tbody></table></div>
<p><strong>行動ログをエクスポート</strong>を押すと、選択したNPCの直近60分をZIPへ保存します。画面の100件制限は適用せず、全文の<code>actions.jsonl</code>、60行の<code>statistics.csv</code>、集計と範囲を記載した<code>summary.json</code>を含めます。保存先はゲームディレクトリの<code>exports/bep_3davater_companion/</code>です。専用サーバーでも所有者の記録だけを分割転送し、自分のPCへ保存します。</p>
<p>更新前に専用イベントがなかったリトライ・上位AIへの引継ぎは、更新後の記録から集計します。1回の出力上限はZIP 64MiB・圧縮前の行動ログ256MiBです。超過した場合は失敗を表示し、途中までの出力を完成扱いにしません。</p>
<div class="callout"><strong>オーナーが不在の間は発言しません</strong>オーナーがサーバーに接続していないNPCは、定期発言・他NPCとの会話・会話への返答・死亡時の発言のAI呼び出しを行いません。ログアウト中に到着した返答も表示しません。死亡そのものの記録は残ります。</div>
""", """
<p>Hold and use an Avatar Journal. Switch between your NPCs with the arrows and select a tab. Scroll with the mouse wheel or the scrollbar on the right.</p>
<ul><li><strong>直近の会話 (Recent conversation):</strong> shows up to 100 recent NPC/player conversation entries, death records and death speech, newest first.</li><li><strong>直近のログ (Recent logs):</strong> shows up to 100 entries of every event type, newest first.</li><li><strong>60分の統計 (60-minute statistics):</strong> shows the latest 60 real-time minutes in 60 one-minute rows, plus totals and per-command execution/failure counts.</li></ul>
<div class="table-wrap"><table><thead><tr><th>Metric</th><th>When it is counted</th></tr></thead><tbody><tr><td>Planning</td><td>A planning request is sent to AI.</td></tr><tr><td>Executions</td><td>The local executor starts a command.</td></tr><tr><td>Failures</td><td>A command returns a failure result.</td></tr><tr><td>Retries</td><td>An alternative action is started after failure, or planning is retried.</td></tr><tr><td>AI handoffs</td><td>An advanced recovery-planning request is actually sent after local retries. Ordinary advanced planning is excluded.</td></tr></tbody></table></div>
<p>Click <strong>行動ログをエクスポート (Export action logs)</strong> to save the selected NPC's latest 60 minutes as a ZIP. It includes full <code>actions.jsonl</code> records beyond the UI's 100-entry limit, <code>statistics.csv</code> with 60 minute rows, and <code>summary.json</code> with totals and the time range. Files are saved under the game directory's <code>exports/bep_3davater_companion/</code>. On dedicated servers, only your own records are transferred in chunks and saved on your PC.</p>
<p>Retry and handoff events that were not recorded before this update are counted from the new records onward. Each export is limited to 64 MiB compressed and 256 MiB of raw action logs. Exceeding a limit produces an error instead of a silently incomplete export.</p>
<div class="callout"><strong>No speech while the owner is absent</strong>When an owner is not connected to the server, their NPC does not request AI speech for periodic remarks, conversations with NPCs, chat replies or death notifications. Replies arriving after logout are not displayed. The death event itself is still recorded.</div>
""")
    content += callout("死亡地点はオーナーへの重要通知へ", "死亡時の次元とX・Y・Z座標をAIへ渡し、NPCの性格に沿ったセリフでオーナーだけへ通知します。復帰後の現在位置と混同せず、死亡地点を記録に残します。AIへ接続できない場合も、座標付きの代替文で通知します。", "Death locations are important owner notifications", "The AI receives the captured dimension and X/Y/Z coordinates and speaks in the NPC's personality. Only the owner receives this important notification. The original death location is retained even after respawn; connection failures use a fallback message that still includes the coordinates.")
    content += section("recall", "07 / MANAGE", "呼び戻し・記録・サーバー設定", "Recall, records and server settings", """
<ul><li><strong>呼び戻しの地図：</strong>作業台で空の地図を鉄インゴット8個で囲みます。手に持って使用し、自分のNPCを選ぶと近くへ呼び戻します。別次元にも対応し、地図は消費しません。呼び戻すと作業と追従は解除されます。</li><li><strong>アバターの記録帳：</strong>本1冊を鉄インゴット8個で囲みます。手に持って使用すると自分のNPCの保存済み記録を確認できます。</li><li><strong>アバターアンカー：</strong>板材1個を鉄インゴット8個で囲みます。設置して右クリックし、場所IDを保存します。「拠点の倉庫へ行って」のような指示に使えます。近くのチェストはアンカーから8ブロック以内へ置きます。</li><li><strong>倒れたとき：</strong>既定では所持品を落とし、所有者のリスポーン地点へ復帰して待機します。作業は自動再開しません。</li></ul>
<p>召喚上限と死亡時の扱いは、ホスト側の<code>config/bep_3davater_companion-server.toml</code>に保存します。<code>maxPerPlayer = 3</code>が既定、<code>-1</code>は無制限、<code>0</code>は新規召喚禁止です。<code>dropItemsOnDeath = false</code>で持ち物を保持して復帰します。</p><p>NPCの状態はワールドへ保存されます。会話・作業の履歴はワールド内の<code>bep_3davater_companion/journal/</code>へ記録されます。バックアップはワールド全体と必要なconfigを含めてください。</p>
""", """
<ul><li><strong>Recall Map:</strong> surround an empty map with eight iron ingots. Hold and use it, then choose one of your NPCs to bring it nearby, including from other dimensions. The map is not consumed. Recall clears its task and following state.</li><li><strong>Avatar Journal:</strong> surround a book with eight iron ingots. Hold and use it to read saved records for your NPCs.</li><li><strong>Avatar Anchor:</strong> surround a plank with eight iron ingots. Place it, right-click and save a location ID. Use that name in instructions such as “Go to the base storage.” Keep related chests within eight blocks of the anchor.</li><li><strong>When defeated:</strong> by default the NPC drops its items, returns to the owner's respawn location and waits. Work does not restart automatically.</li></ul>
<p>Host-side limits are in <code>config/bep_3davater_companion-server.toml</code>. The default is <code>maxPerPlayer = 3</code>; <code>-1</code> means unlimited and <code>0</code> prevents new summons. Use <code>dropItemsOnDeath = false</code> to keep items on recovery.</p><p>NPC state is saved in the world. Conversation and task history is stored under the world's <code>bep_3davater_companion/journal/</code>. Include the entire world and required config files in backups.</p>
""")
    content += section("help", "CHECKLIST", "接続・動作の確認", "Troubleshooting", """
<ul><li><strong>AIモデルタブがない：</strong>Companionの導入を確認し、再起動します。</li><li><strong>認証できない：</strong>ホスト側の接続方法・URL・キー・環境変数を確認します。Codex方式はホスト側のログイン状態も確認します。</li><li><strong>モデル一覧が出ない：</strong>先にローカルサーバーとモデルを起動します。非対応APIではIDを直接入力できます。</li><li><strong>保存したのに動かない：</strong>ワールド／サーバーを開き直します。リモートサーバーはホスト側を設定してください。</li><li><strong>NPCを編集できない：</strong>自分のNPCであること、サーバーに読み込まれていることを確認します。必要なら呼び戻しの地図を使います。</li><li><strong>モデルが見えない：</strong>表示するクライアントに同じ.avcを配置し、モデルIDを確認して再読込します。</li></ul>
""", """
<ul><li><strong>No AI Models tab:</strong> check that Companion is installed, then restart.</li><li><strong>Authentication fails:</strong> check the host's method, URL, key and environment variable. For Codex, check the host's CLI login.</li><li><strong>No model list:</strong> start your local server and load its model first. Enter the ID manually if the API does not support listing.</li><li><strong>Saved but no change:</strong> reopen the world or restart the server. Configure remote servers on the server host.</li><li><strong>Cannot edit an NPC:</strong> confirm you own it and that it is loaded on the server. Use a Recall Map if necessary.</li><li><strong>Model is missing:</strong> place the same .avc on each displaying client, check the model ID and reload.</li></ul>
""")
    return content + cards()


def render(name, body):
    page = PAGES[name]
    links = []
    for key, icon in (("index", "01"), ("player", "02"), ("npc", "03")):
        current = ' aria-current="page"' if key == name else ""
        links.append(f'<a data-page-link href="{key}.html"{current}><span class="nav-icon" aria-hidden="true">{icon}</span>{pair(PAGES[key]["ja"], PAGES[key]["en"], "span")}</a>')
        if key == name:
            links.append('<div class="page-toc">' + ''.join(f'<a href="#{anchor}">{pair(ja, en, "span")}</a>' for anchor, ja, en in page["toc"]) + '</div>')
    return f'''<!doctype html>
<html lang="ja" data-language="ja"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="description" content="B.E.P 3DAvater — Japanese / English offline guide for Unity export, player avatars and AI companions.">
<title>{escape(page['ja'])} | B.E.P 3DAvater</title><link rel="stylesheet" href="assets/guide.css"><script src="assets/guide.js" defer></script></head>
<body data-title-ja="{escape(page['ja'])}" data-title-en="{escape(page['en'])}">
<a class="skip" href="#main">本文へ / Skip to content</a>
<aside class="sidebar"><a class="brand" data-page-link href="index.html"><span class="brandmark" aria-hidden="true">B</span><span><strong>B.E.P 3DAvater</strong><small>USER GUIDE</small></span></a>
{pair('ガイド', 'DOCUMENTATION', cls='nav-label')}<nav class="main-nav" aria-label="Guide navigation">{''.join(links)}</nav>
<div class="sidebar-foot"><strong>26.3-0.10.0</strong><br>Minecraft 26.3 · NeoForge<br>{pair('オフライン対応 / 日英ガイド', 'Offline-ready · JA / EN', 'span')}</div></aside>
<div class="shell"><header class="topbar"><div class="breadcrumb">USER GUIDE <b>{pair(page['ja'], page['en'], 'span')}</b></div><div class="language" role="group" aria-label="Language / 言語"><button type="button" data-language-button="ja" aria-pressed="true">日本語</button><button type="button" data-language-button="en" aria-pressed="false">English</button></div></header>
<noscript><p class="noscript">言語切り替えにはJavaScriptを有効にしてください。 / Enable JavaScript to switch languages.</p></noscript>
<main class="content" id="main"><div class="hero"><div class="eyebrow">{page['eyebrow']}</div>{pair(page['subtitle_ja'], page['subtitle_en'], 'h1')}{pair(page['lead_ja'], page['lead_en'], 'p', 'lead')}<div class="version-badges"><span class="badge green">26.3-0.10.0</span><span class="badge">Minecraft 26.3</span><span class="badge">NeoForge 26.3.0.95</span><span class="badge">Java 25</span><span class="badge">Unity 2022.3</span></div></div>
{body}<footer><span>B.E.P 3DAvater · User Guide</span><span>tortobu by aoi_nasuko · All Rights Reserved.</span></footer></main></div></body></html>'''


def main():
    DEST.mkdir(exist_ok=True)
    for name, content in (("index", main_content()), ("player", player_content()), ("npc", npc_content())):
        (DEST / f"{name}.html").write_text(render(name, content), encoding="utf-8")
    print("Generated index.html, player.html and npc.html (Japanese / English)")


if __name__ == "__main__":
    main()

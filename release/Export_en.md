# B.E.P 3DAvater Exporter — Export guide

B.E.P 3DAvater Exporter converts a Humanoid avatar from its Unity project into an `.avc` archive for Minecraft. The tool includes a Japanese/English language selector, motion previews, editable motion profiles, material conversion and secondary-motion settings.

## Requirements

- Unity 2022.3. The exporter was checked with Unity 2022.3.22f1.
- An avatar root with a valid **Humanoid Animator**. Use the avatar's original Unity project, with the SDK, shaders and other packages required by that avatar.
- Install Modular Avatar and NDMF in that project if the avatar uses them.
- For Minecraft: Java 25, Minecraft Java Edition 26.3 and NeoForge 26.3.0.95.

The exporter does not download avatars or include the VRChat SDK. Use avatars you have permission to convert and use. The Minecraft Loader already includes the authorized tortobu sample, so you can try the series without exporting another model.

## 1. Install the exporter

1. Extract the release ZIP.
2. In Unity, open **Window > Package Manager**.
3. Choose **+ > Add package from tarball...** and select `Unity/dev.avatarcraft.exporter-0.10.0.tgz`.
4. Wait for package resolution and script compilation to finish. The package is displayed as **B.E.P 3DAvater Exporter**.

For repository development, use **Add package from disk...** and select `unity/Packages/dev.avatarcraft.exporter/package.json` instead. Use one installation method for the package in a project.

## 2. Open the tool and select English

1. Select the avatar root in the Hierarchy. It must have a Humanoid Animator.
2. Open **Tools > B.E.P 3DAvater Exporter > Avatar export and motions**.
3. Set **Language / 言語** to **English** at the top of the window.
4. Confirm that **Avatar root** points to the avatar you want to export. You can also drag the root into this field.

The language selection is saved in the Unity Editor preferences. Switching languages changes labels, motion names, help text and dialogs without changing the avatar or its motion assignments. Choose **日本語** to return to Japanese.

## 3. Configure and preview motions

1. Use **Motion profile** to select an existing profile, or start with the generated defaults.
2. For each action, choose a **Built-in motion**. The library contains 34 actions and 39 motion clips.
3. Optionally assign a **Custom clip (optional)** to replace that action's built-in motion.
4. Adjust **Playback speed** and **Blend duration (seconds)** as needed.
5. Click **Preview** beside an action. Use **Play** or **Playback position** under the preview to inspect it.
6. Click **Save profile** and save the profile asset inside the project's `Assets` folder.

**Generate editable .anim clips** creates the built-in motion clips inside an `Assets` subfolder. You can edit those clips and assign them as custom clips. Generating clips does not automatically replace the assignments in the profile.

## 4. Export an `.avc` archive

1. Enable **Apply Modular Avatar / NDMF to an isolated clone** when your avatar requires Modular Avatar processing. NDMF must be available in the source project.
2. Click **Export .avc with this profile**.
3. Select the destination and filename.
4. Check the export result and any warnings. The original scene avatar is kept intact; NDMF processing can leave generated temporary assets in the Unity project.

The archive includes the mesh, textures, Humanoid references, BlendShapes, supported material settings and motion data. Re-export after changing the source avatar or its export profile.

## 5. Load and use the model in Minecraft

1. Install `bep_3davater_loader-26.3-0.10.0.jar` in the game's `mods` folder.
2. Add `bep_3davater_player-26.3-0.10.0.jar` for a player avatar, `bep_3davater_companion-26.3-0.10.0.jar` for AI companions, or both.
3. Place your `.avc` file in `config/bep_3davater_loader/avatars/`.
4. Open a world and press **@ on a JIS keyboard**, or the **[ physical key on a US keyboard**. You can change this binding in Minecraft's controls.
5. In the **Loader** tab, reload and browse the models. Select a model to edit its expression keys. Browsing does not equip it.
6. In **Player Model**, select a model and click **選択したモデルを着る** (Wear selected model). Use **着用を解除** to restore the standard player appearance.
7. In **Companion**, choose one of your summoned NPCs, select a model, edit or choose a personality and click **モデル・性格を保存** (Save model and personality).

Only tabs for installed mods appear. Companion settings are checked against the authenticated owner on the server. Model files must be available on each client that displays a companion.

## Expressions

In **Loader**, select a model and open **表情・シェイプキー** (Expressions / Shape keys). The list starts with head-related morphs and provides a continuous scrollbar, mouse-wheel scrolling and a search box. Choose **表示: すべて** to include the other morphs. Head grouping is based on the bones influencing the changed vertices, so facial morphs can be grouped even when they share a mesh with the body.

Use **表情キーJSON編集** to edit the complete expression-key JSON, including custom keys. Saving validates the JSON, morph names, weights and blink timing. Canceling does not save changes or equip the model.

The standard keys are `idle`, `blink`, `eat` and `hurt`. Player Model and Companion choose `eat` or `hurt` for their own game events; Loader maps the selected keys to morph weights. Expression settings are saved separately in `config/bep_3davater_loader/expressions/<avatar-id>.json`.

## Supported conversion and limits

- Materials approximate supported lilToon settings; Unity shader bytecode is not executed in Minecraft.
- PhysBone behavior uses an approximate solver rather than VRChat's private implementation.
- VRChat Animator FX, Expression Menus, contacts, runtime scripts and grabbing interactions are not executed in Minecraft.
- First-person arm replacement belongs to Player Model. It uses the same `.avc` archive; no separate arm export is required.

If the menu is missing, check package installation and Unity Console compilation errors. If a model is missing in Minecraft, check the game directory, `.avc` extension and Loader's reload button.

## Configure AI connections

With Companion installed, the series settings also include **AIモデル** (AI Models). Select Codex OAuth, OpenAI API, Anthropic API, LM Studio, Ollama or a custom OpenAI-compatible endpoint. Enter the base URL and API key where required. Keys are masked in the UI. An optional environment variable takes precedence when it contains a value.

Use the model-list buttons to fetch and search the chosen provider's models, or enter model IDs manually. The advanced planning model is optional. Click **設定を保存** (Save settings), then reopen the world to apply the connection. This edits the local host's config; it does not change the settings of a remote dedicated server. Keys saved directly are stored in the local config file, so do not redistribute a populated file. Codex OAuth continues to use the CLI-managed login.

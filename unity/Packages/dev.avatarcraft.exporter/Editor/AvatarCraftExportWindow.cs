using System;
using System.IO;
using System.Linq;
using UnityEditor;
using UnityEngine;
using static AvatarCraft.Editor.ExporterLanguage;

namespace AvatarCraft.Editor
{
    public sealed class AvatarCraftExportWindow : EditorWindow
    {
        GameObject avatar, previewAvatar;
        AvatarCraftMotionProfile profile;
        PreviewRenderUtility preview;
        AnimationClip previewClip;
        Transform[] previewBones;
        Vector3[] positions, scales;
        Quaternion[] rotations;
        Vector2 scroll;
        string previewAction = "walk", message = "";
        bool bakeModularAvatar = true, playing;
        float normalizedTime;
        double lastUpdate;

        [MenuItem("Tools/B.E.P 3DAvater Exporter/Avatar export and motions")]
        public static void OpenSelected()
        {
            var window = GetWindow<AvatarCraftExportWindow>("B.E.P 3DAvater Exporter");
            window.minSize = new Vector2(740, 560);
            var selected = Selection.activeGameObject;
            if (selected) { var animator = selected.GetComponentInParent<Animator>(); if (animator && animator.isHuman) window.SetAvatar(animator.gameObject); }
            window.Show();
        }
        void SetAvatar(GameObject source)
        {
            CleanupPreview(); avatar = source;
            if (!avatar || !avatar.GetComponent<Animator>() || !avatar.GetComponent<Animator>().isHuman) return;
            profile = AvatarCraftMotionProfile.FindFor(avatar);
            if (!profile) { profile = CreateInstance<AvatarCraftMotionProfile>(); profile.hideFlags = HideFlags.HideAndDontSave; profile.sourceAvatar = avatar.GetComponent<Animator>().avatar; profile.avatarName = avatar.name; }
            profile.EnsureDefaults(); RebuildPreview();
        }
        void OnEnable() { titleContent = new GUIContent("B.E.P 3DAvater Exporter"); EditorApplication.update += Tick; lastUpdate = EditorApplication.timeSinceStartup; }
        void OnDisable() { EditorApplication.update -= Tick; CleanupPreview(); }
        void Tick()
        {
            double now = EditorApplication.timeSinceStartup;
            if (playing && previewClip)
            {
                var slot = profile.motions.First(s => s.action == previewAction);
                normalizedTime += (float)(now - lastUpdate) * slot.speed / Mathf.Max(.01f, previewClip.length);
                normalizedTime %= 1; Repaint();
            }
            lastUpdate = now;
        }
        void OnGUI()
        {
            float previousLabelWidth = EditorGUIUtility.labelWidth;
            try { EditorGUIUtility.labelWidth = English ? 180 : 160; DrawGUI(); }
            finally { EditorGUIUtility.labelWidth = previousLabelWidth; }
        }
        void DrawGUI()
        {
            EditorGUI.BeginChangeCheck();
            int language = EditorGUILayout.Popup("Language / 言語", English ? 1 : 0, new[] { "日本語", "English" });
            if (EditorGUI.EndChangeCheck()) { English = language == 1; message = ""; }

            EditorGUILayout.LabelField(T("B.E.P 3DAvater Exporter — 人型モーションとアバター変換", "B.E.P 3DAvater Exporter \u2014 Humanoid motions and avatar export"), EditorStyles.boldLabel);
            var next = (GameObject)EditorGUILayout.ObjectField(T("アバターのルート", "Avatar root"), avatar, typeof(GameObject), true);
            if (next != avatar) SetAvatar(next);
            if (!avatar || !profile) { EditorGUILayout.HelpBox(T("Humanoid Animatorのあるアバターを選択してください。", "Select an avatar root with a valid Humanoid Animator."), MessageType.Info); return; }
            var nextProfile = (AvatarCraftMotionProfile)EditorGUILayout.ObjectField(T("モーション設定", "Motion profile"), profile, typeof(AvatarCraftMotionProfile), false);
            if (nextProfile && nextProfile != profile) { profile = nextProfile; profile.EnsureDefaults(); RebuildPreview(); }
            EditorGUILayout.BeginHorizontal();
            if (GUILayout.Button(T("設定を保存", "Save profile"))) SaveProfile();
            if (GUILayout.Button(T("編集用 .anim を生成", "Generate editable .anim clips")))
            {
                string folder = EditorUtility.SaveFolderPanel(T("モーションの生成先（Assets以下）", "Motion destination (inside Assets)"), Application.dataPath, "AvatarCraftMotions");
                if (!string.IsNullOrEmpty(folder))
                {
                    try { string relative = FileUtil.GetProjectRelativePath(folder); if (relative != "Assets" && !relative.StartsWith("Assets/", StringComparison.Ordinal)) throw new InvalidOperationException(T("Assets以下のフォルダを選択してください。", "Choose a folder inside Assets.")); message = GenerateEditableClips(avatar, relative); }
                    catch (Exception e) { message = e.Message; }
                }
            }
            EditorGUILayout.EndHorizontal();
            EditorGUILayout.Space(6);
            EditorGUILayout.BeginHorizontal();
            EditorGUILayout.BeginVertical(GUILayout.Width(280));
            DrawPreview();
            EditorGUILayout.HelpBox(T("骨格に合わせて肩を下げ、肘・膝・重心を使うモーションです。右側で動作ごとに選び、AnimationClipで差し替えることもできます。", "The motions adapt to the skeleton, with relaxed shoulders and natural elbows, knees and balance. Choose each action on the right or replace it with an AnimationClip."), MessageType.Info);
            EditorGUILayout.EndVertical();
            EditorGUILayout.BeginVertical();
            scroll = EditorGUILayout.BeginScrollView(scroll);
            foreach (var action in MotionExport.Library["actions"].Children<Newtonsoft.Json.Linq.JProperty>())
            {
                var slot = profile.motions.Single(s => s.action == action.Name);
                var candidates = MotionExport.Library["clips"].Children<Newtonsoft.Json.Linq.JProperty>().Where(c => (string)c.Value["layer"] == (string)action.Value["layer"]).ToArray();
                EditorGUILayout.BeginVertical(EditorStyles.helpBox);
                EditorGUILayout.BeginHorizontal();
                EditorGUILayout.LabelField(MotionName(action.Name, (string)action.Value["label"]), EditorStyles.boldLabel);
                if (GUILayout.Button(T("確認", "Preview"), GUILayout.Width(English ? 72 : 48))) { previewAction = action.Name; normalizedTime = 0; RebuildClip(); }
                EditorGUILayout.EndHorizontal();
                EditorGUI.BeginChangeCheck();
                int index = Array.FindIndex(candidates, c => c.Name == slot.builtin);
                int choice = EditorGUILayout.Popup(T("標準モーション", "Built-in motion"), Mathf.Max(0, index), candidates.Select(c => MotionName(c.Name, (string)c.Value["name"])).ToArray());
                if (choice != index) slot.builtin = candidates[choice].Name;
                slot.customClip = (AnimationClip)EditorGUILayout.ObjectField(T("差し替えClip（任意）", "Custom clip (optional)"), slot.customClip, typeof(AnimationClip), false);
                EditorGUILayout.BeginHorizontal();
                slot.speed = EditorGUILayout.Slider(T("再生速度", "Playback speed"), slot.speed, .1f, 4);
                EditorGUILayout.EndHorizontal();
                slot.blendSeconds = EditorGUILayout.Slider(T("切替時間（秒）", "Blend duration (seconds)"), slot.blendSeconds, 0, 2);
                if (EditorGUI.EndChangeCheck()) { EditorUtility.SetDirty(profile); if (previewAction == action.Name) RebuildClip(); }
                EditorGUILayout.EndVertical();
            }
            EditorGUILayout.EndScrollView();
            EditorGUILayout.EndVertical();
            EditorGUILayout.EndHorizontal();
            bakeModularAvatar = EditorGUILayout.ToggleLeft(T("Modular Avatar / NDMFを複製上に適用", "Apply Modular Avatar / NDMF to an isolated clone"), bakeModularAvatar);
            if (!string.IsNullOrEmpty(message)) EditorGUILayout.HelpBox(message, MessageType.Info);
            if (GUILayout.Button(T("この設定で .avc を変換", "Export .avc with this profile"), GUILayout.Height(30)))
            {
                string path = EditorUtility.SaveFilePanel("B.E.P 3DAvater Exporter", "", avatar.name, "avc");
                if (!string.IsNullOrEmpty(path)) try { message = AvatarCraftExporter.ExportWithProfile(avatar, path, bakeModularAvatar, profile); } catch (Exception e) { message = e.GetBaseException().Message; Debug.LogException(e); }
            }
        }
        void SaveProfile()
        {
            if (!AssetDatabase.Contains(profile))
            {
                string path = EditorUtility.SaveFilePanelInProject(T("モーション設定を保存", "Save motion profile"), avatar.name + "_Motions", "asset", T("アバターごとのモーション割当を保存します。", "Save the motion assignments for this avatar."));
                if (string.IsNullOrEmpty(path)) return;
                profile.hideFlags = HideFlags.None; AssetDatabase.CreateAsset(profile, AssetDatabase.GenerateUniqueAssetPath(path));
            }
            EditorUtility.SetDirty(profile); AssetDatabase.SaveAssets(); message = T("保存しました: ", "Saved: ") + AssetDatabase.GetAssetPath(profile);
        }
        void RebuildPreview()
        {
            if (!avatar) return;
            CleanupPreview();
            preview = new PreviewRenderUtility();
            previewAvatar = Instantiate(avatar); previewAvatar.name = avatar.name;
            previewAvatar.transform.SetPositionAndRotation(Vector3.zero, Quaternion.identity);
            foreach (var behaviour in previewAvatar.GetComponentsInChildren<Behaviour>(true)) behaviour.enabled = false;
            preview.AddSingleGO(previewAvatar);
            previewBones = previewAvatar.GetComponentsInChildren<Transform>(true);
            positions = previewBones.Select(b => b.localPosition).ToArray(); rotations = previewBones.Select(b => b.localRotation).ToArray(); scales = previewBones.Select(b => b.localScale).ToArray();
            preview.cameraFieldOfView = 28;
            var renderers = previewAvatar.GetComponentsInChildren<Renderer>().Where(r => r.enabled).ToArray();
            var bounds = renderers[0].bounds; foreach (var renderer in renderers) bounds.Encapsulate(renderer.bounds);
            preview.camera.transform.position = bounds.center + new Vector3(bounds.size.y * .4f, bounds.size.y * .06f, bounds.size.y * 2.4f); preview.camera.transform.LookAt(bounds.center);
            preview.camera.nearClipPlane = .01f; preview.camera.farClipPlane = 100;
            preview.lights[0].intensity = 1.1f; preview.lights[0].transform.rotation = Quaternion.Euler(30, 30, 0); preview.lights[1].intensity = .6f;
            RebuildClip();
        }
        void RebuildClip()
        {
            if (!previewAvatar || !profile) return;
            if (previewClip && !AssetDatabase.Contains(previewClip)) DestroyImmediate(previewClip);
            var slot = profile.motions.Single(s => s.action == previewAction);
            ResetPreview(); previewClip = slot.customClip ? MotionExport.PrepareSampleClip(previewAvatar, slot.customClip) : MotionExport.CreateBuiltinClip(previewAvatar, slot.builtin);
        }
        void ResetPreview() { if (previewBones == null) return; for (int i = 0; i < previewBones.Length; i++) { previewBones[i].localPosition = positions[i]; previewBones[i].localRotation = rotations[i]; previewBones[i].localScale = scales[i]; } }
        void DrawPreview()
        {
            if (preview == null) RebuildPreview();
            var rect = GUILayoutUtility.GetRect(200, 280, 160, 320, GUILayout.ExpandWidth(true), GUILayout.ExpandHeight(true));
            if (Event.current.type == EventType.Repaint && previewClip)
            {
                ResetPreview(); MotionExport.SampleClip(previewAvatar, previewClip, previewClip.length * normalizedTime);
                preview.BeginPreview(rect, GUIStyle.none); preview.camera.Render(); var image = preview.EndPreview(); GUI.DrawTexture(rect, image, ScaleMode.ScaleToFit, false);
            }
            EditorGUILayout.LabelField(T("確認中: ", "Previewing: ") + MotionName(previewAction, (string)MotionExport.Library["actions"][previewAction]["label"]));
            normalizedTime = EditorGUILayout.Slider(T("再生位置", "Playback position"), normalizedTime, 0, 1);
            playing = GUILayout.Toggle(playing, T("再生", "Play"), "Button");
        }
        void CleanupPreview()
        {
            if (previewClip && !AssetDatabase.Contains(previewClip)) DestroyImmediate(previewClip);
            previewClip = null; preview?.Cleanup(); preview = null; previewAvatar = null;
        }
        public static string GenerateEditableClips(GameObject source, string folder)
        {
            if (folder != "Assets" && !folder.StartsWith("Assets/", StringComparison.Ordinal)) throw new ArgumentException("Use an Assets folder");
            Directory.CreateDirectory(folder); AssetDatabase.Refresh(); int count = 0;
            foreach (var clip in MotionExport.Library["clips"].Children<Newtonsoft.Json.Linq.JProperty>())
            {
                var animation = MotionExport.CreateBuiltinClip(source, clip.Name);
                AssetDatabase.CreateAsset(animation, AssetDatabase.GenerateUniqueAssetPath(folder + "/" + clip.Name + ".anim")); count++;
            }
            AssetDatabase.SaveAssets(); return T(count + "本のモーションを生成しました: ", "Generated " + count + " motion clips: ") + folder;
        }
    }
}

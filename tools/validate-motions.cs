var selectedAnimator = Selection.activeGameObject ? Selection.activeGameObject.GetComponentInParent<Animator>() : null;
if (!selectedAnimator || !selectedAnimator.isHuman) throw new InvalidOperationException("Select a valid Humanoid avatar root first");
var source = selectedAnimator.gameObject;
var exporter = AppDomain.CurrentDomain.GetAssemblies().Select(a => a.GetType("AvatarCraft.Editor.MotionExport")).First(t => t != null);
var profileType = exporter.Assembly.GetType("AvatarCraft.Editor.AvatarCraftMotionProfile");
var windowType = exporter.Assembly.GetType("AvatarCraft.Editor.AvatarCraftExportWindow");
string folder = "Assets/AvatarCraft";
if (!AssetDatabase.IsValidFolder(folder)) AssetDatabase.CreateFolder("Assets", "AvatarCraft");
if (!AssetDatabase.IsValidFolder(folder + "/MotionProfiles")) AssetDatabase.CreateFolder(folder, "MotionProfiles");
if (!AssetDatabase.IsValidFolder(folder + "/Motions")) AssetDatabase.CreateFolder(folder, "Motions");
var profile = (ScriptableObject)profileType.GetMethod("FindFor").Invoke(null, new object[] { source });
if (!profile) profile = (ScriptableObject)profileType.GetMethod("CreateFor").Invoke(null, new object[] { source, folder + "/MotionProfiles/" + source.name + "_Motions.asset" });
profileType.GetMethod("EnsureDefaults").Invoke(profile, null);
var slots = ((System.Collections.IEnumerable)profileType.GetField("motions").GetValue(profile)).Cast<object>().ToArray();
var slot = slots.Single(s => (string)s.GetType().GetField("action").GetValue(s) == "walk");
var slotType = slot.GetType();
var originalBuiltin = slotType.GetField("builtin").GetValue(slot);
var originalCustom = slotType.GetField("customClip").GetValue(slot);
var originalSpeed = slotType.GetField("speed").GetValue(slot);
var originalBlend = slotType.GetField("blendSeconds").GetValue(slot);
var scene = UnityEditor.SceneManagement.EditorSceneManager.NewPreviewScene();
try
{
    var clone = UnityEngine.Object.Instantiate(source); clone.name = source.name;
    UnityEngine.SceneManagement.SceneManager.MoveGameObjectToScene(clone, scene);
    var transforms = clone.GetComponentsInChildren<Transform>(true);
    var before = transforms.Select(t => t.localRotation).ToArray();
    var beforePosition = transforms.Select(t => t.localPosition).ToArray();
    var beforeScale = transforms.Select(t => t.localScale).ToArray();
    var build = exporter.GetMethod("Build");
    var result = Newtonsoft.Json.Linq.JObject.Parse(Newtonsoft.Json.JsonConvert.SerializeObject(build.Invoke(null, new object[] { clone, profile })));
    if (((Newtonsoft.Json.Linq.JObject)result["actions"]).Count != 34) throw new Exception("Missing action assignments");
    var q = result["neutral"]["RightUpperArm"]["rotation"];
    if (Quaternion.Angle(Quaternion.identity, new Quaternion((float)q[0], (float)q[1], (float)q[2], (float)q[3])) < 20) throw new Exception("T-pose arm was not lowered");
    if (transforms.Where((t, i) => Quaternion.Angle(t.localRotation, before[i]) > .01f).Any()) throw new Exception("Motion baking modified the source pose");
    slotType.GetField("builtin").SetValue(slot, "walk_soft");slotType.GetField("customClip").SetValue(slot, null);slotType.GetField("speed").SetValue(slot, 1.15f);slotType.GetField("blendSeconds").SetValue(slot, .25f);
    var changed = Newtonsoft.Json.Linq.JObject.Parse(Newtonsoft.Json.JsonConvert.SerializeObject(build.Invoke(null, new object[] { clone, profile })));
    if ((string)changed["actions"]["walk"]["clip"] != "walk_soft" || Math.Abs((float)changed["actions"]["walk"]["speed"] - 1.15f) > .0001f || Math.Abs((float)changed["actions"]["walk"]["blendSeconds"] - .25f) > .0001f) throw new Exception("Profile mapping was not baked");
    var animation = (AnimationClip)exporter.GetMethod("CreateBuiltinClip").Invoke(null, new object[] { clone, "walk_natural" });
    try
    {
        slotType.GetField("customClip").SetValue(slot, animation);
        var custom = Newtonsoft.Json.Linq.JObject.Parse(Newtonsoft.Json.JsonConvert.SerializeObject(build.Invoke(null, new object[] { clone, profile })));
        string id = (string)custom["actions"]["walk"]["clip"];
        if (!id.StartsWith("custom_") || ((Newtonsoft.Json.Linq.JArray)custom["clips"][id]["tracks"]["LeftLowerLeg"]).Count < 30) throw new Exception("Custom AnimationClip sampling failed");
        var hipsOffset=custom["clips"][id]["tracks"]["Hips"][0]["position"];
        var hips=clone.GetComponent<Animator>().GetBoneTransform(HumanBodyBones.Hips);
        var offsetWorld=hips.parent.TransformVector(new Vector3((float)hipsOffset[0],(float)hipsOffset[1],(float)hipsOffset[2]));
        if (Math.Abs(offsetWorld.y)<.002f || Math.Abs(offsetWorld.x)>.0001f || Math.Abs(offsetWorld.z)>.0001f) throw new Exception("Hip recentering removed vertical motion or retained horizontal drift: "+offsetWorld);
        foreach (var bone in new[] { "LeftUpperArm", "RightUpperArm", "LeftLowerLeg", "RightLowerLeg" })
        {
            var x = custom["clips"][id + "_left"]["tracks"][bone][0]["rotation"];
            var y = result["clips"]["walk_natural_left"]["tracks"][bone][0]["rotation"];
            if (Quaternion.Angle(new Quaternion((float)x[0],(float)x[1],(float)x[2],(float)x[3]),new Quaternion((float)y[0],(float)y[1],(float)y[2],(float)y[3])) > .1f) throw new Exception("Custom clip mirror differs from canonical mirror: " + bone);
        }
        if (transforms.Where((t,i) => Quaternion.Angle(t.localRotation,before[i]) > .01f || (t.localPosition-beforePosition[i]).sqrMagnitude > 1e-10f || (t.localScale-beforeScale[i]).sqrMagnitude > 1e-10f).Any()) throw new Exception("Custom sampling modified the armature");
    }
    finally { UnityEngine.Object.DestroyImmediate(animation); }
    string clipFolder = folder + "/Motions/" + source.name;
    if (!AssetDatabase.IsValidFolder(clipFolder))
        windowType.GetMethod("GenerateEditableClips").Invoke(null, new object[] { clone, clipFolder });
    var actionCount = ((Newtonsoft.Json.Linq.JObject)result["actions"]).Count;
    var clipCount = ((Newtonsoft.Json.Linq.JObject)result["clips"]).Count;
    var previewFolder = System.IO.Path.Combine(System.IO.Directory.GetParent(Application.dataPath).FullName, "Library", "AvatarCraftDiagnostics", "motion-preview");
    System.IO.Directory.CreateDirectory(previewFolder);
    foreach (var motion in new[] { "idle_relaxed", "walk_natural", "run_jog", "crouch_idle", "attack", "eat" })
    {
        var utility = new PreviewRenderUtility(); Texture2D image = null; AnimationClip clip = null;
        try
        {
            var model = UnityEngine.Object.Instantiate(source); model.transform.SetPositionAndRotation(Vector3.zero, Quaternion.identity);
            utility.AddSingleGO(model);
            foreach (var behaviour in model.GetComponentsInChildren<Behaviour>(true)) behaviour.enabled = false;
            var renderers = model.GetComponentsInChildren<Renderer>().Where(r => r.enabled).ToArray();
            var bounds = renderers[0].bounds; foreach (var r in renderers) bounds.Encapsulate(r.bounds);
            clip = (AnimationClip)exporter.GetMethod("CreateBuiltinClip").Invoke(null, new object[] { model, motion });
            exporter.GetMethod("SampleClip").Invoke(null, new object[] { model, clip, motion == "attack" ? .12f : clip.length * .3f });
            if (motion == "eat")
            {
                var animator = model.GetComponent<Animator>();
                var shoulder = animator.GetBoneTransform(HumanBodyBones.LeftUpperArm); var elbow = animator.GetBoneTransform(HumanBodyBones.LeftLowerArm);
                if (Vector3.Dot((elbow.position - shoulder.position).normalized, Vector3.down) < .9f) throw new Exception("Upper-body preview left an untracked arm in T-pose");
            }
            utility.cameraFieldOfView = 28;utility.camera.transform.position = bounds.center + new Vector3(bounds.size.y * 1.0f, bounds.size.y * .15f, bounds.size.y * 2.3f);utility.camera.transform.LookAt(bounds.center);
            utility.camera.nearClipPlane=.01f;utility.camera.farClipPlane=100;utility.lights[0].intensity=1.2f;utility.lights[1].intensity=.8f;
            utility.BeginStaticPreview(new Rect(0,0,384,512));utility.camera.Render();image=utility.EndStaticPreview();
            System.IO.File.WriteAllBytes(System.IO.Path.Combine(previewFolder, motion + ".png"), image.EncodeToPNG());
        }
        finally { if (image) UnityEngine.Object.DestroyImmediate(image);if (clip) UnityEngine.Object.DestroyImmediate(clip);utility.Cleanup(); }
    }
    return new { succeeded = true, actions = actionCount, bakedClips = clipCount, editableClips = AssetDatabase.FindAssets("t:AnimationClip", new[] { clipFolder }).Length, profile = AssetDatabase.GetAssetPath(profile), checks = new[] { "34 action mappings", "neutral arms", "pose restoration", "profile override", "speed and crossfade", "custom clip sampling", "custom mirror retarget", "custom sampling preserves armature", "hip recentering preserves vertical motion", "editable animation assets", "upper-body standalone neutral", "6 rendered pose previews" } };
}
finally
{
    slotType.GetField("builtin").SetValue(slot, originalBuiltin);slotType.GetField("customClip").SetValue(slot, originalCustom);slotType.GetField("speed").SetValue(slot, originalSpeed);slotType.GetField("blendSeconds").SetValue(slot, originalBlend);
    EditorUtility.SetDirty(profile);AssetDatabase.SaveAssets();
    UnityEditor.SceneManagement.EditorSceneManager.ClosePreviewScene(scene);
}

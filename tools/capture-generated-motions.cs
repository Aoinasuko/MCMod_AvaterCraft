var selectedAnimator = Selection.activeGameObject ? Selection.activeGameObject.GetComponentInParent<Animator>() : null;
if (!selectedAnimator || !selectedAnimator.isHuman) throw new InvalidOperationException("Select a valid Humanoid avatar root first");
var source = selectedAnimator.gameObject;
var exporter = AppDomain.CurrentDomain.GetAssemblies().Select(a => a.GetType("AvatarCraft.Editor.MotionExport")).First(t => t != null);
var hashes = new System.Collections.Generic.Dictionary<string,string>();
foreach (string guid in AssetDatabase.FindAssets("t:AnimationClip", new[] { "Assets/AvatarCraft/Motions/" + source.name }))
{
    string path = AssetDatabase.GUIDToAssetPath(guid);
    var asset = AssetDatabase.LoadAssetAtPath<AnimationClip>(path);
    var expected = (AnimationClip)exporter.GetMethod("CreateBuiltinClip").Invoke(null, new object[] { source, asset.name });
    try
    {
        var bindings = AnimationUtility.GetCurveBindings(asset);
        bool same = bindings.Length == AnimationUtility.GetCurveBindings(expected).Length && bindings.All(b =>
        {
            var x = AnimationUtility.GetEditorCurve(asset,b); var y = AnimationUtility.GetEditorCurve(expected,b);
            return y != null && x.keys.SequenceEqual(y.keys);
        });
        if (same) using (var sha = System.Security.Cryptography.SHA256.Create()) hashes[path] = Convert.ToBase64String(sha.ComputeHash(System.IO.File.ReadAllBytes(path)));
    }
    finally { UnityEngine.Object.DestroyImmediate(expected); }
}
var diagnostics = System.IO.Path.Combine(System.IO.Directory.GetParent(Application.dataPath).FullName, "Library", "AvatarCraftDiagnostics");
System.IO.Directory.CreateDirectory(diagnostics);
System.IO.File.WriteAllText(System.IO.Path.Combine(diagnostics, "generated-motion-hashes.json"), Newtonsoft.Json.JsonConvert.SerializeObject(hashes));
return new { unchangedGeneratedClips = hashes.Count };

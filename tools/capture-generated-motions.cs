var source = UnityEngine.Object.FindObjectsOfType<Animator>().Single(a => a.name == "nasuko" && a.isHuman).gameObject;
var exporter = AppDomain.CurrentDomain.GetAssemblies().Select(a => a.GetType("AvatarCraft.Editor.MotionExport")).First(t => t != null);
var hashes = new System.Collections.Generic.Dictionary<string,string>();
foreach (string guid in AssetDatabase.FindAssets("t:AnimationClip", new[] { "Assets/AvatarCraft/Motions/nasuko" }))
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
System.IO.File.WriteAllText("D:/Dev/AvaterCraft/build/generated-motion-hashes.json", Newtonsoft.Json.JsonConvert.SerializeObject(hashes));
return new { unchangedGeneratedClips = hashes.Count };

var source = UnityEngine.Object.FindObjectsOfType<Animator>().Single(a => a.name == "nasuko" && a.isHuman).gameObject;
var exporter = AppDomain.CurrentDomain.GetAssemblies().Select(a => a.GetType("AvatarCraft.Editor.MotionExport")).First(t => t != null);
var hashes = Newtonsoft.Json.JsonConvert.DeserializeObject<System.Collections.Generic.Dictionary<string,string>>(System.IO.File.ReadAllText("D:/Dev/AvaterCraft/build/generated-motion-hashes.json"));
int updated = 0;
foreach (var entry in hashes)
{
    using (var sha = System.Security.Cryptography.SHA256.Create())
        if (Convert.ToBase64String(sha.ComputeHash(System.IO.File.ReadAllBytes(entry.Key))) != entry.Value) continue;
    var asset = AssetDatabase.LoadAssetAtPath<AnimationClip>(entry.Key);
    var replacement = (AnimationClip)exporter.GetMethod("CreateBuiltinClip").Invoke(null, new object[] { source, asset.name });
    try { EditorUtility.CopySerialized(replacement, asset);EditorUtility.SetDirty(asset);updated++; }
    finally { UnityEngine.Object.DestroyImmediate(replacement); }
}
AssetDatabase.SaveAssets(); return new { updatedUnmodifiedGeneratedClips = updated };

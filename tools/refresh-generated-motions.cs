var selectedAnimator = Selection.activeGameObject ? Selection.activeGameObject.GetComponentInParent<Animator>() : null;
if (!selectedAnimator || !selectedAnimator.isHuman) throw new InvalidOperationException("Select a valid Humanoid avatar root first");
var source = selectedAnimator.gameObject;
var exporter = AppDomain.CurrentDomain.GetAssemblies().Select(a => a.GetType("AvatarCraft.Editor.MotionExport")).First(t => t != null);
var diagnostics = System.IO.Path.Combine(System.IO.Directory.GetParent(Application.dataPath).FullName, "Library", "AvatarCraftDiagnostics");
var hashes = Newtonsoft.Json.JsonConvert.DeserializeObject<System.Collections.Generic.Dictionary<string,string>>(System.IO.File.ReadAllText(System.IO.Path.Combine(diagnostics, "generated-motion-hashes.json")));
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

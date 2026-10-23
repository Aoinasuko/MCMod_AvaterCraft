// Temporary fixture: imported animation bounds and unused vertices must not determine body size.
var exporter = AppDomain.CurrentDomain.GetAssemblies().Select(a => a.GetType("AvatarCraft.Editor.AvatarCraftExporter")).First(t => t != null);
var root = new GameObject("AvatarCraftBoundsFixture");
var mesh = new Mesh();
try
{
    var bone = new GameObject("Bone"); bone.transform.SetParent(root.transform, false);
    mesh.vertices = new[] { new Vector3(-.5f, 0, 0), new Vector3(.5f, 0, 0), new Vector3(0, 2, 0), new Vector3(0, 100, 0) };
    mesh.triangles = new[] { 0, 1, 2 };
    mesh.bindposes = new[] { Matrix4x4.identity };
    mesh.boneWeights = Enumerable.Range(0, 4).Select(_ => new BoneWeight { boneIndex0 = 0, weight0 = 1 }).ToArray();
    var renderer = root.AddComponent<SkinnedMeshRenderer>(); renderer.sharedMesh = mesh;
    renderer.bones = new[] { bone.transform }; renderer.rootBone = bone.transform;
    renderer.localBounds = new Bounds(new Vector3(0, 10, 0), new Vector3(100, 100, 100));
    var method = exporter.GetMethod("VisibleBounds", System.Reflection.BindingFlags.Static | System.Reflection.BindingFlags.NonPublic);
    var bounds = (Bounds)method.Invoke(null, new object[] { new[] { renderer } });
    if (Mathf.Abs(bounds.size.y - 2) > .0001f || Mathf.Abs(bounds.min.y) > .0001f)
        throw new Exception("Imported/unused bounds corrupted avatar dimensions: " + bounds);
    root.transform.localScale = Vector3.one * .5f;
    bounds = (Bounds)method.Invoke(null, new object[] { new[] { renderer } });
    if (Mathf.Abs(bounds.size.y - 1) > .0001f) throw new Exception("World scale must be applied once: " + bounds);
    return new { passed = true, height = bounds.size.y, feetOffset = bounds.min.y };
}
finally { UnityEngine.Object.DestroyImmediate(root); UnityEngine.Object.DestroyImmediate(mesh); }

using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;
using UnityEditor;
using UnityEngine;
using Object = UnityEngine.Object;
using CompressionLevel = System.IO.Compression.CompressionLevel;

namespace AvatarCraft.Editor
{
    public static class AvatarCraftExporter
    {
        static readonly JsonSerializerSettings Json = new JsonSerializerSettings { Formatting = Formatting.None };
        [MenuItem("Tools/B.E.P 3DAvater Exporter/Export selected avatar")]
        public static void ExportSelected()
        {
            AvatarCraftExportWindow.OpenSelected();
        }

        public static string Export(GameObject source, string path, bool bakeModularAvatar = true)
        {
            return ExportWithProfile(source, path, bakeModularAvatar, AvatarCraftMotionProfile.FindFor(source));
        }

        public static string ExportWithProfile(GameObject source, string path, bool bakeModularAvatar, AvatarCraftMotionProfile profile)
        {
            if (!source || !source.GetComponent<Animator>() || !source.GetComponent<Animator>().isHuman)
                throw new InvalidOperationException("Avatar root must have a Humanoid Animator.");
            var warnings = new List<string>();
            var clone = Object.Instantiate(source);
            clone.name = source.name;
            clone.transform.SetPositionAndRotation(Vector3.zero, Quaternion.identity);
            clone.transform.localScale = source.transform.lossyScale;
            string temp = path + ".tmp";
            try
            {
                // Prevent unrelated export plugins on this clone from overwriting user outputs.
                foreach (var component in clone.GetComponentsInChildren<Component>(true).Where(c => c && c.GetType().Name == "NdmfVrmExporterComponent").ToArray())
                    Object.DestroyImmediate(component);
                if (bakeModularAvatar)
                {
                    var processor = AppDomain.CurrentDomain.GetAssemblies().Select(a => a.GetType("nadena.dev.ndmf.AvatarProcessor")).FirstOrDefault(t => t != null);
                    var process = processor?.GetMethod("ProcessAvatar", new[] { typeof(GameObject) });
                    if (process != null) { process.Invoke(null, new object[] { clone }); warnings.Add("NDMF processed an isolated clone; generated NDMF temporary assets may remain in the project."); }
                }
                var animator = clone.GetComponent<Animator>();
                // Normalize to the Avatar's humanoid reference pose before collecting bind data.
                // The current authored shape weights are preserved as defaults.
                var transforms = clone.GetComponentsInChildren<Transform>(true);
                var boneIds = transforms.Select((t, i) => new { t, i }).ToDictionary(x => x.t, x => x.i);
                var humanoid = new Dictionary<string, int>();
                for (int i = 0; i < (int)HumanBodyBones.LastBone; i++)
                {
                    var t = animator.GetBoneTransform((HumanBodyBones)i);
                    if (t && boneIds.ContainsKey(t)) humanoid[((HumanBodyBones)i).ToString()] = boneIds[t];
                }
                var bones = transforms.Select(t => new { name = AnimationUtility.CalculateTransformPath(t, clone.transform), parent = t == clone.transform ? -1 : boneIds[t.parent], position = V(t.localPosition), rotation = Q(t.localRotation), scale = V(t.localScale) }).ToArray();
                var materials = new List<object>();
                var meshes = new List<object>();
                var materialIds = new Dictionary<Material, int>();
                var textures = new Dictionary<string, string>();
                var surfaceSchema = JObject.Parse(Resources.Load<TextAsset>("AvatarCraft/surface").text);
                var surfaceSlots = new HashSet<string>(surfaceSchema["slots"].Values<string>());
                var shapeDefaults = new Dictionary<string, float>();
                var shapes = new List<string>();
                Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path)));
                using (var file = File.Create(temp))
                using (var zip = new ZipArchive(file, ZipArchiveMode.Create))
                {
                    Func<Texture, bool, bool, string> exportTexture = (texture, normal, linear) =>
                    {
                        if (!texture) return null;
                        string textureKey = texture.GetInstanceID() + ":" + normal + ":" + linear;
                        if (textures.TryGetValue(textureKey, out var existing)) return existing;
                        var name = "textures/" + textures.Count + ".png";
                        textures[textureKey] = name;
                        Write(zip, name, TexturePng(texture, normal, linear));
                        return name;
                    };
                    Func<Material, int> materialIndex = material =>
                    {
                        if (!material) return -1;
                        if (materialIds.TryGetValue(material, out int existing)) return existing;
                        int id = materials.Count;
                        materialIds[material] = id;
                        var props = new Dictionary<string, object>();
                        var shader = material.shader;
                        for (int p = 0; p < shader.GetPropertyCount(); p++)
                        {
                            string n = shader.GetPropertyName(p);
                            switch (shader.GetPropertyType(p))
                            {
                                case UnityEngine.Rendering.ShaderPropertyType.Color: props[n] = C(material.GetColor(n)); break;
                                case UnityEngine.Rendering.ShaderPropertyType.Vector: var v = material.GetVector(n); props[n] = new[] { v.x, v.y, v.z, v.w }; break;
                                case UnityEngine.Rendering.ShaderPropertyType.Texture:
                                    var tex = material.GetTexture(n);
                                    // Keep every texture property, but export only textures used by supported passes.
                                    bool used = n == "_MainTex" || n == "_OutlineTex" || surfaceSlots.Contains(n);
                                    bool normal = n.Contains("Bump") && !n.Contains("Mask");
                                    bool linear = normal || n.Contains("Mask");
                                    props[n] = new { texture = used ? exportTexture(tex, normal, linear) : null, source = tex ? tex.name : null, scale = V2(material.GetTextureScale(n)), offset = V2(material.GetTextureOffset(n)), wrapU = tex ? (int)tex.wrapModeU : 0, wrapV = tex ? (int)tex.wrapModeV : 0, normalEncoding = normal ? "rgb" : null };
                                    break;
                                default: props[n] = material.GetFloat(n); break;
                            }
                        }
                        foreach (string channel in new[] { "_Main2ndTex_UVMode", "_Main3rdTex_UVMode", "_Bump2ndMap_UVMode", "_EmissionMap_UVMode", "_Emission2ndMap_UVMode" })
                            if (material.HasProperty(channel) && material.GetFloat(channel) > 0 && material.GetFloat(channel) < 4)
                                warnings.Add(material.name + ": " + channel + " uses UV1-3; runtime currently falls back to UV0.");
                        materials.Add(new { name = material.name, shader = shader.name, renderQueue = material.renderQueue, renderType = material.GetTag("RenderType", false, ""), properties = props });
                        if (!shader.name.ToLowerInvariant().Contains("liltoon")) warnings.Add("Non-lilToon material: " + material.name + " (" + shader.name + ")");
                        return id;
                    };
                    foreach (var renderer in clone.GetComponentsInChildren<SkinnedMeshRenderer>().Where(r => r.enabled && r.sharedMesh))
                    {
                        var mesh = renderer.sharedMesh;
                        string meshName = AnimationUtility.CalculateTransformPath(renderer.transform, clone.transform);
                        string entry = "meshes/" + meshes.Count + ".bin";
                        var vertices = mesh.vertices; var normals = mesh.normals; var uv = mesh.uv; var weights = mesh.boneWeights;
                        var ids = renderer.bones.Select(b => b && boneIds.ContainsKey(b) ? boneIds[b] : boneIds[renderer.transform]).ToArray();
                        bool rigid = ids.Length == 0;
                        if (rigid) ids = new[] { boneIds[renderer.transform] };
                        var binds = rigid ? new[] { Matrix4x4.identity } : mesh.bindposes;
                        var subs = new List<object>();
                        // ZIP entries must be closed before creating a texture entry.
                        for (int s = 0; s < mesh.subMeshCount; s++)
                            subs.Add(new { material = materialIndex(s < renderer.sharedMaterials.Length ? renderer.sharedMaterials[s] : null) });
                        using (var stream = zip.CreateEntry(entry, CompressionLevel.Optimal).Open())
                        using (var writer = new BinaryWriter(stream))
                        {
                            writer.Write(0x31435641); writer.Write(vertices.Length);
                            for (int v = 0; v < vertices.Length; v++)
                            {
                                WV(writer, vertices[v]); WV(writer, normals.Length == vertices.Length ? normals[v] : Vector3.up);
                                var u = uv.Length == vertices.Length ? uv[v] : Vector2.zero; writer.Write(u.x); writer.Write(u.y);
                                if (rigid || weights.Length != vertices.Length) { for (int k = 0; k < 4; k++) writer.Write(0); writer.Write(1f); writer.Write(0f); writer.Write(0f); writer.Write(0f); }
                                else { var w = weights[v]; writer.Write(w.boneIndex0); writer.Write(w.boneIndex1); writer.Write(w.boneIndex2); writer.Write(w.boneIndex3); writer.Write(w.weight0); writer.Write(w.weight1); writer.Write(w.weight2); writer.Write(w.weight3); }
                            }
                            writer.Write(mesh.subMeshCount);
                            for (int s = 0; s < mesh.subMeshCount; s++)
                            {
                                if (mesh.GetTopology(s) != MeshTopology.Triangles) throw new InvalidOperationException("Only triangle meshes are supported: " + meshName);
                                var indices = mesh.GetTriangles(s); writer.Write(indices.Length); foreach (int ix in indices) writer.Write(ix);
                            }
                            writer.Write(mesh.blendShapeCount);
                            for (int s = 0; s < mesh.blendShapeCount; s++)
                            {
                                string shape = mesh.GetBlendShapeName(s); string key = meshName + ":" + shape;
                                shapes.Add(key); shapeDefaults[key] = renderer.GetBlendShapeWeight(s);
                                var nameBytes = Encoding.UTF8.GetBytes(shape); writer.Write(nameBytes.Length); writer.Write(nameBytes);
                                int frames = mesh.GetBlendShapeFrameCount(s); writer.Write(frames);
                                for (int f = 0; f < frames; f++)
                                {
                                    writer.Write(mesh.GetBlendShapeFrameWeight(s, f));
                                    var dv = new Vector3[vertices.Length]; var dn = new Vector3[vertices.Length];
                                    mesh.GetBlendShapeFrameVertices(s, f, dv, dn, null);
                                    var changed = Enumerable.Range(0, vertices.Length).Where(i => dv[i] != Vector3.zero || dn[i] != Vector3.zero).ToArray();
                                    writer.Write(changed.Length); foreach (int v in changed) { writer.Write(v); WV(writer, dv[v]); WV(writer, dn[v]); }
                                }
                            }
                        }
                        meshes.Add(new { name = meshName, data = entry, bones = ids, bindPoses = binds.Select(M).ToArray(), submeshes = subs });
                    }
                    if (meshes.Count == 0) throw new InvalidOperationException("No active skinned meshes.");
                    var physics = PhysBoneExport.Build(clone, boneIds);
                    var springs = physics.springs; var colliders = physics.colliders;
                    warnings.Add("PhysBone uses an approximate solver with exported angle and stretch constraints; grabbing and posing interactions are metadata only.");
                    warnings.Add("Animator FX/menus, contacts, constraints and runtime scripts are not executed in Minecraft.");
                    var idPath = AssetDatabase.GetAssetPath(source.GetComponent<Animator>().avatar);
                    var identity = AssetDatabase.AssetPathToGUID(idPath) + ":" + source.name;
                    var id = BitConverter.ToString(SHA256.Create().ComputeHash(Encoding.UTF8.GetBytes(identity))).Replace("-", "").ToLowerInvariant().Substring(0, 24);
                    var activeRenderers = clone.GetComponentsInChildren<SkinnedMeshRenderer>().Where(r => r.enabled && r.sharedMesh).ToArray();
                    // Imported skinned bounds include animation padding and may extend far below
                    // the feet. Measure the actually skinned, referenced vertices instead.
                    var bounds = VisibleBounds(activeRenderers);
                    var manifest = new { formatVersion = 1, id, name = source.name, height = bounds.size.y, feetOffset = bounds.min.y, bones, humanoid, meshes, materials, physicsVersion = physics.version, springs, colliders, defaults = shapeDefaults, warnings };
                    WriteJson(zip, "avatar.json", manifest);
                    var motions = MotionExport.Build(clone, profile);
                    byte[] motionData = Encoding.UTF8.GetBytes(JsonConvert.SerializeObject(motions, Json));
                    if (motionData.Length > 16 * 1024 * 1024) throw new InvalidOperationException("Motion data exceeds 16 MiB.");
                    Write(zip, "motions.json", motionData);
                    var events = new Dictionary<string, Dictionary<string, float>> { ["idle"] = new Dictionary<string, float>(), ["blink"] = new Dictionary<string, float>(), ["eat"] = new Dictionary<string, float>(), ["hurt"] = new Dictionary<string, float>() };
                    var blink = shapes.FirstOrDefault(s => new[] { "blink", "まばたき", "瞬き" }.Any(k => s.Substring(s.LastIndexOf(':') + 1).ToLowerInvariant() == k));
                    if (blink != null) events["blink"][blink] = 100;
                    var mouth = shapes.FirstOrDefault(s => s.EndsWith(":vrc.v_aa", StringComparison.OrdinalIgnoreCase));
                    if (mouth != null) events["eat"][mouth] = 35;
                    if (blink != null) events["hurt"][blink] = 100;
                    WriteJson(zip, "expressions.json", new { formatVersion = 1, blinkMinSeconds = 2f, blinkMaxSeconds = 4f, blinkDurationSeconds = .16f, events });
                    var preview = RenderPreview(clone);
                    Write(zip, "preview.png", preview);
                }
                // Replace only after export succeeds, preserving previous export on failure.
                if (File.Exists(path)) File.Replace(temp, path, null); else File.Move(temp, path);
                return JsonConvert.SerializeObject(new { path, bytes = new FileInfo(path).Length, meshes = meshes.Count, materials = materials.Count, shapes = shapes.Count, warnings }, Formatting.Indented);
            }
            finally { Object.DestroyImmediate(clone); if (File.Exists(temp)) File.Delete(temp); }
        }

        static object Read(object o, string n) { var t = o.GetType(); return t.GetField(n)?.GetValue(o) ?? t.GetProperty(n)?.GetValue(o); }
        internal static Bounds VisibleBounds(SkinnedMeshRenderer[] renderers)
        {
            var bounds = new Bounds(); bool initialized = false;
            foreach (var renderer in renderers)
            {
                var baked = new Mesh();
                try
                {
                    renderer.BakeMesh(baked, true);
                    var vertices = baked.vertices;
                    for (int sub = 0; sub < renderer.sharedMesh.subMeshCount; sub++)
                        foreach (int index in renderer.sharedMesh.GetIndices(sub))
                        {
                            var point = renderer.transform.TransformPoint(vertices[index]);
                            if (!initialized) { bounds = new Bounds(point, Vector3.zero); initialized = true; }
                            else bounds.Encapsulate(point);
                        }
                }
                finally { Object.DestroyImmediate(baked); }
            }
            if (!initialized || bounds.size.y < .01f || float.IsNaN(bounds.size.y) || float.IsInfinity(bounds.size.y))
                throw new InvalidOperationException("Avatar has no measurable visible geometry.");
            return bounds;
        }
        static float Number(object o, string n, float fallback) { var v = Read(o, n); return v == null ? fallback : Convert.ToSingle(v); }
        static float[] V(Vector3 v) => new[] { v.x, v.y, v.z };
        static float[] V2(Vector2 v) => new[] { v.x, v.y };
        static float[] Q(Quaternion q) => new[] { q.x, q.y, q.z, q.w };
        static float[] C(Color c) => new[] { c.r, c.g, c.b, c.a };
        // Column-major, directly compatible with JOML Matrix4f.set(float[]).
        static float[] M(Matrix4x4 m) => Enumerable.Range(0, 16).Select(i => m[i]).ToArray();
        static void WV(BinaryWriter w, Vector3 v) { w.Write(v.x); w.Write(v.y); w.Write(v.z); }
        static void Write(ZipArchive z, string n, byte[] b) { using (var s = z.CreateEntry(n, CompressionLevel.Optimal).Open()) s.Write(b, 0, b.Length); }
        static void WriteJson(ZipArchive z, string n, object o) => Write(z, n, Encoding.UTF8.GetBytes(JsonConvert.SerializeObject(o, Json)));
        static byte[] TexturePng(Texture texture, bool normal = false, bool linear = false)
        {
            int w = Math.Min(texture.width, 4096), h = Math.Min(texture.height, 4096);
            var rt = RenderTexture.GetTemporary(w, h, 0, RenderTextureFormat.ARGB32, linear ? RenderTextureReadWrite.Linear : RenderTextureReadWrite.sRGB);
            var old = RenderTexture.active; bool oldSrgb = GL.sRGBWrite; Texture2D copy = null; Material decode = null;
            try {
                GL.sRGBWrite = !linear && QualitySettings.activeColorSpace == ColorSpace.Linear;
                if (normal) {
                    var shader = Shader.Find("Hidden/AvatarCraft/ExportNormal");
                    if (!shader) throw new InvalidOperationException("Missing B.E.P 3DAvater Exporter normal export shader");
                    decode = new Material(shader);
                    Graphics.Blit(texture, rt, decode);
                } else Graphics.Blit(texture, rt);
                RenderTexture.active = rt;
                copy = new Texture2D(w, h, TextureFormat.RGBA32, false, linear);
                copy.ReadPixels(new Rect(0, 0, w, h), 0, 0); copy.Apply(); return copy.EncodeToPNG();
            }
            finally { GL.sRGBWrite = oldSrgb; RenderTexture.active = old; RenderTexture.ReleaseTemporary(rt); if (copy) Object.DestroyImmediate(copy); if (decode) Object.DestroyImmediate(decode); }
        }
        static byte[] RenderPreview(GameObject avatar)
        {
            var utility = new PreviewRenderUtility();
            Texture2D image = null;
            try
            {
                var renderers = avatar.GetComponentsInChildren<Renderer>().Where(r => r.enabled).ToArray();
                var bounds = VisibleBounds(renderers.OfType<SkinnedMeshRenderer>().ToArray());
                utility.AddSingleGO(avatar); utility.cameraFieldOfView = 28;
                utility.camera.transform.position = bounds.center + new Vector3(0, bounds.size.y * .04f, bounds.size.y * 2.3f);
                utility.camera.transform.LookAt(bounds.center); utility.camera.nearClipPlane = .01f; utility.camera.farClipPlane = 100;
                utility.lights[0].intensity = 1.1f; utility.lights[0].transform.rotation = Quaternion.Euler(30, 30, 0);
                utility.lights[1].intensity = .6f; utility.ambientColor = new Color(.6f, .6f, .6f);
                utility.BeginStaticPreview(new Rect(0, 0, 512, 512)); utility.camera.Render(); image = utility.EndStaticPreview();
                return image.EncodeToPNG();
            }
            finally { if (image) Object.DestroyImmediate(image); utility.Cleanup(); }
        }
    }
}

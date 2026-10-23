using System;
using System.Collections.Generic;
using System.Linq;
using Newtonsoft.Json.Linq;
using UnityEditor;
using UnityEngine;

namespace AvatarCraft.Editor
{
    /// <summary>Avatar-specific baking of original anatomical keyframes and Unity animation clips.</summary>
    public static class MotionExport
    {
        static JObject library;
        public static JObject Library => library ?? (library = JObject.Parse(Resources.Load<TextAsset>("AvatarCraft/motions").text));
        public sealed class Key { public float time; public float[] rotation, position; }
        public sealed class Clip
        {
            public string name, layer;
            public string interpolation = "linear";
            public float duration;
            public bool loop;
            public Dictionary<string, Key[]> tracks = new Dictionary<string, Key[]>();
        }
        public sealed class Binding { public string clip, mirroredClip, layer; public float speed, blendSeconds; }
        public sealed class Pack
        {
            public int version = 1;
            public string space = "localDelta", library = "AvatarCraft Humanoid 1";
            public Dictionary<string, Key> neutral = new Dictionary<string, Key>();
            public Dictionary<string, Clip> clips = new Dictionary<string, Clip>();
            public Dictionary<string, Binding> actions = new Dictionary<string, Binding>();
        }
        sealed class Rest
        {
            public Transform bone;
            public Vector3 position, scale;
            public Quaternion rotation, neutralRotation, neutralWorld;
        }
        sealed class Snapshot
        {
            readonly Transform[] transforms;
            readonly Vector3[] positions, scales;
            readonly Quaternion[] rotations;
            readonly SkinnedMeshRenderer[] renderers;
            readonly float[][] shapes;
            public Snapshot(GameObject root)
            {
                transforms = root.GetComponentsInChildren<Transform>(true);
                positions = transforms.Select(t => t.localPosition).ToArray();
                scales = transforms.Select(t => t.localScale).ToArray();
                rotations = transforms.Select(t => t.localRotation).ToArray();
                renderers = root.GetComponentsInChildren<SkinnedMeshRenderer>(true).Where(r => r.sharedMesh).ToArray();
                shapes = renderers.Select(r => Enumerable.Range(0, r.sharedMesh.blendShapeCount).Select(r.GetBlendShapeWeight).ToArray()).ToArray();
            }
            public void Restore()
            {
                for (int i = 0; i < transforms.Length; i++) if (transforms[i]) { transforms[i].localPosition = positions[i]; transforms[i].localRotation = rotations[i]; transforms[i].localScale = scales[i]; }
                for (int r = 0; r < renderers.Length; r++) if (renderers[r]) for (int i = 0; i < shapes[r].Length; i++) renderers[r].SetBlendShapeWeight(i, shapes[r][i]);
            }
        }
        static Dictionary<string, Rest> Rig(GameObject root)
        {
            var animator = root.GetComponent<Animator>();
            if (!animator || !animator.isHuman) throw new InvalidOperationException("A Humanoid Animator is required.");
            var result = new Dictionary<string, Rest>();
            for (int i = 0; i < (int)HumanBodyBones.LastBone; i++)
            {
                var bone = animator.GetBoneTransform((HumanBodyBones)i); if (!bone) continue;
                result[((HumanBodyBones)i).ToString()] = new Rest { bone = bone, position = bone.localPosition, rotation = bone.localRotation, scale = bone.localScale };
            }
            foreach (string side in new[] { "Left", "Right" })
            {
                if (!result.TryGetValue(side + "UpperArm", out var arm) || !result.TryGetValue(side + "LowerArm", out var elbow)) continue;
                var direction = elbow.bone.position - arm.bone.position;
                if (direction.sqrMagnitude < 1e-10f) continue;
                float sign = Mathf.Sign(root.transform.InverseTransformDirection(direction).x);
                if (sign == 0) sign = side == "Left" ? 1 : -1;
                var target = root.transform.TransformDirection(new Vector3(sign * .06f, -1, 0)).normalized;
                arm.bone.rotation = Quaternion.FromToRotation(direction.normalized, target) * arm.bone.rotation;
            }
            foreach (var rest in result.Values)
            {
                rest.neutralRotation = rest.bone.localRotation;
                rest.neutralWorld = Quaternion.Inverse(root.transform.rotation) * rest.bone.rotation;
            }
            foreach (var rest in result.Values) rest.bone.localRotation = rest.rotation;
            return result;
        }
        static bool Upper(string bone) => bone == "Spine" || bone == "Chest" || bone == "UpperChest" || bone == "Neck" || bone == "Head"
            || bone.Contains("Shoulder") || bone.Contains("Arm") || bone.Contains("Hand") || bone.Contains("Thumb") || bone.Contains("Index") || bone.Contains("Middle") || bone.Contains("Ring") || bone.Contains("Little");
        static string MirrorName(string bone) => bone.StartsWith("Left", StringComparison.Ordinal) ? "Right" + bone.Substring(4) : bone.StartsWith("Right", StringComparison.Ordinal) ? "Left" + bone.Substring(5) : bone;
        static Quaternion Mirror(Quaternion q) => new Quaternion(q.x, -q.y, -q.z, q.w);
        static float[] Q(Quaternion q) { q = q.normalized; return new[] { q.x, q.y, q.z, q.w }; }
        static float[] V(Vector3 v) => new[] { v.x, v.y, v.z };
        static Vector3 Vec(JToken v) => v == null ? Vector3.zero : new Vector3((float)v[0], (float)v[1], (float)v[2]);
        static float Height(GameObject root)
        {
            var rs = root.GetComponentsInChildren<Renderer>().Where(r => r.enabled).ToArray();
            if (rs.Length == 0) return 1.8f;
            var b = rs[0].bounds; foreach (var r in rs) b.Encapsulate(r.bounds); return Mathf.Max(.1f, b.size.y);
        }

        public static Pack Build(GameObject root, AvatarCraftMotionProfile profile)
        {
            var snapshot = new Snapshot(root);
            try
            {
                var rig = Rig(root); float height = Height(root); var pack = new Pack();
                foreach (var pair in rig) pack.neutral[pair.Key] = new Key { time = 0, rotation = Q(Quaternion.Inverse(pair.Value.rotation) * pair.Value.neutralRotation), position = new float[3] };
                var slots = profile ? profile.motions : new List<AvatarCraftMotionSlot>();
                foreach (var action in Library["actions"].Children<JProperty>())
                {
                    var slot = slots.SingleOrDefault(s => s.action == action.Name) ?? new AvatarCraftMotionSlot { action = action.Name, builtin = (string)action.Value["clip"], blendSeconds = AvatarCraftMotionProfile.DefaultBlend(action.Name) };
                    if (!float.IsFinite(slot.speed) || slot.speed < .1f || slot.speed > 4 || !float.IsFinite(slot.blendSeconds) || slot.blendSeconds < 0 || slot.blendSeconds > 2) throw new InvalidOperationException("Invalid motion speed/blend: " + action.Name);
                    string layer = (string)action.Value["layer"];
                    string id = slot.customClip ? "custom_" + slot.customClip.GetInstanceID().ToString().Replace("-", "n") + "_" + layer : slot.builtin;
                    if (string.IsNullOrEmpty(id)) throw new InvalidOperationException("Missing motion: " + action.Name);
                    if (!pack.clips.ContainsKey(id))
                    {
                        if (slot.customClip)
                        {
                            var clip = BakeCustom(root, rig, snapshot, slot.customClip, layer); pack.clips[id] = clip;
                            pack.clips[id + "_left"] = MirrorBaked(clip, rig, root.transform.rotation);
                        }
                        else
                        {
                            var definition = Library["clips"][id];
                            if (definition == null || (string)definition["layer"] != layer) throw new InvalidOperationException("Motion layer mismatch: " + action.Name);
                            pack.clips[id] = BakeBuiltin(root, rig, definition, height, false);
                            pack.clips[id + "_left"] = BakeBuiltin(root, rig, definition, height, true);
                        }
                    }
                    pack.actions[action.Name] = new Binding { clip = id, mirroredClip = id + "_left", layer = layer, speed = slot.speed, blendSeconds = slot.blendSeconds };
                }
                int keyCount = pack.clips.Values.Sum(c => c.tracks.Values.Sum(keys => keys.Length));
                if (keyCount > 120000) throw new InvalidOperationException("Motion data exceeds 120000 keys. Use shorter or shared custom clips.");
                return pack;
            }
            finally { snapshot.Restore(); }
        }

        static Clip BakeBuiltin(GameObject root, Dictionary<string, Rest> rig, JToken definition, float height, bool mirrored)
        {
            string layer = (string)definition["layer"];
            var frames = definition["frames"].ToArray();
            var sourceBones = new HashSet<string>(frames.SelectMany(f => ((JObject)f["rotations"]).Properties().Select(p => p.Name).Concat(((JObject)f["positions"]).Properties().Select(p => p.Name))));
            var bones = layer == "base" ? rig.Keys.ToArray() : sourceBones.Where(b => layer != "upper" || Upper(b)).ToArray();
            var clip = new Clip { name = (string)definition["name"], duration = (float)definition["duration"], loop = (bool)definition["loop"], layer = layer, interpolation = "smooth" };
            foreach (var sourceBone in bones)
            {
                string name = mirrored ? MirrorName(sourceBone) : sourceBone;
                if (!rig.TryGetValue(name, out var rest)) continue;
                clip.tracks[name] = frames.Select(f =>
                {
                    Quaternion canonical = Quaternion.Euler(Vec(f["rotations"][sourceBone])); if (mirrored) canonical = Mirror(canonical);
                    Quaternion localDelta = Quaternion.Inverse(rest.neutralWorld) * canonical * rest.neutralWorld;
                    Quaternion target = layer == "additive" ? localDelta : Quaternion.Inverse(rest.rotation) * rest.neutralRotation * localDelta;
                    Vector3 offset = Vec(f["positions"][sourceBone]) * height; if (mirrored) offset.x = -offset.x;
                    var worldOffset = root.transform.rotation * offset;
                    var localOffset = rest.bone.parent ? rest.bone.parent.InverseTransformVector(worldOffset) : worldOffset;
                    return new Key { time = (float)f["time"], rotation = Q(target), position = V(localOffset) };
                }).ToArray();
            }
            return clip;
        }

        static Clip BakeCustom(GameObject root, Dictionary<string, Rest> rig, Snapshot snapshot, AnimationClip source, string layer)
        {
            if (source.length <= 0 || source.length > 30) throw new InvalidOperationException("Custom motions must be 0–30 seconds long: " + source.name);
            var clip = new Clip { name = source.name, duration = source.length, loop = AnimationUtility.GetAnimationClipSettings(source).loopTime, layer = layer };
            var names = rig.Keys.Where(b => layer != "upper" || Upper(b)).ToArray();
            int frames = Mathf.Max(2, Mathf.CeilToInt(source.length * 30) + 1);
            foreach (var name in names) clip.tracks[name] = new Key[frames];
            var initialRotation = new Dictionary<string, Quaternion>(); var initialPosition = new Dictionary<string, Vector3>();
            var sample = PrepareSampleClip(root, source);
            try
            {
                for (int i = 0; i < frames; i++)
                {
                    snapshot.Restore(); float time = source.length * i / (frames - 1);
                    SampleClip(root, sample, time);
                    foreach (var name in names)
                    {
                        var rest = rig[name]; var bone = rest.bone;
                        if (i == 0) { initialRotation[name] = bone.localRotation; initialPosition[name] = bone.localPosition; }
                        Quaternion q = Quaternion.Inverse(layer == "additive" ? initialRotation[name] : rest.rotation) * bone.localRotation;
                        Vector3 p = bone.localPosition - (layer == "additive" ? initialPosition[name] : rest.position);
                        if (name == "Hips" && layer != "additive")
                        {
                            var first = initialPosition[name] - rest.position;
                            var world = bone.parent ? bone.parent.TransformVector(first) : first;
                            var horizontal = Quaternion.Inverse(root.transform.rotation) * world; horizontal.y = 0;
                            world = root.transform.rotation * horizontal;
                            p -= bone.parent ? bone.parent.InverseTransformVector(world) : world;
                        }
                        clip.tracks[name][i] = new Key { time = time, rotation = Q(q), position = V(p) };
                    }
                }
            }
            finally { if (sample != source) UnityEngine.Object.DestroyImmediate(sample); }
            snapshot.Restore(); return clip;
        }

        static Clip MirrorBaked(Clip source, Dictionary<string, Rest> rig, Quaternion rootRotation)
        {
            var result = new Clip { name = source.name + " (left)", duration = source.duration, loop = source.loop, layer = source.layer };
            foreach (var track in source.tracks)
            {
                string name = MirrorName(track.Key); if (!rig.ContainsKey(name)) continue;
                var from = rig[track.Key]; var to = rig[name];
                result.tracks[name] = track.Value.Select(k =>
                {
                    var q = new Quaternion(k.rotation[0], k.rotation[1], k.rotation[2], k.rotation[3]);
                    bool additive = source.layer == "additive";
                    var fromNeutral = Quaternion.Inverse(from.rotation) * from.neutralRotation;
                    var toNeutral = Quaternion.Inverse(to.rotation) * to.neutralRotation;
                    var anatomical = additive ? q : Quaternion.Inverse(fromNeutral) * q;
                    var canonical = from.neutralWorld * anatomical * Quaternion.Inverse(from.neutralWorld);
                    var local = (additive ? Quaternion.identity : toNeutral) * Quaternion.Inverse(to.neutralWorld) * Mirror(canonical) * to.neutralWorld;
                    var world = from.bone.parent ? from.bone.parent.TransformVector(new Vector3(k.position[0], k.position[1], k.position[2])) : new Vector3(k.position[0], k.position[1], k.position[2]);
                    var anatomicalPosition = Quaternion.Inverse(rootRotation) * world;
                    anatomicalPosition.x = -anatomicalPosition.x;
                    world = rootRotation * anatomicalPosition;
                    var p = to.bone.parent ? to.bone.parent.InverseTransformVector(world) : world;
                    return new Key { time = k.time, rotation = Q(local), position = V(p) };
                }).ToArray();
            }
            return result;
        }

        /// <summary>Supply omitted Humanoid body curves from the target rig, without changing the asset.</summary>
        public static AnimationClip PrepareSampleClip(GameObject root, AnimationClip source)
        {
            var animator = root.GetComponent<Animator>();
            if (!source.humanMotion || !animator || !animator.isHuman) return source;
            var names = new HashSet<string>(AnimationUtility.GetCurveBindings(source).Where(b => b.path == "" && b.type == typeof(Animator)).Select(b => b.propertyName));
            var required = new[] { "RootT.x", "RootT.y", "RootT.z", "RootQ.x", "RootQ.y", "RootQ.z", "RootQ.w" };
            if (required.All(names.Contains)) return source;
            var handler = new HumanPoseHandler(animator.avatar, root.transform);
            var pose = new HumanPose();
            try { handler.GetHumanPose(ref pose); } finally { handler.Dispose(); }
            var clip = UnityEngine.Object.Instantiate(source); clip.hideFlags = HideFlags.HideAndDontSave;
            for (int i = 0; i < required.Length; i++) if (!names.Contains(required[i]))
            {
                float value = i < 3 ? pose.bodyPosition[i] : pose.bodyRotation[i - 3];
                AnimationUtility.SetEditorCurve(clip, EditorCurveBinding.FloatCurve("", typeof(Animator), required[i]), AnimationCurve.Constant(0, source.length, value));
            }
            return clip;
        }

        /// <summary>Sample a stationary target; preserve FBX armature transforms and missing Humanoid body curves.</summary>
        public static void SampleClip(GameObject root, AnimationClip clip, float time)
        {
            var animator = root.GetComponent<Animator>();
            var avatar = animator ? animator.avatar : null;
            var position = root.transform.localPosition; var rotation = root.transform.localRotation; var scale = root.transform.localScale;
            var sample = PrepareSampleClip(root, clip);
            try
            {
                if (animator && !clip.humanMotion) animator.avatar = null;
                sample.SampleAnimation(root, time);
            }
            finally
            {
                if (animator && animator.avatar != avatar) animator.avatar = avatar;
                root.transform.localPosition = position; root.transform.localRotation = rotation; root.transform.localScale = scale;
                if (sample != clip) UnityEngine.Object.DestroyImmediate(sample);
            }
        }

        public static AnimationClip CreateBuiltinClip(GameObject root, string id)
        {
            var snapshot = new Snapshot(root);
            try
            {
                var rig = Rig(root); var data = BakeBuiltin(root, rig, Library["clips"][id] ?? throw new ArgumentException("Unknown motion " + id), Height(root), false);
                // Editable clips can also be previewed alone. In game their upper-body/additive
                // masks are applied during baking, so this neutral baseline never moves the legs.
                if (data.layer != "base") foreach (var pair in rig)
                {
                    if (data.tracks.ContainsKey(pair.Key)) continue;
                    var neutral = data.layer == "additive" ? Quaternion.identity : Quaternion.Inverse(pair.Value.rotation) * pair.Value.neutralRotation;
                    data.tracks[pair.Key] = new[] {
                        new Key { time = 0, rotation = Q(neutral), position = new float[3] },
                        new Key { time = data.duration, rotation = Q(neutral), position = new float[3] }
                    };
                }
                var clip = new AnimationClip { name = id, frameRate = 30 };
                foreach (var track in data.tracks)
                {
                    var rest = rig[track.Key]; var path = AnimationUtility.CalculateTransformPath(rest.bone, root.transform);
                    var samples = track.Value.Select(k => new { key = k, q = (data.layer == "additive" ? rest.neutralRotation : rest.rotation) * new Quaternion(k.rotation[0], k.rotation[1], k.rotation[2], k.rotation[3]), p = rest.position + new Vector3(k.position[0], k.position[1], k.position[2]) }).ToArray();
                    for (int channel = 0; channel < 4; channel++)
                    {
                        int c = channel; var curve = new AnimationCurve(samples.Select(s => new Keyframe(s.key.time, s.q[c])).ToArray());
                        for (int i = 0; i < curve.length; i++) { AnimationUtility.SetKeyLeftTangentMode(curve, i, AnimationUtility.TangentMode.ClampedAuto); AnimationUtility.SetKeyRightTangentMode(curve, i, AnimationUtility.TangentMode.ClampedAuto); }
                        AnimationUtility.SetEditorCurve(clip, EditorCurveBinding.FloatCurve(path, typeof(Transform), "m_LocalRotation." + "xyzw"[c]), curve);
                    }
                    for (int channel = 0; channel < 3; channel++)
                    {
                        int c = channel; var curve = new AnimationCurve(samples.Select(s => new Keyframe(s.key.time, s.p[c])).ToArray());
                        for (int i = 0; i < curve.length; i++) { AnimationUtility.SetKeyLeftTangentMode(curve, i, AnimationUtility.TangentMode.ClampedAuto); AnimationUtility.SetKeyRightTangentMode(curve, i, AnimationUtility.TangentMode.ClampedAuto); }
                        AnimationUtility.SetEditorCurve(clip, EditorCurveBinding.FloatCurve(path, typeof(Transform), "m_LocalPosition." + "xyz"[c]), curve);
                    }
                }
                clip.EnsureQuaternionContinuity(); var settings = AnimationUtility.GetAnimationClipSettings(clip); settings.loopTime = data.loop; AnimationUtility.SetAnimationClipSettings(clip, settings);
                return clip;
            }
            finally { snapshot.Restore(); }
        }
    }
}

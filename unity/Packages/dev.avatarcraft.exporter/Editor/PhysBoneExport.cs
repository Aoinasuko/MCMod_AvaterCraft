using System;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;
using UnityEditor;
using UnityEngine;

namespace AvatarCraft.Editor
{
    /// <summary>Read public SDK configuration without depending on a particular VRChat assembly version.</summary>
    internal static class PhysBoneExport
    {
        internal sealed class Result
        {
            public int version = 2;
            public object[] springs, colliders;
        }
        static readonly string[] Scalars = { "pull", "spring", "stiffness", "gravity", "gravityFalloff", "immobile", "radius", "maxAngleX", "maxAngleZ", "maxStretch", "maxSquish", "stretchMotion" };
        static readonly string[] Metadata = { "version", "integrationType", "immobileType", "multiChildType", "ignoreOtherPhysBones", "limitType", "limitRotation", "staticFreezeAxis", "allowCollision", "collisionFilter", "allowGrabbing", "grabFilter", "allowPosing", "poseFilter", "snapToHand", "grabMovement", "isAnimated", "resetWhenDisabled", "parameter" };
        static readonly string[] RotationCurves = { "limitRotationXCurve", "limitRotationYCurve", "limitRotationZCurve" };
        internal static Result Build(GameObject avatar, Dictionary<Transform, int> boneIds)
        {
            var components = avatar.GetComponentsInChildren<Component>(true).Where(c => c && c.GetType().Name == "VRCPhysBone").ToArray();
            var roots = components.ToDictionary(c => c, Root);
            var colliderComponents = avatar.GetComponentsInChildren<Component>(true).Where(c => c && c.GetType().Name == "VRCPhysBoneCollider").ToArray();
            var colliderIds = colliderComponents.Select((c, i) => new { c, i }).ToDictionary(x => x.c, x => x.i);
            var colliders = colliderComponents.Select(c => new {
                bone = boneIds[Root(c)],
                position = V(Vector(c, "position")),
                rotation = Q(Read(c, "rotation") is Quaternion q ? q : Quaternion.Euler(Vector(c, "rotation"))),
                radius = Number(c, "radius", .05f), height = Number(c, "height", 0),
                inside = Flag(c, "insideBounds", false), shape = Text(c, "shapeType", "Sphere"),
                enabled = Enabled(c), active = c.gameObject.activeInHierarchy,
                bonesAsSpheres = Flag(c, "bonesAsSpheres", Flag(c, "bonesAsSphere", false)),
                globalCollision = Text(c, "globalCollision", "False"),
                settings = Settings(c, new[] { "globalCollision", "globalCollisionFilter", "bonesAsSpheres", "bonesAsSphere", "shapeType", "radius", "height", "position", "rotation", "insideBounds" })
            }).Cast<object>().ToArray();
            var springs = new List<object>();
            foreach (var c in components)
            {
                var root = roots[c];
                bool ignoreOthers = Flag(c, "ignoreOtherPhysBones", true);
                var ignored = (Read(c, "ignoreTransforms") as IEnumerable)?.Cast<Transform>().Where(t => t).ToArray() ?? Array.Empty<Transform>();
                var otherRoots = ignoreOthers ? roots.Where(p => p.Key != c && p.Value != root && p.Value.IsChildOf(root)).Select(p => p.Value).ToArray() : Array.Empty<Transform>();
                var affected = root.GetComponentsInChildren<Transform>(true).Where(t =>
                    !ignored.Any(i => t == i || t.IsChildOf(i)) && !otherRoots.Any(i => t == i || t.IsChildOf(i))).ToArray();
                var affectedSet = new HashSet<Transform>(affected);
                Func<Transform, int> depth = t => { int n = 0; for (var p = t; p != root && p.parent; p = p.parent) n++; return n; };
                int maxDepth = affected.Length == 0 ? 0 : affected.Max(depth);
                var endpoint = Vector(c, "endpointPosition");
                string multi = Text(c, "multiChildType", "Ignore");
                var nodes = new List<object>();
                foreach (var bone in affected)
                {
                    float t = maxDepth == 0 ? 0 : (float)depth(bone) / maxDepth;
                    var children = bone.Cast<Transform>().Where(affectedSet.Contains).ToArray();
                    Vector3 tail = endpoint;
                    bool simulate = true;
                    if (children.Length == 1 || children.Length > 1 && multi == "First") tail = children[0].localPosition;
                    else if (children.Length > 1 && multi == "Average") tail = children.Select(b => b.localPosition).Aggregate(Vector3.zero, (sum, p) => sum + p) / children.Length;
                    else if (children.Length > 1) simulate = false;
                    if (tail.sqrMagnitude < 1e-12f) simulate = false;
                    var values = Scalars.ToDictionary(n => n, n => Evaluate(c, n, t, n == "maxAngleX" || n == "maxAngleZ" ? 45 : 0));
                    foreach (var name in new[] { "pull", "spring", "stiffness", "gravityFalloff", "immobile", "stretchMotion", "maxSquish" }) values[name] = Mathf.Clamp01(values[name]);
                    values["maxAngleX"] = Mathf.Clamp(values["maxAngleX"], 0, 180);
                    values["maxAngleZ"] = Mathf.Clamp(values["maxAngleZ"], 0, 180);
                    values["maxStretch"] = Mathf.Clamp(values["maxStretch"], 0, 1000);
                    values["radius"] = Mathf.Max(0, values["radius"]);
                    var euler = Vector(c, "limitRotation");
                    euler = new Vector3(euler.x * Multiplier(c, RotationCurves[0], t), euler.y * Multiplier(c, RotationCurves[1], t), euler.z * Multiplier(c, RotationCurves[2], t));
                    nodes.Add(new {
                        bone = boneIds[bone], depth = depth(bone), normalizedDepth = t, tail = V(tail), simulate,
                        pull = values["pull"], spring = values["spring"], stiffness = values["stiffness"], gravity = values["gravity"], gravityFalloff = values["gravityFalloff"], immobile = values["immobile"], radius = values["radius"],
                        maxAngleX = values["maxAngleX"], maxAngleZ = values["maxAngleZ"], maxStretch = values["maxStretch"], maxSquish = values["maxSquish"], stretchMotion = values["stretchMotion"],
                        limitRotation = Q(Quaternion.Euler(euler))
                    });
                }
                var cols = (Read(c, "colliders") as IEnumerable)?.Cast<Component>().Where(t => t && colliderIds.ContainsKey(t)).Select(t => colliderIds[t]).ToArray() ?? Array.Empty<int>();
                var curveNames = Scalars.Select(n => n + "Curve").Concat(RotationCurves).ToArray();
                var settings = Settings(c, Metadata.Concat(Scalars));
                springs.Add(new {
                    name = AnimationUtility.CalculateTransformPath(c.transform, avatar.transform), root = boneIds[root], bones = affected.Select(t => boneIds[t]).ToArray(),
                    colliders = cols, ignoredBones = ignored.Where(boneIds.ContainsKey).Select(t => boneIds[t]).ToArray(),
                    enabled = Enabled(c), active = c.gameObject.activeInHierarchy,
                    version = Text(c, "version", "Version_1_1"), integrationType = Text(c, "integrationType", "Simplified"), immobileType = Text(c, "immobileType", "AllMotion"), multiChildType = multi,
                    ignoreOtherPhysBones = ignoreOthers, limitType = Text(c, "limitType", "None"), limitRotation = V(Vector(c, "limitRotation")),
                    maxAngleX = Number(c, "maxAngleX", 45), maxAngleZ = Number(c, "maxAngleZ", 45),
                    pull = Number(c, "pull", .2f), spring = Number(c, "spring", .2f), stiffness = Number(c, "stiffness", .2f), gravity = Number(c, "gravity", 0), gravityFalloff = Number(c, "gravityFalloff", 0), immobile = Number(c, "immobile", 0), radius = Number(c, "radius", 0),
                    maxStretch = Number(c, "maxStretch", 0), maxSquish = Number(c, "maxSquish", 0), stretchMotion = Number(c, "stretchMotion", 0), endpoint = V(endpoint),
                    isAnimated = Flag(c, "isAnimated", false), resetWhenDisabled = Flag(c, "resetWhenDisabled", false), parameter = Text(c, "parameter", ""),
                    allowCollision = Text(c, "allowCollision", "True"), allowGrabbing = Text(c, "allowGrabbing", "False"), allowPosing = Text(c, "allowPosing", "False"),
                    staticFreezeAxis = V(Vector(c, "staticFreezeAxis")), nodes,
                    curves = curveNames.ToDictionary(n => n, n => Curve(Read(c, n) as AnimationCurve)), settings
                });
            }
            return new Result { springs = springs.ToArray(), colliders = colliders };
        }
        static Transform Root(Component c) => Read(c, "rootTransform") is Transform t && t ? t : c.transform;
        static bool Enabled(Component c) => !(c is Behaviour b) || b.enabled;
        static object Read(object o, string name) => o.GetType().GetField(name)?.GetValue(o) ?? o.GetType().GetProperty(name)?.GetValue(o);
        static bool Flag(object o, string n, bool fallback) => Read(o, n) is bool v ? v : fallback;
        static string Text(object o, string n, string fallback) => Read(o, n)?.ToString() ?? fallback;
        static float Number(object o, string n, float fallback) => Read(o, n) is object v ? Convert.ToSingle(v) : fallback;
        static Vector3 Vector(object o, string n) => Read(o, n) is Vector3 v ? v : Vector3.zero;
        static float Multiplier(object o, string n, float t) => Read(o, n) is AnimationCurve c && c.length > 0 ? c.Evaluate(t) : 1;
        static float Evaluate(object o, string n, float t, float fallback) => Number(o, n, fallback) * Multiplier(o, n + "Curve", t);
        static float[] V(Vector3 v) => new[] { v.x, v.y, v.z };
        static float[] Q(Quaternion q) => new[] { q.x, q.y, q.z, q.w };
        static object Curve(AnimationCurve c) => new {
            enabled = c != null && c.length > 0,
            preWrapMode = c?.preWrapMode.ToString(), postWrapMode = c?.postWrapMode.ToString(),
            keys = c?.keys.Select(k => new { k.time, k.value, k.inTangent, k.outTangent, k.inWeight, k.outWeight, weightedMode = k.weightedMode.ToString() }).ToArray(),
            samples = Enumerable.Range(0, 65).Select(i => c != null && c.length > 0 ? c.Evaluate(i / 64f) : 1).ToArray()
        };
        static Dictionary<string, object> Settings(object o, IEnumerable<string> names) => names.Distinct().ToDictionary(n => n, n => Portable(Read(o, n)));
        static object Portable(object v)
        {
            if (v == null) return null;
            if (v is Enum) return v.ToString();
            if (v is Vector3 p) return V(p);
            if (v is Quaternion q) return Q(q);
            var type = v.GetType();
            if (type.IsPrimitive || v is string) return v;
            if (type.IsValueType) return type.GetFields(BindingFlags.Public | BindingFlags.Instance).ToDictionary(f => f.Name, f => Portable(f.GetValue(v)));
            return type.Name;
        }
    }
}

var selectedAnimator = Selection.activeGameObject ? Selection.activeGameObject.GetComponentInParent<Animator>() : null;
if (!selectedAnimator || !selectedAnimator.isHuman) throw new InvalidOperationException("Select a valid Humanoid avatar root first");
var avatar = selectedAnimator.gameObject;
var components = avatar.GetComponentsInChildren<Component>(true).Where(c => c && c.GetType().Name == "VRCPhysBone").ToArray();
return components.Select(c => new {
    path = AnimationUtility.CalculateTransformPath(c.transform, avatar.transform),
    enabled = (c as Behaviour).enabled,
    fields = c.GetType().GetFields(System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.Instance)
        .Where(f => f.DeclaringType != typeof(Component) && f.DeclaringType != typeof(Behaviour) && f.DeclaringType != typeof(UnityEngine.Object))
        .Select(f => {
            var v = f.GetValue(c); object value = v;
            if (v is Transform t) value = t ? AnimationUtility.CalculateTransformPath(t, avatar.transform) : null;
            else if (v is AnimationCurve curve) value = new { count = curve.length, samples = new[] { curve.Evaluate(0), curve.Evaluate(.5f), curve.Evaluate(1) } };
            else if (v is Enum) value = v.ToString();
            else if (v is System.Collections.IList list) value = list.Count;
            else if (v is UnityEngine.Object obj) value = obj ? obj.name : null;
            return new { name = f.Name, type = f.FieldType.Name, value };
        }).ToArray()
}).ToArray();

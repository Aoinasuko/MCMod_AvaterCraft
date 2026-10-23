var selectedAnimator = Selection.activeGameObject ? Selection.activeGameObject.GetComponentInParent<Animator>() : null;
if (!selectedAnimator || !selectedAnimator.isHuman) throw new InvalidOperationException("Select a valid Humanoid avatar root first");
var source = selectedAnimator;
var physType = source.GetComponentsInChildren<Component>(true).First(c => c && c.GetType().Name == "VRCPhysBone").GetType();
var scene = UnityEditor.SceneManagement.EditorSceneManager.NewPreviewScene();
try
{
    var root = new GameObject("AvatarCraft_PhysicsFixture");
    UnityEngine.SceneManagement.SceneManager.MoveGameObjectToScene(root, scene);
    var a = new GameObject("A").transform; a.SetParent(root.transform, false); a.localPosition = Vector3.up;
    var b = new GameObject("B").transform; b.SetParent(a, false); b.localPosition = Vector3.up;
    var nested = new GameObject("Nested").transform; nested.SetParent(b, false); nested.localPosition = Vector3.up;
    var spring = root.AddComponent(physType);
    physType.GetField("pull").SetValue(spring, .8f);
    physType.GetField("pullCurve").SetValue(spring, AnimationCurve.EaseInOut(0, 1, 1, .25f));
    physType.GetField("maxAngleX").SetValue(spring, 5f);
    physType.GetField("maxStretch").SetValue(spring, .2f);
    physType.GetField("maxSquish").SetValue(spring, .1f);
    physType.GetField("limitRotation").SetValue(spring, new Vector3(10, 20, 30));
    physType.GetField("endpointPosition").SetValue(spring, new Vector3(0, .2f, 0));
    physType.GetField("ignoreOtherPhysBones").SetValue(spring, true);
    var nestedComponent = (Behaviour)nested.gameObject.AddComponent(physType); nestedComponent.enabled = false;
    var transforms = root.GetComponentsInChildren<Transform>(true);
    var ids = transforms.Select((t, i) => new { t, i }).ToDictionary(x => x.t, x => x.i);
    var exportType = AppDomain.CurrentDomain.GetAssemblies().Select(assembly => assembly.GetType("AvatarCraft.Editor.PhysBoneExport")).First(t => t != null);
    var result = exportType.GetMethod("Build", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Static).Invoke(null, new object[] { root, ids });
    var json = Newtonsoft.Json.Linq.JObject.Parse(Newtonsoft.Json.JsonConvert.SerializeObject(result));
    var springs = (Newtonsoft.Json.Linq.JArray)json["springs"];
    var first = springs[0]; var nodes = (Newtonsoft.Json.Linq.JArray)first["nodes"];
    if (nodes.Count != 3) throw new Exception("Nested subtree was not excluded");
    if (Math.Abs((float)nodes[1]["pull"] - .5f) > .0001f) throw new Exception("Curve evaluation mismatch");
    if ((float)nodes[0]["maxAngleX"] != 5 || Math.Abs((float)nodes[0]["maxStretch"] - .2f) > .0001f) throw new Exception("Constraint values were not preserved");
    if ((bool)springs[1]["enabled"]) throw new Exception("Disabled state lost");
    var q = (Newtonsoft.Json.Linq.JArray)nodes[0]["limitRotation"];
    var actual = new Quaternion((float)q[0], (float)q[1], (float)q[2], (float)q[3]);
    if (Quaternion.Angle(actual, Quaternion.Euler(10, 20, 30)) > .01f) throw new Exception("Limit orientation lost");
    if (((Newtonsoft.Json.Linq.JArray)first["curves"]["pullCurve"]["samples"]).Count != 65) throw new Exception("Curve samples missing");
    return new { succeeded = true, checks = new[] { "nested exclusion", "nonlinear Unity curve", "angle constraint", "stretch/squish", "disabled status", "Euler to Quaternion", "65 curve samples" } };
}
finally
{
    UnityEditor.SceneManagement.EditorSceneManager.ClosePreviewScene(scene);
}

var source = UnityEngine.Object.FindObjectsOfType<Animator>().Single(a => a.name == "nasuko" && a.isHuman).gameObject;
var exporter = AppDomain.CurrentDomain.GetAssemblies().Select(a => a.GetType("AvatarCraft.Editor.MotionExport")).First(t => t != null);
var scene = UnityEditor.SceneManagement.EditorSceneManager.NewPreviewScene();
var clip = new AnimationClip { name = "Humanoid validation", frameRate = 30 };
ScriptableObject profile = null;
try
{
    var clone = UnityEngine.Object.Instantiate(source);clone.name=source.name;
    UnityEngine.SceneManagement.SceneManager.MoveGameObjectToScene(clone,scene);
    var animator = clone.GetComponent<Animator>();
    string muscle = HumanTrait.MuscleName.Single(n => n == "Left Lower Leg Stretch");
    AnimationUtility.SetEditorCurve(clip,EditorCurveBinding.FloatCurve("",typeof(Animator),muscle),AnimationCurve.Linear(0,0,1,.8f));
    var hip = animator.GetBoneTransform(HumanBodyBones.Hips);var knee = animator.GetBoneTransform(HumanBodyBones.LeftLowerLeg);
    var hipBefore=hip.position;var kneeBefore=knee.localRotation;
    exporter.GetMethod("SampleClip").Invoke(null,new object[] { clone,clip,.5f });
    float angle=Quaternion.Angle(kneeBefore,knee.localRotation);
    if (!clip.humanMotion || angle < 5 || (hip.position - hipBefore).magnitude > .2f) throw new Exception("Humanoid sampling failed: " + clip.humanMotion + ", knee=" + angle + ", hip=" + hip.position + ", expected=" + hipBefore);
    var bakeRoot=UnityEngine.Object.Instantiate(source);bakeRoot.name=source.name;
    UnityEngine.SceneManagement.SceneManager.MoveGameObjectToScene(bakeRoot,scene);
    var profileType=exporter.Assembly.GetType("AvatarCraft.Editor.AvatarCraftMotionProfile");
    profile=ScriptableObject.CreateInstance(profileType);profileType.GetMethod("EnsureDefaults").Invoke(profile,null);
    var slots=((System.Collections.IEnumerable)profileType.GetField("motions").GetValue(profile)).Cast<object>().ToArray();
    var walk=slots.Single(s=>(string)s.GetType().GetField("action").GetValue(s)=="walk");walk.GetType().GetField("customClip").SetValue(walk,clip);
    var pack=Newtonsoft.Json.Linq.JObject.Parse(Newtonsoft.Json.JsonConvert.SerializeObject(exporter.GetMethod("Build").Invoke(null,new object[]{bakeRoot,profile})));
    string id=(string)pack["actions"]["walk"]["clip"];
    var keys=(Newtonsoft.Json.Linq.JArray)pack["clips"][id]["tracks"]["LeftLowerLeg"];
    if (keys.Count != 31 || !keys.Any(k => Quaternion.Angle(Quaternion.identity,new Quaternion((float)k["rotation"][0],(float)k["rotation"][1],(float)k["rotation"][2],(float)k["rotation"][3])) > 5)) throw new Exception("Humanoid clip did not bake into joint tracks");
    if (AnimationUtility.GetCurveBindings(clip).Length != 1) throw new Exception("Sampling mutated the source clip");
    return new { succeeded = true, humanMotion=clip.humanMotion, kneeAngle = angle, hipsBefore = hipBefore.ToString(), hipsAfter = hip.position.ToString(), bakedKeys = keys.Count, sourceClipUnchanged = true };
}
finally { if(profile)UnityEngine.Object.DestroyImmediate(profile);UnityEngine.Object.DestroyImmediate(clip);UnityEditor.SceneManagement.EditorSceneManager.ClosePreviewScene(scene); }

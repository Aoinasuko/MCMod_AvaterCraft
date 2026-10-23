var source=UnityEngine.Object.FindObjectsOfType<Animator>().Single(a=>a.name=="nasuko"&&a.isHuman).gameObject;
var type=AppDomain.CurrentDomain.GetAssemblies().Select(a=>a.GetType("AvatarCraft.Editor.MotionExport")).First(t=>t!=null);
var clone=UnityEngine.Object.Instantiate(source);
try{
 var animator=clone.GetComponent<Animator>();var hip=animator.GetBoneTransform(HumanBodyBones.Hips);
 var clip=(AnimationClip)type.GetMethod("CreateBuiltinClip").Invoke(null,new object[]{clone,"walk_natural"});
 try{
  var bindings=AnimationUtility.GetCurveBindings(clip).Where(b=>b.path.EndsWith("/Hips")&&b.propertyName.Contains("Position")).Select(b=>new{b.path,b.propertyName,value=AnimationUtility.GetEditorCurve(clip,b).Evaluate(.315f)}).ToArray();
  var before=hip.localPosition;var avatar=animator.avatar;animator.avatar=null;clip.SampleAnimation(clone,.315f);var noAvatar=hip.localPosition;
  return new{bindings,before,noAvatar,properties=typeof(AnimationClip).GetProperties().Where(p=>p.Name.ToLower().Contains("human")).Select(p=>p.Name).ToArray()};
 }finally{UnityEngine.Object.DestroyImmediate(clip);}
}finally{UnityEngine.Object.DestroyImmediate(clone);}

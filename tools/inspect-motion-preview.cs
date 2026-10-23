var source=UnityEngine.Object.FindObjectsOfType<Animator>().Single(a=>a.name=="nasuko"&&a.isHuman).gameObject;
var type=AppDomain.CurrentDomain.GetAssemblies().Select(a=>a.GetType("AvatarCraft.Editor.MotionExport")).First(t=>t!=null);
var scene=UnityEditor.SceneManagement.EditorSceneManager.NewPreviewScene();
try {
 var outputs=new System.Collections.Generic.List<object>();
 foreach(var name in new[]{"idle_relaxed","walk_natural","run_jog","eat"}) {
  var clone=UnityEngine.Object.Instantiate(source);clone.transform.SetPositionAndRotation(Vector3.zero,Quaternion.identity);UnityEngine.SceneManagement.SceneManager.MoveGameObjectToScene(clone,scene);
  foreach(var b in clone.GetComponentsInChildren<Behaviour>(true))b.enabled=false;
  var renderer=clone.GetComponentsInChildren<SkinnedMeshRenderer>().First(r=>r.name=="Body");
  var before=renderer.bounds;var hip=clone.GetComponent<Animator>().GetBoneTransform(HumanBodyBones.Hips);var hp=hip.position;
  var clip=(AnimationClip)type.GetMethod("CreateBuiltinClip").Invoke(null,new object[]{clone,name});type.GetMethod("SampleClip").Invoke(null,new object[]{clone,clip,clip.length*.3f});
  var after=renderer.bounds;
  outputs.Add(new{name,root=clone.transform.position,scale=clone.transform.lossyScale,before=before.ToString(),after=after.ToString(),hipBefore=hp,hipAfter=hip.position,clipLength=clip.length,active=clone.activeInHierarchy,meshEnabled=renderer.enabled});
  UnityEngine.Object.DestroyImmediate(clip);UnityEngine.Object.DestroyImmediate(clone);
 }
 return outputs;
}finally{UnityEditor.SceneManagement.EditorSceneManager.ClosePreviewScene(scene);}

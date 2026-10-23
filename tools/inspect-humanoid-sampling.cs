var selectedAnimator = Selection.activeGameObject ? Selection.activeGameObject.GetComponentInParent<Animator>() : null;
if (!selectedAnimator || !selectedAnimator.isHuman) throw new InvalidOperationException("Select a valid Humanoid avatar root first");
var source = selectedAnimator.gameObject;
var clone=UnityEngine.Object.Instantiate(source);var clip=new AnimationClip();
try {
 var animator=clone.GetComponent<Animator>();var bones=new System.Collections.Generic.HashSet<Transform>();for(int i=0;i<(int)HumanBodyBones.LastBone;i++){var b=animator.GetBoneTransform((HumanBodyBones)i);if(b)bones.Add(b);}
 var transforms=clone.GetComponentsInChildren<Transform>(true);var positions=transforms.Select(t=>t.localPosition).ToArray();var scales=transforms.Select(t=>t.localScale).ToArray();var rotations=transforms.Select(t=>t.localRotation).ToArray();
 var handler=new HumanPoseHandler(animator.avatar,clone.transform);var pose=new HumanPose();handler.GetHumanPose(ref pose);
 AnimationUtility.SetEditorCurve(clip,EditorCurveBinding.FloatCurve("",typeof(Animator),"Left Lower Leg Stretch"),AnimationCurve.Linear(0,0,1,.8f));
 for(int c=0;c<3;c++) AnimationUtility.SetEditorCurve(clip,EditorCurveBinding.FloatCurve("",typeof(Animator),"RootT."+"xyz"[c]),AnimationCurve.Constant(0,1,pose.bodyPosition[c]));
 for(int c=0;c<4;c++) AnimationUtility.SetEditorCurve(clip,EditorCurveBinding.FloatCurve("",typeof(Animator),"RootQ."+"xyzw"[c]),AnimationCurve.Constant(0,1,pose.bodyRotation[c]));
 clip.SampleAnimation(clone,.5f);
 return new { humanBodyPosition=pose.bodyPosition.ToString(), changed=transforms.Select((t,i)=>new { name=t.name,human=bones.Contains(t),before=positions[i].ToString("F6"),after=t.localPosition.ToString("F6"),beforeScale=scales[i].ToString(),afterScale=t.localScale.ToString(),rotationAngle=Quaternion.Angle(rotations[i],t.localRotation)}).Where((v,i)=>(transforms[i].localPosition-positions[i]).sqrMagnitude>1e-10f||(transforms[i].localScale-scales[i]).sqrMagnitude>1e-10f||Quaternion.Angle(rotations[i],transforms[i].localRotation)>.1f).Take(20).ToArray() };
} finally {UnityEngine.Object.DestroyImmediate(clone);UnityEngine.Object.DestroyImmediate(clip);}

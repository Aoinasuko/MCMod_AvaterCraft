var source = UnityEngine.Object.FindObjectsOfType<Animator>().Single(a => a.name == "nasuko" && a.isHuman).gameObject;
var type = AppDomain.CurrentDomain.GetAssemblies().Select(a => a.GetType("AvatarCraft.Editor.AvatarCraftMotionProfile")).First(t => t != null);
var profile = (ScriptableObject)type.GetMethod("FindFor").Invoke(null,new object[]{source});
var slots=((System.Collections.IEnumerable)type.GetField("motions").GetValue(profile)).Cast<object>();
foreach(var slot in slots) {
 string action=(string)slot.GetType().GetField("action").GetValue(slot);
 var field=slot.GetType().GetField("blendSeconds");
 if(Math.Abs((float)field.GetValue(slot)-.18f)<.00001f && (action=="jump"||action=="fall"||action=="attack"||action=="mine")) field.SetValue(slot,action=="jump"?.06f:action=="fall"?.08f:.04f);
}
EditorUtility.SetDirty(profile);AssetDatabase.SaveAssets();return "Short jump/fall/attack/mine transitions saved; customized values retained";

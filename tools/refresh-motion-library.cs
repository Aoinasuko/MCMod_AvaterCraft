var exporter=AppDomain.CurrentDomain.GetAssemblies().Select(a=>a.GetType("AvatarCraft.Editor.MotionExport")).First(t=>t!=null);
exporter.GetField("library",System.Reflection.BindingFlags.NonPublic|System.Reflection.BindingFlags.Static).SetValue(null,null);
return "Motion library cache refreshed";

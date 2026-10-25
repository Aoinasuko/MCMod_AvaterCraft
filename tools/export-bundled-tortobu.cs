// Run through Unity MCP in the project containing the authorized tortobu model.
var roots = UnityEngine.SceneManagement.SceneManager.GetActiveScene().GetRootGameObjects().Where(x => x.name == "tortobu").ToArray();
if (roots.Length != 1) throw new InvalidOperationException("Expected one tortobu scene root");
var source = roots[0];
var animator = source.GetComponent<Animator>();
if (!animator || !animator.isHuman) throw new InvalidOperationException("tortobu must be Humanoid");
var type = AppDomain.CurrentDomain.GetAssemblies().Select(a => a.GetType("AvatarCraft.Editor.AvatarCraftExporter")).First(t => t != null);
var output = System.IO.Path.Combine(System.IO.Directory.GetParent(Application.dataPath).FullName, "AvatarCraftExports", "tortobu.avc");
return type.GetMethod("Export").Invoke(null, new object[] { source, output, true });

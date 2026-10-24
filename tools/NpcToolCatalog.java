import dev.avatarcraft.npc.ai.NpcTools;

/** Export the actual tool descriptions and argument schemas, without any host configuration. */
public class NpcToolCatalog {
  public static void main(String[] args) {
    System.out.println(
        new com.google.gson.GsonBuilder()
            .setPrettyPrinting()
            .create()
            .toJson(NpcTools.definitions(new java.util.TreeSet<>(NpcTools.names()))));
  }
}

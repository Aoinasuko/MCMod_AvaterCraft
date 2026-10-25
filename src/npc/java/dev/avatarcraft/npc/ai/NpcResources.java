package dev.avatarcraft.npc.ai;

import java.util.*;

/** Registry-based resource families. Language understanding remains with the instruction AI. */
public final class NpcResources {
  public static String normalize(String id) {
    return switch (id) {
      case "minecraft:log", "minecraft:logs", "minecraft:wood" -> "#minecraft:logs";
      case "minecraft:plank", "minecraft:planks" -> "#minecraft:planks";
      default -> id;
    };
  }

  /** Pure observation matching; the game supplies real registry tags, never guessed suffixes. */
  public static boolean matches(com.google.gson.JsonObject stack, String resource) {
    resource = normalize(resource);
    if (!resource.startsWith("#")) return stack.get("item").getAsString().equals(resource);
    if (stack.has("tags"))
      for (var tag : stack.getAsJsonArray("tags"))
        if (tag.getAsString().equals(resource.substring(1))) return true;
    return false;
  }

  private static final Map<String, String> ORES =
      Map.of(
          "diamond",
          "diamond_ores",
          "coal",
          "coal_ores",
          "raw_iron",
          "iron_ores",
          "raw_gold",
          "gold_ores",
          "raw_copper",
          "copper_ores",
          "redstone",
          "redstone_ores",
          "lapis_lazuli",
          "lapis_ores",
          "emerald",
          "emerald_ores");

  public static String search(TaskPlan.Step s) {
    if (s.action().equals("collect") && s.target().startsWith("#")) return s.target();
    if (s.action().equals("collect") && s.target().startsWith("minecraft:")) {
      var tag = ORES.get(s.target().substring(10));
      if (tag != null) return "#minecraft:" + tag;
    }
    return s.action().equals("mine") || s.action().equals("prepare_tool") ? s.target() : s.block();
  }

  public static boolean underground(TaskPlan.Step s) {
    return search(s).contains("_ore")
        || search(s).equals("minecraft:stone")
        || search(s).equals("minecraft:deepslate");
  }

  public static boolean diamond(TaskPlan.Step s) {
    return search(s).contains("diamond");
  }

  public static int preferredY(TaskPlan.Step s, int minimum, int current) {
    String block = search(s);
    if (block.contains("diamond") || block.contains("redstone")) return minimum + 12;
    if (block.contains("gold") || block.contains("lapis")) return Math.max(minimum + 12, 0);
    return Math.min(current, Math.max(minimum + 12, 32));
  }

  private NpcResources() {}
}

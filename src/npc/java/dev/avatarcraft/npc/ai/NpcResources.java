package dev.avatarcraft.npc.ai;

import java.util.*;

/** Registry-based resource families. Language understanding remains with the instruction AI. */
public final class NpcResources {
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

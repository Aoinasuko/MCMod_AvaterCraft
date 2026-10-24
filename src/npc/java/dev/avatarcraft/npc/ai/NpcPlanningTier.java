package dev.avatarcraft.npc.ai;

import java.util.Locale;

/** Conservative routing only; never translates a request into an executable action. */
public final class NpcPlanningTier {
  public static boolean advanced(String instruction) {
    String text = instruction.toLowerCase(Locale.ROOT);
    return text.length() > 120
        || java.util.List.of(
                "ネザー",
                "遺跡",
                "エンド",
                "ドラゴン",
                "協力",
                "仲間",
                "分担",
                "みんな",
                "nether",
                "fortress",
                "bastion",
                "stronghold",
                "dragon",
                "end portal",
                "together",
                "cooperat",
                "team",
                "delegate")
            .stream()
            .anyMatch(text::contains);
  }

  public static boolean cooperative(String instruction) {
    String text = instruction.toLowerCase(Locale.ROOT);
    return java.util.List.of("協力", "分担", "仲間", "みんな", "together", "cooperat", "team", "delegate")
        .stream()
        .anyMatch(text::contains);
  }

  private NpcPlanningTier() {}
}

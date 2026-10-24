package dev.avatarcraft.npc;

import net.neoforged.neoforge.common.ModConfigSpec;

/** World/server authority: clients cannot increase their own summon allowance. */
public final class NpcSettings {
  public static final ModConfigSpec SPEC;
  public static final ModConfigSpec.IntValue MAX_PER_PLAYER;
  public static final ModConfigSpec.BooleanValue DROP_ITEMS_ON_DEATH;

  static {
    var b = new ModConfigSpec.Builder();
    b.push("companions");
    MAX_PER_PLAYER =
        b.comment("Maximum summoned companions per owner across all dimensions. -1 is unlimited.")
            .defineInRange("maxPerPlayer", 3, -1, Integer.MAX_VALUE);
    DROP_ITEMS_ON_DEATH =
        b.comment(
                "Drop the NPC's inventory and equipment at its death position. False preserves them"
                    + " on respawn.")
            .define("dropItemsOnDeath", true);
    b.pop();
    SPEC = b.build();
  }

  private NpcSettings() {}
}

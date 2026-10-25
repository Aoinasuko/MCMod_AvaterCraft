package dev.avatarcraft.npc;

import java.util.*;

/** Companion events select Loader expression keys independently of Player Model. */
public final class CompanionExpressions {
  public static List<String> keys(boolean eating, boolean hurt) {
    var keys = new ArrayList<String>();
    if (eating) keys.add("eat");
    if (hurt) keys.add("hurt");
    return List.copyOf(keys);
  }
}

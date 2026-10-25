package dev.avatarcraft.player;

import java.util.*;

/** Player game events select Loader expression keys. Damage takes priority over eating. */
public final class PlayerExpressions {
  public static List<String> keys(boolean eating, boolean hurt, Collection<String> manual) {
    var keys = new ArrayList<String>(manual);
    if (eating) keys.add("eat");
    if (hurt) keys.add("hurt");
    return List.copyOf(keys);
  }
}

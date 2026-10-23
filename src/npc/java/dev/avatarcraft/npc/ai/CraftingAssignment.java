package dev.avatarcraft.npc.ai;

import java.util.*;

/** Bounded ingredient assignment; alternatives share the actual count of each inventory stack. */
public final class CraftingAssignment {
  public static Optional<int[]> find(List<int[]> candidates, int[] counts) {
    if (candidates.size() > 9 || counts.length > 36)
      throw new IllegalArgumentException("Crafting input exceeds its bounds");
    for (var options : candidates)
      for (int slot : options)
        if (slot < 0 || slot >= counts.length)
          throw new IllegalArgumentException("Invalid material slot");
    var order = new ArrayList<Integer>();
    for (int i = 0; i < candidates.size(); i++) order.add(i);
    order.sort(Comparator.comparingInt(i -> candidates.get(i).length));
    int[] result = new int[candidates.size()];
    Arrays.fill(result, -1);
    return assign(candidates, counts.clone(), order, 0, result, new int[] {10000})
        ? Optional.of(result)
        : Optional.empty();
  }

  private static boolean assign(
      List<int[]> candidates,
      int[] counts,
      List<Integer> order,
      int depth,
      int[] result,
      int[] budget) {
    if (--budget[0] < 0) return false;
    if (depth == order.size()) return true;
    int ingredient = order.get(depth);
    for (int slot : candidates.get(ingredient)) {
      if (counts[slot] <= 0) continue;
      counts[slot]--;
      result[ingredient] = slot;
      if (assign(candidates, counts, order, depth + 1, result, budget)) return true;
      counts[slot]++;
      result[ingredient] = -1;
    }
    return false;
  }

  private CraftingAssignment() {}
}

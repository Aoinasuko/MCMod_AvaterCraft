package dev.avatarcraft.npc;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Inserts into a bounded inventory region without deleting overflow (including creative players).
 */
public final class InventoryTransfer {
  public static ItemStack insert(Container target, ItemStack input, int from, int to) {
    var remaining = input.copy();
    for (int pass = 0; pass < 2 && !remaining.isEmpty(); pass++)
      for (int slot = from; slot < to && !remaining.isEmpty(); slot++) {
        if (!target.canPlaceItem(slot, remaining)) continue;
        var current = target.getItem(slot);
        if (pass == 0 ? current.isEmpty() : !current.isEmpty()) continue;
        if (!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, remaining)) continue;
        int capacity =
            Math.min(remaining.getMaxStackSize(), target.getMaxStackSize(remaining))
                - current.getCount();
        int amount = Math.min(remaining.getCount(), Math.max(0, capacity));
        if (amount == 0) continue;
        if (current.isEmpty()) target.setItem(slot, remaining.copyWithCount(amount));
        else current.grow(amount);
        remaining.shrink(amount);
        target.setChanged();
      }
    return remaining;
  }

  private InventoryTransfer() {}
}

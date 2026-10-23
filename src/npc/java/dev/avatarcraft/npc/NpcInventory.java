package dev.avatarcraft.npc;

import java.util.*;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** 36 bag slots, four armor slots, offhand, and a separate main hand. */
public final class NpcInventory implements Container {
  public static final int BAG_SIZE = 36, OFF_HAND = 40, MAIN_HAND = 41, SIZE = 42;
  private final CompanionEntity npc;
  private final NonNullList<ItemStack> bag = NonNullList.withSize(BAG_SIZE, ItemStack.EMPTY);
  private static final EquipmentSlot[] GEAR = {
    EquipmentSlot.HEAD,
    EquipmentSlot.CHEST,
    EquipmentSlot.LEGS,
    EquipmentSlot.FEET,
    EquipmentSlot.OFFHAND
  };

  NpcInventory(CompanionEntity npc) {
    this.npc = npc;
  }

  public int getContainerSize() {
    return SIZE;
  }

  public static EquipmentSlot equipment(int slot) {
    return slot == MAIN_HAND
        ? EquipmentSlot.MAINHAND
        : slot >= 36 && slot <= OFF_HAND ? GEAR[slot - 36] : null;
  }

  private void check(int slot) {
    if (slot < 0 || slot >= SIZE) throw new IndexOutOfBoundsException(slot);
  }

  public ItemStack getItem(int slot) {
    check(slot);
    var equipment = equipment(slot);
    return equipment == null ? bag.get(slot) : npc.getItemBySlot(equipment);
  }

  public void setItem(int slot, ItemStack stack) {
    check(slot);
    var equipment = equipment(slot);
    if (equipment == null) bag.set(slot, stack);
    else npc.setItemSlot(equipment, stack);
    setChanged();
  }

  public ItemStack removeItem(int slot, int count) {
    var stack = getItem(slot);
    var removed = stack.split(Math.max(0, count));
    if (stack.isEmpty()) setItem(slot, ItemStack.EMPTY);
    else setChanged();
    return removed;
  }

  public ItemStack removeItemNoUpdate(int slot) {
    var stack = getItem(slot);
    setItem(slot, ItemStack.EMPTY);
    return stack;
  }

  public boolean isEmpty() {
    for (int i = 0; i < SIZE; i++) if (!getItem(i).isEmpty()) return false;
    return true;
  }

  public void clearContent() {
    for (int i = 0; i < SIZE; i++) setItem(i, ItemStack.EMPTY);
  }

  public void setChanged() {}

  /** Swap complete stacks, including an empty slot to put the held item away. */
  public boolean equip(int source, boolean offhand) {
    if (source < 0 || source >= BAG_SIZE || npc.isUsingItem()) return false;
    int target = offhand ? OFF_HAND : MAIN_HAND;
    var hand = getItem(target);
    setItem(target, getItem(source));
    setItem(source, hand);
    return true;
  }

  public boolean stillValid(Player player) {
    return npc.isAlive() && npc.ownedBy(player) && npc.distanceToSqr(player) <= 64;
  }

  public void save(ValueOutput output) {
    ContainerHelper.saveAllItems(output, bag);
  }

  public void load(ValueInput input) {
    Collections.fill(bag, ItemStack.EMPTY);
    ContainerHelper.loadAllItems(input, bag);
  }

  public ItemStack insert(ItemStack input) {
    var remaining = input.copy();
    for (int i = 0; i < BAG_SIZE && !remaining.isEmpty(); i++) {
      var current = getItem(i);
      if (!current.isEmpty() && ItemStack.isSameItemSameComponents(current, remaining)) {
        int amount =
            Math.min(
                remaining.getCount(), Math.max(0, current.getMaxStackSize() - current.getCount()));
        current.grow(amount);
        remaining.shrink(amount);
      }
    }
    for (int i = 0; i < BAG_SIZE && !remaining.isEmpty(); i++) {
      if (getItem(i).isEmpty()) {
        int amount = Math.min(remaining.getCount(), remaining.getMaxStackSize());
        setItem(i, remaining.copyWithCount(amount));
        remaining.shrink(amount);
      }
    }
    return remaining;
  }
}

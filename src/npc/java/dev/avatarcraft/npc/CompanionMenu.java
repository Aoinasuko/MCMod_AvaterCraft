package dev.avatarcraft.npc;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;

public final class CompanionMenu extends AbstractContainerMenu {
  public final CompanionEntity npc;
  public boolean slotsVisible = true;
  public static final int GRID_X = 8, GRID_Y = 101;

  public CompanionMenu(int id, Inventory playerInventory, RegistryFriendlyByteBuf data) {
    this(id, playerInventory, requireNpc(playerInventory, data.readVarInt()));
  }

  private static CompanionEntity requireNpc(Inventory inventory, int id) {
    if (inventory.player.level().getEntity(id) instanceof CompanionEntity npc) return npc;
    throw new IllegalArgumentException("Companion entity no longer exists");
  }

  public CompanionMenu(int id, Inventory playerInventory, CompanionEntity npc) {
    super(NpcMod.COMPANION_MENU.get(), id);
    this.npc = npc;
    for (int row = 0; row < 4; row++)
      for (int col = 0; col < 9; col++)
        addSlot(
            visibleSlot(npc.inventory, row * 9 + col, GRID_X + col * 18, GRID_Y + row * 18, false));
    String[] armorIcons = {"helmet", "chestplate", "leggings", "boots"};
    for (int i = 36; i < NpcInventory.SIZE; i++) {
      var equipment = NpcInventory.equipment(i);
      if (i >= 40)
        addSlot(
            new Slot(npc.inventory, i, i == NpcInventory.MAIN_HAND ? 77 : 103, 64) {
              public boolean isActive() {
                return slotsVisible;
              }

              public Identifier getNoItemIcon() {
                return equipment == net.minecraft.world.entity.EquipmentSlot.OFFHAND
                    ? Identifier.withDefaultNamespace("container/slot/shield")
                    : null;
              }
            });
      else
        addSlot(
            new ArmorSlot(
                npc.inventory,
                npc,
                equipment,
                i,
                8,
                10 + (i - 36) * 18,
                Identifier.withDefaultNamespace("container/slot/" + armorIcons[i - 36])) {
              public boolean isActive() {
                return slotsVisible;
              }
            });
    }
    for (int row = 0; row < 3; row++)
      for (int col = 0; col < 9; col++)
        addSlot(
            visibleSlot(
                playerInventory, 9 + row * 9 + col, GRID_X + col * 18, 191 + row * 18, true));
    for (int col = 0; col < 9; col++)
      addSlot(visibleSlot(playerInventory, col, GRID_X + col * 18, 249, true));
  }

  private Slot visibleSlot(
      net.minecraft.world.Container inventory, int index, int x, int y, boolean player) {
    return new Slot(inventory, index, x, y) {
      public boolean isActive() {
        return slotsVisible;
      }
    };
  }

  public boolean stillValid(Player player) {
    return npc.inventory.stillValid(player);
  }

  public ItemStack quickMoveStack(Player player, int index) {
    if (index < 0 || index >= slots.size() || !stillValid(player)) return ItemStack.EMPTY;
    var slot = slots.get(index);
    if (!slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY;
    var original = slot.getItem();
    var copy = original.copy();
    if (index < NpcInventory.SIZE) {
      if (!moveItemStackTo(original, NpcInventory.SIZE, slots.size(), true)) return ItemStack.EMPTY;
    } else {
      boolean equipped = false;
      for (int i = 36; i < 40; i++)
        if (slots.get(i).mayPlace(original) && !slots.get(i).hasItem()) {
          equipped = moveItemStackTo(original, i, i + 1, false);
          break;
        }
      if (!equipped && !moveItemStackTo(original, 0, 36, false)) return ItemStack.EMPTY;
    }
    if (original.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
    else slot.setChanged();
    slot.onTake(player, original);
    return copy;
  }
}

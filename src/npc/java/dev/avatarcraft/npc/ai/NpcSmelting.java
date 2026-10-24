package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.JsonObject;
import dev.avatarcraft.npc.CompanionEntity;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;

/** Waits for vanilla block-entity ticks; no synthetic cooking or output generation. */
final class NpcSmelting implements AutoCloseable {
  private final CompanionEntity npc;
  private final NpcSkills skills;
  private final BlockPos position;
  private final Item output;
  private final int requested;
  private int moved;
  private final long deadline;
  final CompletableFuture<JsonObject> result = new CompletableFuture<>();

  static String key(ServerLevel level, BlockPos p) {
    return level.dimension().identifier() + "/" + p.getX() + "," + p.getY() + "," + p.getZ();
  }

  NpcSmelting(CompanionEntity npc, NpcSkills skills, BlockPos p, String item, int count) {
    this.npc = npc;
    this.skills = skills;
    position = p.immutable();
    output = NpcRecipes.item(item);
    requested = count;
    var level = (ServerLevel) npc.level();
    if (count < 1 || count > 64 || skills.furnace(p) == null)
      throw new IllegalArgumentException("Accessible furnace and count 1..64 required");
    var data = NpcWorkData.get(level);
    String key = key(level, p);
    var lease = data.furnaces.get(key);
    if (lease != null && !lease.equals(npc.getUUID()))
      throw new IllegalArgumentException("Furnace is reserved by another NPC");
    var furnace = skills.furnace(p);
    boolean resumed = npc.getUUID().equals(lease);
    if (!resumed && (!furnace.getItem(0).isEmpty() || !furnace.getItem(2).isEmpty()))
      throw new IllegalArgumentException("Furnace contains an unowned batch");
    if (!resumed) {
      int slot = -1;
      for (int i = 0; i < 36; i++) {
        var input = npc.inventory.getItem(i);
        if (input.getCount() < count) continue;
        var recipe =
            level
                .getServer()
                .getRecipeManager()
                .getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(input), level);
        if (recipe.isPresent()
            && recipe.get().value().assemble(new SingleRecipeInput(input)).is(output)) {
          slot = i;
          break;
        }
      }
      if (slot < 0) throw new IllegalArgumentException("Carried smelting input is missing");
      int fuel = -1;
      for (int i = 0; i < 36; i++)
        if (i != slot
            && !npc.inventory.getItem(i).isEmpty()
            && furnace.canPlaceItem(1, npc.inventory.getItem(i))) {
          fuel = i;
          break;
        }
      if (fuel < 0 && furnace.getItem(1).isEmpty())
        throw new IllegalArgumentException("Carried furnace fuel is missing");
      if (data.furnaces.size() >= 512)
        throw new IllegalArgumentException("Too many furnace leases");
      data.furnaces.put(key, npc.getUUID());
      data.setDirty();
      furnace.setItem(0, npc.inventory.removeItem(slot, count));
      if (furnace.getItem(1).isEmpty() && fuel >= 0)
        furnace.setItem(1, npc.inventory.removeItem(fuel, 64));
      furnace.setChanged();
    }
    deadline = level.getGameTime() + count * 400L + 1200;
  }

  void tick() {
    if (result.isDone()) return;
    var level = (ServerLevel) npc.level();
    var furnace = skills.furnace(position);
    if (furnace == null) {
      finish(false, "Furnace removed, protected or out of reach");
      return;
    }
    var stack = furnace.getItem(2);
    if (!stack.isEmpty() && !stack.is(output)) {
      finish(false, "Furnace output changed");
      return;
    }
    // Keep a partial batch in the persistent furnace until its entire requested output fits.
    // A save/interrupt therefore cannot lose an already-delivered partial count.
    int capacity = 0;
    for (int i = 0; i < 36; i++) {
      var slot = npc.inventory.getItem(i);
      if (slot.isEmpty()) capacity += stack.getMaxStackSize();
      else if (ItemStack.isSameItemSameComponents(slot, stack))
        capacity += Math.max(0, slot.getMaxStackSize() - slot.getCount());
    }
    if (stack.is(output)
        && stack.getCount() >= requested - moved
        && capacity >= requested - moved) {
      var inserted = stack.copyWithCount(Math.min(stack.getCount(), requested - moved));
      int n = inserted.getCount() - npc.inventory.insert(inserted).getCount();
      if (n > 0) {
        furnace.removeItem(2, n);
        moved += n;
        furnace.setChanged();
      }
    }
    if (moved >= requested) {
      // Return unused fuel, preserving real overflow inside the furnace.
      var fuel = furnace.getItem(1);
      int before = fuel.getCount();
      var remaining = npc.inventory.insert(fuel.copy());
      if (before > remaining.getCount()) furnace.removeItem(1, before - remaining.getCount());
      if (furnace.getItem(0).isEmpty() && furnace.getItem(2).isEmpty()) {
        var data = NpcWorkData.get(level);
        data.furnaces.remove(key(level, position));
        data.setDirty();
      }
      finish(true, "");
    } else if (level.getGameTime() > deadline)
      finish(false, "Smelting timed out: check fuel, output space and input");
  }

  private void finish(boolean ok, String problem) {
    result.complete(object("ok", ok, "moved", moved, "error", problem));
  }

  public void close() {
    if (!result.isDone())
      finish(false, "Smelting interrupted; real batch remains reserved in furnace");
  }
}

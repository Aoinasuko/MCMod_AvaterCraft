package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.JsonObject;
import dev.avatarcraft.npc.CompanionEntity;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.storage.loot.*;
import net.minecraft.world.level.storage.loot.parameters.*;
import net.minecraft.world.level.storage.loot.providers.number.floats.ResolvableFloat;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;
import net.minecraft.world.phys.Vec3;

/** Waits for vanilla block-entity ticks; no synthetic cooking or output generation. */
final class NpcSmelting implements AutoCloseable {
  static final class FuelMissing extends IllegalArgumentException {
    final long ticks;
    final int inputSlot, inputCount;

    FuelMissing(long ticks, int inputSlot, int inputCount) {
      super("FUEL_INSUFFICIENT: missing effective burn ticks=" + ticks);
      this.ticks = ticks;
      this.inputSlot = inputSlot;
      this.inputCount = inputCount;
    }
  }

  record FuelPlan(String item, int totalCount) {}

  static FuelPlan prepareCarriedFuel(
      CompanionEntity npc, FurnaceBlockEntity furnace, FuelMissing missing) {
    var level = (ServerLevel) npc.level();
    var outputCounts = new LinkedHashMap<Item, Integer>();
    for (int i = 0; i < dev.avatarcraft.npc.NpcInventory.SIZE; i++) {
      var stack = npc.inventory.getItem(i);
      if (!stack.is(net.minecraft.tags.ItemTags.LOGS)) continue;
      int available =
          Math.max(0, stack.getCount() - (i == missing.inputSlot ? missing.inputCount : 0));
      ItemStack best = ItemStack.EMPTY;
      long energy = 0;
      for (var holder : level.getServer().getRecipeManager().getRecipes()) {
        if (!(holder.value() instanceof CraftingRecipe recipe)) continue;
        var placement = recipe.placementInfo();
        if (placement.isImpossibleToPlace()
            || placement.ingredients().size() != 1
            || placement.slotsToIngredientIndex().intStream().filter(index -> index >= 0).count()
                != 1
            || !placement.ingredients().getFirst().test(stack)) continue;
        var output = NpcRecipes.output(recipe);
        if (!output.is(net.minecraft.tags.ItemTags.PLANKS)) continue;
        long candidate = (long) burn(level, furnace, output) * output.getCount();
        if (candidate > energy) {
          best = output;
          energy = candidate;
        }
      }
      if (!best.isEmpty() && available > 0)
        outputCounts.merge(best.getItem(), available * best.getCount(), Integer::sum);
    }
    for (var candidate : outputCounts.entrySet()) {
      int burn = burn(level, furnace, new ItemStack(candidate.getKey()));
      if (burn <= 0 || (long) candidate.getValue() * burn < missing.ticks) continue;
      int carried = 0;
      for (int i = 0; i < dev.avatarcraft.npc.NpcInventory.SIZE; i++)
        if (npc.inventory.getItem(i).is(candidate.getKey()))
          carried += npc.inventory.getItem(i).getCount();
      return new FuelPlan(
          NpcRecipes.id(candidate.getKey()), carried + (int) ((missing.ticks + burn - 1) / burn));
    }
    return null;
  }

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
    if (resumed && !furnace.getItem(0).isEmpty()) {
      var recipe =
          level
              .getServer()
              .getRecipeManager()
              .getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(furnace.getItem(0)), level);
      if (recipe.isEmpty()
          || !recipe.get().value().assemble(new SingleRecipeInput(furnace.getItem(0))).is(output))
        throw new IllegalArgumentException(
            "FURNACE_BATCH_MISMATCH: reserved input produces a different item");
      requireFuel(level, furnace, furnace.getItem(0), -1, 0);
    }
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
      requireFuel(level, furnace, npc.inventory.getItem(slot).copyWithCount(count), slot, count);
      if (data.furnaces.size() >= 512)
        throw new IllegalArgumentException("Too many furnace leases");
      data.furnaces.put(key, npc.getUUID());
      data.setDirty();
      furnace.setItem(0, npc.inventory.removeItem(slot, count));
      refill(level, furnace);
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
    if (level.getGameTime() % 20 == 0
        && !furnace.getItem(0).isEmpty()
        && (!furnace.getItem(2).is(output) || furnace.getItem(2).getCount() < requested - moved)) {
      try {
        requireFuel(level, furnace, furnace.getItem(0), -1, 0);
        refill(level, furnace);
      } catch (IllegalArgumentException e) {
        finish(false, e.getMessage() + "; partial batch remains reserved in furnace");
        return;
      }
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

  // Resolve the same component and container loot context used by vanilla; no fuel ID table.
  static int burn(ServerLevel level, FurnaceBlockEntity furnace, ItemStack stack) {
    if (stack.isEmpty()) return 0;
    var params =
        new LootParams.Builder(level)
            .withParameter(LootContextParams.BLOCK_STATE, furnace.getBlockState())
            .withParameter(LootContextParams.BLOCK_ENTITY, furnace)
            .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(furnace.getBlockPos()))
            .withParameter(LootContextParams.CONTAINER, furnace)
            .withOptionalParameter(
                net.neoforged.neoforge.common.loot.NeoForgeLootContextParams.QUERIED_STACK, stack)
            .create(LootContextParamSets.CONTAINER_PROCESS);
    var context = new LootContext.Builder(params).create(Optional.empty());
    int ticks =
        ResolvableInt.getFromItem(
            stack, DataComponents.COOKING_FUEL, CookingFuel::burnTime, context, 0);
    float speed =
        ResolvableFloat.getFromItem(
            stack, DataComponents.COOKING_FUEL, CookingFuel::speedMultiplier, context, 1f);
    return (int) Math.floor(ticks * Math.max(0, speed));
  }

  private static boolean safeFuel(ItemStack stack) {
    return !stack.isEmpty()
        // Four planks retain four times a log's furnace work. Keep convertible logs for the
        // normal resource planner rather than greedily consuming future batches' materials.
        && !stack.is(net.minecraft.tags.ItemTags.LOGS)
        && !stack.isDamageableItem()
        && !stack.has(DataComponents.TOOL)
        && !stack.has(DataComponents.EQUIPPABLE);
  }

  private static long needed(ServerLevel level, FurnaceBlockEntity furnace, ItemStack input) {
    if (input.isEmpty()) return 0;
    var recipe =
        level
            .getServer()
            .getRecipeManager()
            .getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(input), level);
    if (recipe.isEmpty())
      throw new IllegalArgumentException("Furnace input no longer matches requested output");
    var state = furnace.saveWithoutMetadata(level.registryAccess());
    int progress = state.getIntOr("cooking_time_spent", 0);
    float speed = state.getFloatOr("speed_multiplier", 1f);
    return Math.max(
        0,
        (long)
            Math.ceil(
                (long) recipe.get().value().cookingTime() * input.getCount()
                    - (progress + state.getIntOr("lit_time_remaining", 0)) * speed));
  }

  private void requireFuel(
      ServerLevel level,
      FurnaceBlockEntity furnace,
      ItemStack input,
      int reservedSlot,
      int reservedCount) {
    long deficit = fuelDeficit(npc, furnace, input, reservedSlot, reservedCount);
    if (deficit > 0) throw new FuelMissing(deficit, reservedSlot, reservedCount);
  }

  static long fuelDeficit(
      CompanionEntity npc,
      FurnaceBlockEntity furnace,
      ItemStack input,
      int reservedSlot,
      int reservedCount) {
    var level = (ServerLevel) npc.level();
    long required = needed(level, furnace, input);
    long available =
        (long) burn(level, furnace, furnace.getItem(1)) * furnace.getItem(1).getCount();
    for (int i = 0; i < dev.avatarcraft.npc.NpcInventory.SIZE; i++) {
      var stack = npc.inventory.getItem(i);
      if (safeFuel(stack))
        available +=
            (long) burn(level, furnace, stack)
                * Math.max(0, stack.getCount() - (i == reservedSlot ? reservedCount : 0));
    }
    return Math.max(0, required - available);
  }

  private void refill(ServerLevel level, FurnaceBlockEntity furnace) {
    if (!furnace.getItem(1).isEmpty() || furnace.getItem(0).isEmpty()) return;
    long required = needed(level, furnace, furnace.getItem(0));
    if (required <= 0) return;
    int best = -1, duration = 0;
    for (int i = 0; i < dev.avatarcraft.npc.NpcInventory.SIZE; i++) {
      var stack = npc.inventory.getItem(i);
      int candidate = safeFuel(stack) ? burn(level, furnace, stack) : 0;
      if (candidate > duration && furnace.canPlaceItem(1, stack)) {
        best = i;
        duration = candidate;
      }
    }
    if (best >= 0) {
      int count =
          (int)
              Math.min(
                  npc.inventory.getItem(best).getCount(), (required + duration - 1) / duration);
      furnace.setItem(1, npc.inventory.removeItem(best, count));
      furnace.setChanged();
    }
  }

  private void finish(boolean ok, String problem) {
    result.complete(object("ok", ok, "moved", moved, "error", problem));
  }

  public void close() {
    if (!result.isDone())
      finish(false, "Smelting interrupted; real batch remains reserved in furnace");
  }
}

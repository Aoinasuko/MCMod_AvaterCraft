package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.*;
import dev.avatarcraft.npc.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;

/** Recomputes the next dependency from real inventory after every verified operation. */
final class NpcSurvival implements AutoCloseable {
  private final CompanionEntity npc;
  private final NpcSkills skills;
  private final ServerLevel level;
  private final String target;
  private final int count;
  private LocalTaskRunner local;
  private long nextTick, deadline;
  private int operations;
  private final Set<BlockPos> rejectedPlacements = new HashSet<>();
  private final Set<BlockPos> rejectedApproaches = new HashSet<>();
  private TaskPlan.Step activeStep;
  final CompletableFuture<JsonObject> result = new CompletableFuture<>();
  private static final Map<String, String> SOURCES =
      Map.ofEntries(
          Map.entry("minecraft:diamond", "minecraft:diamond_ore"),
              Map.entry("minecraft:coal", "minecraft:coal_ore"),
          Map.entry("minecraft:raw_iron", "minecraft:iron_ore"),
              Map.entry("minecraft:raw_copper", "minecraft:copper_ore"),
          Map.entry("minecraft:raw_gold", "minecraft:gold_ore"),
              Map.entry("minecraft:redstone", "minecraft:redstone_ore"),
          Map.entry("minecraft:lapis_lazuli", "minecraft:lapis_ore"),
              Map.entry("minecraft:emerald", "minecraft:emerald_ore"),
          Map.entry("minecraft:cobblestone", "minecraft:stone"),
              Map.entry("minecraft:flint", "minecraft:gravel"),
          Map.entry("minecraft:quartz", "minecraft:nether_quartz_ore"),
              Map.entry("minecraft:gold_nugget", "minecraft:nether_gold_ore"));
  private static final Map<String, String> LOOT =
      Map.ofEntries(
          Map.entry("minecraft:ender_pearl", "minecraft:enderman"),
              Map.entry("minecraft:blaze_rod", "minecraft:blaze"),
          Map.entry("minecraft:string", "minecraft:spider"),
              Map.entry("minecraft:bone", "minecraft:skeleton"),
          Map.entry("minecraft:gunpowder", "minecraft:creeper"),
              Map.entry("minecraft:leather", "minecraft:cow"),
          Map.entry("minecraft:beef", "minecraft:cow"),
              Map.entry("minecraft:chicken", "minecraft:chicken"),
          Map.entry("minecraft:feather", "minecraft:chicken"),
              Map.entry("minecraft:porkchop", "minecraft:pig"));

  NpcSurvival(CompanionEntity npc, String target, int count, int depth) {
    this.npc = npc;
    this.target = target;
    this.count = count;
    level = (ServerLevel) npc.level();
    if (count < 1 || count > 256 || depth > 4)
      throw new IllegalArgumentException("Acquisition bounds exceeded");
    NpcRecipes.item(target);
    skills = new NpcSkills(npc, (text, id) -> {}, depth);
    deadline = level.getGameTime() + 20L * 60 * 30;
  }

  void tick() {
    if (result.isDone()) return;
    skills.tick();
    long now = level.getGameTime();
    if (now >= deadline || operations > 2048) {
      fail("資源準備の時間・操作上限に達しました");
      return;
    }
    if (local != null) {
      if (activeStep != null
          && activeStep.action().equals("pickup")
          && amount(NpcRecipes.item(activeStep.target())) < activeStep.count()
          && groundAmount(NpcRecipes.item(activeStep.target())) == 0) {
        // Drops can disappear or be taken by another actor. Recompute the real dependency;
        // a short opportunistic pickup must never turn into an endless search for more drops.
        local.close();
        local = null;
        operations++;
        nextTick = now + 4;
        return;
      }
      local.tick(now);
      if (local.needsReplan()) {
        if (activeStep != null
            && (activeStep.action().equals("move_to")
                || activeStep.action().equals("tool")
                    && activeStep.target().equals("avatarcraftnpc:move_precisely"))
            && rejectedApproaches.size() < 12) {
          rejectedApproaches.add(
              BlockPos.containing(
                  activeStep.position().get(0),
                  activeStep.position().get(1),
                  activeStep.position().get(2)));
          local.close();
          local = null;
          operations++;
          nextTick = now + 4;
          return;
        }
        if (activeStep != null
            && activeStep.action().equals("place")
            && rejectedPlacements.size() < 12) {
          rejectedPlacements.add(
              BlockPos.containing(
                  activeStep.position().get(0),
                  activeStep.position().get(1),
                  activeStep.position().get(2)));
          local.close();
          local = null;
          operations++;
          nextTick = now + 4;
          return;
        }
        fail(local.problem());
        return;
      }
      if (!local.complete()) return;
      local.close();
      local = null;
      operations++;
      nextTick = now + 2;
    }
    if (now < nextTick) return;
    nextTick = now + 4;
    try {
      if (amount(NpcRecipes.item(target)) >= count) {
        result.complete(
            object("ok", true, "item", target, "count", amount(NpcRecipes.item(target))));
        return;
      }
      var step = need(NpcRecipes.item(target), count, new LinkedHashSet<>(), 0);
      if (step == null) {
        fail("資源準備の次の操作を解決できません");
        return;
      }
      var plan = new TaskPlan(1, "Prepare " + target, "", List.of(step));
      NpcService.get(level.getServer())
          .record(
              npc,
              "resource_step",
              "Resolve next real resource dependency",
              null,
              object("goal", target, "wanted", count, "step", new Gson().toJsonTree(step)));
      activeStep = step;
      local =
          new LocalTaskRunner(
              plan, new NpcLocalWorld(npc, skills), npc.personality().allowedSkills);
    } catch (RuntimeException e) {
      fail(e.getMessage() == null ? "依存する資源を準備できません" : e.getMessage());
    }
  }

  int amount(Item item) {
    int n = 0;
    for (int i = 0; i < NpcInventory.SIZE; i++)
      if (npc.inventory.getItem(i).is(item)) n += npc.inventory.getItem(i).getCount();
    return n;
  }

  private TaskPlan.Step need(Item item, int wanted, Set<Item> visiting, int depth) {
    if (depth > 24 || !visiting.add(item))
      throw new IllegalArgumentException("循環する材料依存: " + NpcRecipes.id(item));
    String id = NpcRecipes.id(item);
    // Resources in chests are transferred, never counted until actually in inventory.
    for (var e : skills.nearbyChests().getAsJsonArray("chests")) {
      var chest = e.getAsJsonObject();
      for (var entry : chest.getAsJsonArray("items"))
        if (entry.getAsJsonObject().get("item").getAsString().equals(id))
          return at(
              "withdraw",
              id,
              Math.min(64, wanted - amount(item)),
              chest.get("x").getAsDouble(),
              chest.get("y").getAsDouble(),
              chest.get("z").getAsDouble());
    }
    int ground = groundAmount(item);
    if (ground > 0) return step("pickup", id, "", Math.min(wanted, amount(item) + ground));
    if (NpcTeams.waiting(npc, id)) return step("wait", "", "", 1);
    // Table/furnace are placed and used at observed positions; return to them after gathering.
    var smelting =
        level.getServer().getRecipeManager().getRecipes().stream()
            .filter(
                h ->
                    h.value() instanceof SmeltingRecipe r
                        && r.assemble(new SingleRecipeInput(ItemStack.EMPTY)).is(item))
            .filter(
                h ->
                    ((SmeltingRecipe) h.value())
                        .input()
                        .items()
                        .anyMatch(
                            v ->
                                !NpcRecipes.id(v.value()).endsWith("_ore")
                                    || amount(v.value()) > 0))
            .sorted(
                Comparator.comparingInt(
                    h ->
                        ((SmeltingRecipe) h.value())
                            .input()
                            .items()
                            .mapToInt(
                                v ->
                                    amount(v.value()) > 0
                                        ? 0
                                        : NpcRecipes.id(v.value()).contains("raw_") ? 1 : 2)
                            .min()
                            .orElse(9)))
            .findFirst()
            .orElse(null);
    if (smelting != null && !SOURCES.containsKey(id)) {
      var station = station(Blocks.FURNACE);
      if (station == null) {
        if (amount(Items.FURNACE) == 0) return need(Items.FURNACE, 1, visiting, depth + 1);
        return placeStation(Items.FURNACE);
      }
      var recipe = (SmeltingRecipe) smelting.value();
      var input = choose(recipe.input(), visiting);
      int needed = Math.min(64, wanted - amount(item));
      // An interrupted owned batch may already hold input/output.
      var furnace =
          (net.minecraft.world.level.block.entity.FurnaceBlockEntity) level.getBlockEntity(station);
      String key = NpcSmelting.key(level, station);
      boolean ours = npc.getUUID().equals(NpcWorkData.get(level).furnaces.get(key));
      if (!ours && amount(input) < needed) return need(input, needed, visiting, depth + 1);
      boolean fuel = ours || hasFuel();
      if (!fuel) {
        var planks =
            choose(Ingredient.of(BuiltInRegistries.ITEM.getOrThrow(ItemTags.PLANKS)), visiting);
        return need(
            planks,
            Math.max(2, (needed * recipe.cookingTime() + 299) / 300 + 1),
            visiting,
            depth + 1);
      }
      if (npc.distanceToSqr(Vec3.atCenterOf(station)) > 9 || !skills.visible(station))
        return move(station);
      return at("smelt", id, needed, station.getX(), station.getY(), station.getZ());
    }
    var source = SOURCES.get(id);
    if (source != null
        || item instanceof BlockItem && !id.endsWith("_planks") && naturallyGathered(item)) {
      if (source == null) source = id;
      var block =
          BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse(source));
      var state = block.defaultBlockState();
      if (state.requiresCorrectToolForDrops() && !hasTool(state)) {
        var tool =
            state.is(net.minecraft.tags.BlockTags.INCORRECT_FOR_STONE_TOOL)
                ? Items.IRON_PICKAXE
                : state.is(net.minecraft.tags.BlockTags.INCORRECT_FOR_WOODEN_TOOL)
                    ? Items.STONE_PICKAXE
                    : Items.WOODEN_PICKAXE;
        if (state.is(Blocks.OBSIDIAN)) tool = Items.DIAMOND_PICKAXE;
        return need(tool, 1, visiting, depth + 1);
      }
      return step("collect", id, source, wanted);
    }
    var recipes = NpcRecipes.crafting(level, item);
    String last = "入手方法が未解決です: " + id;
    for (var holder : recipes) {
      var recipe = (CraftingRecipe) holder.value();
      try {
        var p = recipe.placementInfo();
        boolean large =
            recipe instanceof ShapedRecipe shaped
                ? (shaped.getWidth() > 2 || shaped.getHeight() > 2)
                : p.ingredients().size() > 4;
        if (large && !skills.hasCraftingTable()) {
          var table = station(Blocks.CRAFTING_TABLE);
          if (table != null) return move(table);
          if (amount(Items.CRAFTING_TABLE) > 0) return placeStation(Items.CRAFTING_TABLE);
          return need(Items.CRAFTING_TABLE, 1, new LinkedHashSet<>(visiting), depth + 1);
        }
        var required = new LinkedHashMap<Item, Integer>();
        for (var ingredient : p.ingredients())
          required.merge(choose(ingredient, visiting), 1, Integer::sum);
        for (var ingredient : required.entrySet())
          if (amount(ingredient.getKey()) < ingredient.getValue())
            return need(
                ingredient.getKey(),
                ingredient.getValue(),
                new LinkedHashSet<>(visiting),
                depth + 1);
        return step("craft", holder.id().identifier().toString(), "", 1);
      } catch (IllegalArgumentException unavailable) {
        last = unavailable.getMessage();
      }
    }
    String entityType = LOOT.get(id);
    if (entityType != null) {
      var target =
          level
              .getEntitiesOfClass(
                  Entity.class,
                  npc.getBoundingBox().inflate(48),
                  e ->
                      e.isAlive()
                          && BuiltInRegistries.ENTITY_TYPE
                              .getKey(e.getType())
                              .toString()
                              .equals(entityType))
              .stream()
              .min(Comparator.comparingDouble(npc::distanceToSqr))
              .orElse(null);
      if (target != null)
        return new TaskPlan.Step("hunt", "", "", 1, target.getId(), -1, "main", 10, List.of());
      throw new IllegalArgumentException("必要な生物が観察範囲にいません: " + entityType + "。上位計画で生息地へ移動してください");
    }
    throw new IllegalArgumentException(last);
  }

  private boolean hasTool(net.minecraft.world.level.block.state.BlockState state) {
    for (int i = 0; i < NpcInventory.SIZE; i++)
      if (npc.inventory.getItem(i).isCorrectToolForDrops(state)) return true;
    return false;
  }

  private int groundAmount(Item item) {
    return level
        .getEntitiesOfClass(
            net.minecraft.world.entity.item.ItemEntity.class,
            npc.getBoundingBox().inflate(16),
            e -> e.isAlive() && !e.hasPickUpDelay() && e.getItem().is(item))
        .stream()
        .mapToInt(e -> e.getItem().getCount())
        .sum();
  }

  private boolean naturallyGathered(Item item) {
    var s = new ItemStack(item);
    return s.is(ItemTags.LOGS)
        || Set.of(
                Items.DIRT,
                Items.SAND,
                Items.GRAVEL,
                Items.OBSIDIAN,
                Items.NETHERRACK,
                Items.END_STONE)
            .contains(item);
  }

  private Item choose(Ingredient ingredient, Set<Item> visited) {
    var items = ingredient.items().map(h -> h.value()).filter(i -> !visited.contains(i)).toList();
    if (items.isEmpty()) throw new IllegalArgumentException("材料候補が循環しています");
    for (var i : items) if (amount(i) > 0) return i;
    // Prefer nearby actual wood over a fixed biome-specific log/plank choice.
    for (var i : items) if (i instanceof BlockItem b && station(b.getBlock()) != null) return i;
    return items.stream()
        .min(
            Comparator.comparingInt(
                i -> {
                  String s = NpcRecipes.id(i);
                  return s.contains("oak")
                      ? 0
                      : s.contains("cobblestone") ? 1 : s.contains("raw_") ? 2 : 3;
                }))
        .orElseThrow();
  }

  private boolean hasFuel() {
    for (int i = 0; i < 36; i++) {
      var s = npc.inventory.getItem(i);
      if (s.is(Items.COAL) || s.is(Items.CHARCOAL) || s.is(ItemTags.PLANKS) && s.getCount() >= 2)
        return true;
    }
    return false;
  }

  private BlockPos station(Block block) {
    for (var p :
        BlockPos.betweenClosed(
            npc.blockPosition().offset(-16, -5, -16), npc.blockPosition().offset(16, 5, 16)))
      if (level.hasChunkAt(p) && level.getBlockState(p).is(block)) {
        if (block == Blocks.FURNACE
            && level.getBlockEntity(p)
                instanceof net.minecraft.world.level.block.entity.FurnaceBlockEntity furnace) {
          var lease = NpcWorkData.get(level).furnaces.get(NpcSmelting.key(level, p));
          if (lease != null && !lease.equals(npc.getUUID())) continue;
          if (lease == null && (!furnace.getItem(0).isEmpty() || !furnace.getItem(2).isEmpty()))
            continue;
        }
        return p.immutable();
      }
    return null;
  }

  private TaskPlan.Step placeStation(Item item) {
    var choices = new ArrayList<BlockPos>();
    for (var p :
        BlockPos.betweenClosed(
            npc.blockPosition().offset(-3, -3, -3), npc.blockPosition().offset(3, 0, 3)))
      choices.add(p.immutable());
    choices.sort(Comparator.comparingDouble(p -> npc.distanceToSqr(Vec3.atCenterOf(p))));
    for (var p : choices) {
      if (!rejectedPlacements.contains(p)
          && level.getBlockState(p).isAir()
          && level.getEntities(npc, new net.minecraft.world.phys.AABB(p)).isEmpty()
          && !npc.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(p))
          && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP))
        return at("place", NpcRecipes.id(item), 1, p.getX(), p.getY(), p.getZ());
    }
    throw new IllegalArgumentException("設備を置ける足場がありません");
  }

  private TaskPlan.Step move(BlockPos p) {
    var ceiling = npc.blockPosition().above(2);
    var state = level.getBlockState(ceiling);
    if (p.getY() >= npc.blockPosition().getY()
        && !state.getCollisionShape(level, ceiling).isEmpty()
        && !state.hasBlockEntity()
        && state.getDestroySpeed(level, ceiling) >= 0
        && skills.mayChange(ceiling, 4)
        && level.getFluidState(ceiling).isEmpty()
        && !(state.getBlock() instanceof FallingBlock)
        && (!state.requiresCorrectToolForDrops() || hasTool(state)))
      return at(
          "mine",
          BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
          1,
          ceiling.getX(),
          ceiling.getY(),
          ceiling.getZ());
    var approach =
        new NpcLocalWorld(npc, skills)
            .approach(
                new com.google.gson.Gson()
                    .toJsonTree(List.of(p.getX(), p.getY(), p.getZ()))
                    .getAsJsonArray(),
                rejectedApproaches);
    if (approach == null) throw new IllegalArgumentException("設備に戻る経路がありません");
    var at =
        new Vec3(
            approach.get("x").getAsDouble(),
            approach.get("y").getAsDouble(),
            approach.get("z").getAsDouble());
    if (npc.distanceToSqr(at) < 64) {
      return new TaskPlan.Step(
          "tool",
          "avatarcraftnpc:move_precisely",
          "",
          1,
          -1,
          -1,
          "main",
          2,
          List.of(at.x, at.y, at.z),
          "",
          object("x", at.x, "y", at.y, "z", at.z).toString());
    }
    return at(
        "move_to",
        "",
        1,
        approach.get("x").getAsDouble(),
        approach.get("y").getAsDouble(),
        approach.get("z").getAsDouble());
  }

  static TaskPlan.Step step(String action, String item, String block, int count) {
    return new TaskPlan.Step(action, item, block, count, -1, -1, "main", 2, List.of());
  }

  static TaskPlan.Step at(String action, String item, int count, double x, double y, double z) {
    return new TaskPlan.Step(action, item, "", count, -1, -1, "main", 2, List.of(x, y, z));
  }

  private void fail(String reason) {
    result.complete(object("ok", false, "error", reason));
    close();
  }

  public void close() {
    if (local != null) local.close();
    skills.cancel("Acquisition stopped");
    if (!result.isDone()) result.complete(object("ok", false, "error", "Acquisition stopped"));
  }
}

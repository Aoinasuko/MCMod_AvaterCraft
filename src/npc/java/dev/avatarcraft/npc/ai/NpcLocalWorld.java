package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.*;
import dev.avatarcraft.npc.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Game-side candidate resolution; all mutation still passes through validated NpcSkills. */
public final class NpcLocalWorld implements LocalTaskRunner.World {
  private final CompanionEntity npc;
  private final NpcSkills skills;
  private String trackedResource = "";

  public NpcLocalWorld(CompanionEntity npc, NpcSkills skills) {
    this.npc = npc;
    this.skills = skills;
  }

  public JsonObject observe() {
    var o = skills.observe();
    o.addProperty("settling", !npc.onGround() && !npc.isInWater());
    var drops = new JsonArray();
    for (var item :
        npc
            .level()
            .getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class,
                npc.getBoundingBox().inflate(24),
                e -> e.isAlive())
            .stream()
            .sorted(
                Comparator.comparingInt(
                        (net.minecraft.world.entity.item.ItemEntity item) ->
                            trackedDrop(item.getItem()) ? 0 : 1)
                    .thenComparingDouble(npc::distanceToSqr))
            .limit(64)
            .toList()) {
      var p = new JsonArray();
      p.add(item.getX());
      p.add(item.getY());
      p.add(item.getZ());
      if (blocked("pickup", object("x", item.getX(), "y", item.getY(), "z", item.getZ()))) continue;
      drops.add(
          object(
              "item",
              BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString(),
              "tags",
              NpcSkills.itemTags(item.getItem()),
              "settling",
              !item.onGround() && (item.tickCount < 30 || item.getDeltaMovement().y < -.02),
              "position",
              p));
    }
    o.add("drops", drops);
    var danger = npc.getLastHurtByMob();
    o.addProperty(
        "combat_health",
        danger == null
            ? 0
            : Math.clamp(danger.getHealth() / Math.max(1, danger.getMaxHealth()), 0, 1));
    o.addProperty(
        "hostile_distance", danger == null ? 1 : Math.clamp(npc.distanceTo(danger) / 16, 0, 1));
    o.addProperty("danger", npc.isOnFire() || npc.isInLava() ? 1 : danger != null ? .5 : 0);
    boolean ammo = false, fuel = false;
    for (int i = 0; i < 36; i++) {
      var stack = npc.inventory.getItem(i);
      ammo |= stack.is(net.minecraft.tags.ItemTags.ARROWS);
      fuel |= stack.has(DataComponents.COOKING_FUEL);
    }
    o.addProperty("ammunition", ammo);
    o.addProperty("fuel_available", fuel);
    o.addProperty("team_waiting", NpcTeams.waiting(npc, "minecraft:iron_pickaxe"));
    var delivery = NpcTeams.job(npc);
    if (delivery != null && levelEntity(delivery.leader) instanceof CompanionEntity leader) {
      o.addProperty("delivery_entity_id", leader.getId());
      var peers = o.getAsJsonArray("nearby");
      peers.add(
          object(
              "entity_id",
              leader.getId(),
              "position",
              new Gson().toJsonTree(List.of(leader.getX(), leader.getY(), leader.getZ())),
              "hostile",
              false));
    }
    return o;
  }

  @Override
  public JsonObject sharedLocation(String name) {
    var entry = NpcAnchors.get(npc.level().getServer()).resolve(npc.level().getServer(), name);
    return entry == null ? null : entry.observation();
  }

  @Override
  public void continuousWork(boolean enabled) {
    skills.continuousWork(enabled);
  }

  private net.minecraft.world.entity.Entity levelEntity(java.util.UUID id) {
    return ((ServerLevel) npc.level()).getEntity(id);
  }

  public List<LocalTaskRunner.Option> tactics(JsonObject observation) {
    var target = npc.getLastHurtByMob();
    if (target == null
        || !target.isAlive()
        || !(target instanceof net.minecraft.world.entity.monster.Enemy)
        || npc.distanceToSqr(target) > 36
        || !npc.hasLineOfSight(target)) return List.of();
    return List.of(
        new LocalTaskRunner.Option(
            "execute",
            "attack",
            object("entity_id", target.getId()),
            npc.distanceTo(target),
            object("combat", 1)));
  }

  public CompletionStage<JsonObject> execute(String tool, JsonObject args) {
    var service = NpcService.get(((ServerLevel) npc.level()).getServer());
    String actionId = UUID.randomUUID().toString();
    var start = args.deepCopy();
    start.addProperty("actionId", actionId);
    start.add("actorPosition", new Gson().toJsonTree(List.of(npc.getX(), npc.getY(), npc.getZ())));
    service.record(npc, "tool_start", tool, null, NpcObservations.bounded(start, 6000));
    return skills
        .execute(tool, args)
        .thenApply(
            result -> {
              var logged = result.deepCopy();
              logged.addProperty("actionId", actionId);
              if (logged.has("error"))
                logged.addProperty(
                    "code", NpcExecutionMemory.failureCode(logged.get("error").getAsString()));
              service.record(npc, "tool_result", tool, null, NpcObservations.bounded(logged, 6000));
              npc.rememberResult(tool, result);
              return result;
            });
  }

  public int bestTool(JsonArray position) {
    var level = (ServerLevel) npc.level();
    var p =
        BlockPos.containing(
            position.get(0).getAsDouble(),
            position.get(1).getAsDouble(),
            position.get(2).getAsDouble());
    if (!level.hasChunkAt(p)) return -2;
    var state = level.getBlockState(p);
    if (state.isAir() || state.getDestroySpeed(level, p) < 0) return -2;
    return bestTool(state);
  }

  private int bestTool(BlockState state) {
    var held = npc.getMainHandItem();
    double best =
        (!state.requiresCorrectToolForDrops() || held.isCorrectToolForDrops(state))
            ? held.getDestroySpeed(state)
            : -1;
    int slot = best < 0 ? -2 : -1;
    for (int i = 0; i < 36; i++) {
      var stack = npc.inventory.getItem(i);
      if (state.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(state)) continue;
      double score = stack.getDestroySpeed(state);
      if (score > best + .001) {
        best = score;
        slot = i;
      }
    }
    return slot;
  }

  private BlockState resourceState(TaskPlan.Step step) {
    String id =
        step.action().equals("mine") || step.action().equals("prepare_tool")
            ? step.target()
            : step.block();
    var key = Identifier.tryParse(id);
    if (id.startsWith("#")) {
      key = Identifier.tryParse(id.substring(1));
      if (key == null) return null;
      var tag =
          net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK, key);
      var found = BuiltInRegistries.BLOCK.get(tag);
      if (found.isPresent())
        return found.get().stream()
            .findFirst()
            .map(h -> h.value().defaultBlockState())
            .orElse(null);
    }
    return key != null && BuiltInRegistries.BLOCK.containsKey(key)
        ? BuiltInRegistries.BLOCK.getValue(key).defaultBlockState()
        : null;
  }

  public int miningTool(TaskPlan.Step step) {
    if (step.action().equals("collect")) trackedResource = step.target();
    var state = resourceState(step);
    return state == null ? -2 : bestTool(state);
  }

  private boolean trackedDrop(ItemStack stack) {
    if (trackedResource.startsWith("#"))
      return stack.is(
          net.minecraft.tags.TagKey.create(
              net.minecraft.core.registries.Registries.ITEM,
              Identifier.parse(trackedResource.substring(1))));
    return !trackedResource.isEmpty()
        && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(trackedResource);
  }

  public boolean mineAccessible(JsonArray target) {
    var p =
        BlockPos.containing(
            target.get(0).getAsDouble(), target.get(1).getAsDouble(), target.get(2).getAsDouble());
    // Automatic bulk gathering must not hollow out its floor; descend by stairs instead.
    if (p.getY() < npc.blockPosition().getY()) return false;
    return skills.visible(p) && npc.distanceToSqr(Vec3.atCenterOf(p)) <= 16
        || approach(target) != null;
  }

  @Override
  public boolean harvestable(TaskPlan.Step step, JsonArray target) {
    return harvestReady(step, target) && mineAccessible(target);
  }

  private boolean harvestReady(TaskPlan.Step step, JsonArray target) {
    if (step.action().equals("collect")) {
      var p =
          BlockPos.containing(
              target.get(0).getAsDouble(),
              target.get(1).getAsDouble(),
              target.get(2).getAsDouble());
      var state = npc.level().getBlockState(p);
      if (state.getBlock() instanceof CropBlock crop && !crop.isMaxAge(state)) return false;
      if (state.is(Blocks.NETHER_WART) && state.getValue(NetherWartBlock.AGE) < 3) return false;
      if ((state.is(Blocks.SUGAR_CANE) || state.is(Blocks.BAMBOO))
          && !npc.level().getBlockState(p.below()).is(state.getBlock())) return false;
    }
    return true;
  }

  public JsonObject planningObservation() {
    var o = observe();
    var nearby = new JsonArray();
    var buildingMaterials = new java.util.TreeMap<String, Integer>();
    for (int slot = 0; slot < 42; slot++) {
      var stack = npc.inventory.getItem(slot);
      if (stack.getItem() instanceof net.minecraft.world.item.BlockItem && !stack.isEmpty())
        buildingMaterials.merge(NpcRecipes.id(stack.getItem()), stack.getCount(), Integer::sum);
    }
    var allChests = skills.nearbyChests().getAsJsonArray("chests");
    for (var entry : allChests)
      for (var element : entry.getAsJsonObject().getAsJsonArray("items")) {
        var stack = element.getAsJsonObject();
        String id = stack.get("item").getAsString();
        if (NpcRecipes.item(id) instanceof net.minecraft.world.item.BlockItem)
          buildingMaterials.merge(id, stack.get("count").getAsInt(), Integer::sum);
      }
    for (var entry : allChests) {
      if (nearby.size() >= 6) break;
      var chest = entry.getAsJsonObject().deepCopy();
      var items = chest.getAsJsonArray("items");
      while (items.size() > 8) items.remove(items.size() - 1);
      nearby.add(chest);
    }
    o.add("nearby_chests", nearby);
    o.add("locations", npc.locations.snapshot());
    var teammates = new JsonArray();
    for (var peer :
        npc.level()
            .getEntitiesOfClass(
                CompanionEntity.class,
                npc.getBoundingBox().inflate(48),
                n -> n != npc && n.isAlive() && Objects.equals(n.ownerId(), npc.ownerId())))
      teammates.add(
          object(
              "entity_id",
              peer.getId(),
              "uuid",
              peer.getUUID().toString(),
              "name",
              peer.getName().getString(),
              "task",
              peer.instruction(),
              "available",
              NpcTeams.job(peer) == null
                  && (peer.instruction().isBlank() || peer.instruction().equals(npc.instruction())),
              "inventory",
              new NpcSkills(peer, (message, id) -> {}).observe().get("inventory")));
    o.add("teammates", teammates);
    o.add("team_jobs", NpcTeams.status(npc, -1));
    var findings = new JsonArray();
    for (var entry : NpcWorkData.get((ServerLevel) npc.level()).findings.values())
      if (findings.size() < 16
          && entry.has("owner")
          && entry.get("owner").getAsString().equals(npc.ownerId().toString()))
        findings.add(entry.deepCopy());
    o.add("team_findings", findings);
    o.add("recent_results", npc.recentResults());
    o.addProperty(
        "local_exploration",
        "Loaded paths, bounded stairs, ore variants, real recipes, recursive acquire from no tools,"
            + " vanilla furnace smelting, same-owner delegation with actual delivery.");
    // Reserve a separate budget: generic array trimming must not erase all search findings.
    var bounded = NpcObservations.planning(o, 10000);
    bounded.add(
        "execution_memory",
        NpcObservations.bounded(
            npc.executionMemory.observation(
                npc.level().dimension().identifier().toString(), npc.level().getGameTime()),
            10000));
    if (NpcTools.permitted("read_signs", npc.personality().allowedSkills))
      bounded.add("nearby_signs", NpcObservations.bounded(skills.readSigns(12), 4000));
    var peerFindings = new JsonArray();
    for (var peer :
        npc.level()
            .getEntitiesOfClass(
                CompanionEntity.class,
                npc.getBoundingBox().inflate(48),
                n -> n != npc && Objects.equals(n.ownerId(), npc.ownerId()))) {
      if (peerFindings.size() == 3) break;
      var memory =
          peer.executionMemory.observation(
              npc.level().dimension().identifier().toString(), npc.level().getGameTime());
      peerFindings.add(
          NpcObservations.bounded(
              object("actor", peer.getUUID().toString(), "searches", memory.get("searches")),
              3000));
    }
    bounded.add("peer_findings", peerFindings);
    bounded.add("building_materials", new Gson().toJsonTree(buildingMaterials));
    var blueprint = NpcBlueprints.get(npc.level().getServer()).plans.get(npc.getUUID().toString());
    if (blueprint != null)
      bounded.add(
          "blueprint",
          object(
              "id",
              blueprint.id,
              "status",
              blueprint.status,
              "origin",
              new Gson().toJsonTree(List.of(blueprint.x, blueprint.y, blueprint.z)),
              "name",
              blueprint.spec().name(),
              "missing",
              new Gson().toJsonTree(NpcConstruction.missing(npc, blueprint))));
    bounded.add(
        "shared_anchors",
        NpcAnchors.get(npc.level().getServer())
            .relevant(npc.level().getServer(), npc.instruction()));
    return bounded;
  }

  @Override
  public boolean blocked(String tool, JsonObject args) {
    return skills.blocked(tool, args);
  }

  public TaskPlan bindLocations(TaskPlan plan) {
    var o = planningObservation();
    String dim = o.get("dimension").getAsString();
    var home =
        npc.locations.entries().stream()
            .filter(p -> p.dimension().equals(dim) && p.kind().equals("chest"))
            .min(
                Comparator.comparingDouble(
                    p -> npc.distanceToSqr(Vec3.atCenterOf(new BlockPos(p.x(), p.y(), p.z())))))
            .orElse(null);
    if (home == null) return plan;
    var steps = new ArrayList<TaskPlan.Step>();
    for (var s : plan.steps())
      steps.add(
          s.action().equals("deposit") && s.location().isEmpty() && s.position().isEmpty()
              ? new TaskPlan.Step(
                  s.action(),
                  s.target(),
                  s.block(),
                  s.count(),
                  s.entityId(),
                  s.slot(),
                  s.hand(),
                  s.seconds(),
                  s.position(),
                  home.name())
              : s);
    return new TaskPlan(plan.version(), plan.summary(), plan.blocked(), steps);
  }

  public List<LocalTaskRunner.Option> preparation(TaskPlan.Step step, JsonArray chests) {
    var state = resourceState(step);
    if (state == null) return List.of();
    var out = new ArrayList<LocalTaskRunner.Option>();
    for (var e : chests) {
      var chest = e.getAsJsonObject();
      var pos = new JsonArray();
      pos.add(chest.get("x"));
      pos.add(chest.get("y"));
      pos.add(chest.get("z"));
      for (var entry : chest.getAsJsonArray("items")) {
        var item = entry.getAsJsonObject();
        var id = Identifier.tryParse(item.get("item").getAsString());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) continue;
        var stack = new ItemStack(BuiltInRegistries.ITEM.getValue(id));
        if (!stack.isCorrectToolForDrops(state) || stack.getDestroySpeed(state) <= 1) continue;
        double distance =
            Math.sqrt(
                npc.distanceToSqr(
                    Vec3.atCenterOf(
                        BlockPos.containing(
                            pos.get(0).getAsDouble(),
                            pos.get(1).getAsDouble(),
                            pos.get(2).getAsDouble()))));
        var args =
            distance > 3
                ? approach(pos)
                : object(
                    "x",
                    chest.get("x"),
                    "y",
                    chest.get("y"),
                    "z",
                    chest.get("z"),
                    "slot",
                    item.get("slot"),
                    "count",
                    1);
        if (args != null)
          out.add(
              new LocalTaskRunner.Option(
                  distance > 3 ? "approach" : "prepare",
                  distance > 3 ? "move_to" : "withdraw",
                  args,
                  distance,
                  object("chest_source", 1)));
      }
    }
    out.removeIf(option -> blocked(option.tool(), option.args()));
    if (!out.isEmpty()) return out; // A usable observed spare takes precedence over new crafting.
    for (String material : List.of("stone", "iron", "diamond", "wooden", "golden")) {
      var id = Identifier.fromNamespaceAndPath("minecraft", material + "_pickaxe");
      var stack = new ItemStack(BuiltInRegistries.ITEM.getValue(id));
      String recipe = id.toString();
      if (stack.isCorrectToolForDrops(state) && skills.canCraft(recipe))
        out.add(
            new LocalTaskRunner.Option(
                "prepare", "craft", object("recipe", recipe), 0, object("ingredients_ready", 1)));
    }
    if (!out.isEmpty()) return out;
    boolean table = skills.hasCraftingTable();
    int sticks = 0, planks = 0;
    for (int i = 0; i < 36; i++) {
      var stack = npc.inventory.getItem(i);
      if (stack.is(Items.STICK)) sticks += stack.getCount();
      if (stack.is(net.minecraft.tags.ItemTags.PLANKS)) planks += stack.getCount();
      if (!table && stack.is(Items.CRAFTING_TABLE))
        for (var direction : Direction.Plane.HORIZONTAL) {
          var p = npc.blockPosition().relative(direction);
          if (safeStand(p) && npc.level().getBlockState(p).isAir())
            out.add(
                new LocalTaskRunner.Option(
                    "prepare",
                    "place",
                    object("x", p.getX(), "y", p.getY(), "z", p.getZ(), "slot", i),
                    1,
                    object("ingredients_ready", 1)));
        }
    }
    if (!out.isEmpty()) return out;
    if (sticks < 2 && skills.canCraft("minecraft:stick"))
      return List.of(craftOption("minecraft:stick"));
    if (!table && skills.canCraft("minecraft:crafting_table"))
      return List.of(craftOption("minecraft:crafting_table"));
    if (planks < (table ? 2 : 6))
      for (String wood :
          List.of(
              "oak",
              "spruce",
              "birch",
              "jungle",
              "acacia",
              "dark_oak",
              "mangrove",
              "cherry",
              "pale_oak",
              "crimson",
              "warped",
              "bamboo"))
        if (skills.canCraft("minecraft:" + wood + "_planks"))
          return List.of(craftOption("minecraft:" + wood + "_planks"));
    if (NpcTools.permitted("acquire", npc.personality().allowedSkills)) {
      Item needed =
          state.is(net.minecraft.tags.BlockTags.INCORRECT_FOR_STONE_TOOL)
              ? Items.IRON_PICKAXE
              : state.is(net.minecraft.tags.BlockTags.INCORRECT_FOR_WOODEN_TOOL)
                  ? Items.STONE_PICKAXE
                  : Items.WOODEN_PICKAXE;
      if (state.is(Blocks.OBSIDIAN)) needed = Items.DIAMOND_PICKAXE;
      return List.of(
          new LocalTaskRunner.Option(
              "prepare",
              "acquire",
              object("item", NpcRecipes.id(needed), "count", 1),
              0,
              object("ingredients_ready", 1)));
    }
    return List.of();
  }

  private static LocalTaskRunner.Option craftOption(String recipe) {
    return new LocalTaskRunner.Option(
        "prepare", "craft", object("recipe", recipe), 0, object("ingredients_ready", 1));
  }

  private boolean safeStand(BlockPos p) {
    return NpcWalkSurface.standable(npc, p);
  }

  private boolean dangerous(BlockPos p) {
    var level = (ServerLevel) npc.level();
    for (var q : List.of(p, p.above(), p.below(), p.north(), p.south(), p.east(), p.west()))
      if (!level.hasChunkAt(q)
          || !level.getFluidState(q).isEmpty()
          || level.getBlockState(q).getBlock() instanceof FallingBlock) return true;
    return level.getBlockState(p.above(2)).getBlock() instanceof FallingBlock;
  }

  private boolean inExplorationArea(BlockPos p) {
    var level = (ServerLevel) npc.level();
    var owner = level.getServer().getPlayerList().getPlayer(npc.ownerId());
    return owner != null
        && level.hasChunkAt(p)
        && level.isInWorldBounds(p)
        && level.getWorldBorder().isWithinBounds(p)
        && Math.hypot(p.getX() - npc.getX(), p.getZ() - npc.getZ()) <= 48
        && p.getY() >= level.getMinY() + 6;
  }

  public List<LocalTaskRunner.Option> exploration(
      TaskPlan.Step s, Set<String> visited, int actions, JsonArray targets) {
    if (actions >= LocalTaskRunner.EXPLORATION_BUDGET || npc.getHealth() < 6) return List.of();
    var level = (ServerLevel) npc.level();
    var origin = npc.blockPosition();
    var ceiling = origin.above(2);
    var ceilingState = level.getBlockState(ceiling);
    if (!ceilingState.getCollisionShape(level, ceiling).isEmpty()
        && !ceilingState.hasBlockEntity()
        && !NpcWorkRoute.protectedBlock(npc, ceiling)
        && ceilingState.getDestroySpeed(level, ceiling) >= 0
        && !protectedSupport(ceiling, visited)
        && skills.mayChange(ceiling, 4)
        && skills.visible(ceiling)
        && !dangerous(ceiling)
        && (!ceilingState.requiresCorrectToolForDrops()
            || npc.getMainHandItem().isCorrectToolForDrops(ceilingState)))
      return List.of(
          new LocalTaskRunner.Option(
              "excavate",
              "dig",
              object("x", ceiling.getX(), "y", ceiling.getY(), "z", ceiling.getZ()),
              1,
              object("unvisited", 1)));
    int preferred = NpcResources.preferredY(s, level.getMinY(), origin.getY());
    BlockPos focus = null;
    if (!targets.isEmpty()) {
      var a = targets.get(0).getAsJsonArray();
      focus =
          BlockPos.containing(
              a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
      preferred = focus.getY();
    }
    if (focus != null && focus.equals(origin.below())) {
      // Automatic drop pickup can leave us standing on the last resource block. Walk off it
      // onto an observed adjacent floor before mining; never dig our current support.
      for (var direction : Direction.Plane.HORIZONTAL) {
        var stand = origin.relative(direction).below();
        if (!safeStand(stand) || dangerous(stand)) continue;
        var path = npc.getNavigation().createPath(stand, 0);
        if (path != null && path.canReach())
          return List.of(
              new LocalTaskRunner.Option(
                  "approach",
                  "move_precisely",
                  object("x", stand.getX(), "y", stand.getY(), "z", stand.getZ()),
                  1,
                  object("unvisited", 1)));
      }
    }
    if (!targets.isEmpty() && NpcTools.permitted("work_route", npc.personality().allowedSkills)) {
      for (var value : targets) {
        var a = value.getAsJsonArray();
        if (!harvestReady(s, a)) continue;
        var p =
            BlockPos.containing(
                a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
        if (!s.position().isEmpty()
            && p.distSqr(
                    BlockPos.containing(
                        s.position().get(0), s.position().get(1), s.position().get(2)))
                > 1) continue;
        if (p.getY() < origin.getY() || p.distSqr(origin) > 24 * 24) continue;
        return List.of(
            new LocalTaskRunner.Option(
                "prepare",
                "work_route",
                object(
                    "x",
                    p.getX(),
                    "y",
                    p.getY(),
                    "z",
                    p.getZ(),
                    "goal",
                    "mine",
                    "mode",
                    "both",
                    "max_blocks",
                    96),
                0,
                object("ingredients_ready", 1)));
      }
    }
    var out = new ArrayList<LocalTaskRunner.Option>();
    var directions = List.of(Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH);
    int rotation = (actions / 24) % 4;
    if (focus != null) {
      int dx = focus.getX() - origin.getX(), dz = focus.getZ() - origin.getZ();
      Direction desired =
          Math.abs(dx) >= Math.abs(dz)
              ? (dx >= 0 ? Direction.EAST : Direction.WEST)
              : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
      rotation = directions.indexOf(desired);
    }
    if (NpcResources.underground(s))
      for (int i = 0; i < 4; i++) {
        var p =
            origin
                .relative(directions.get((rotation + i) % 4))
                .below(origin.getY() > preferred ? 1 : 0);
        if (!inExplorationArea(p)
            || visited.contains(key(p))
            || dangerous(p)
            || !level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP))
          continue;
        BlockPos obstruction = null;
        if (!level.getBlockState(p.above(2)).getCollisionShape(level, p.above(2)).isEmpty())
          obstruction = p.above(2);
        else if (!level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty())
          obstruction = p.above();
        else if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) obstruction = p;
        var context =
            object("unvisited", 1, "depth_gain", Math.clamp(origin.getY() - p.getY(), -1, 1));
        if (obstruction != null) {
          var state = level.getBlockState(obstruction);
          if (protectedSupport(obstruction, visited)) continue;
          if (state.hasBlockEntity()
              || state.getDestroySpeed(level, obstruction) < 0
              || !skills.mayChange(obstruction, 4)
              || state.requiresCorrectToolForDrops()
                  && !npc.getMainHandItem().isCorrectToolForDrops(state)) continue;
          out.add(
              new LocalTaskRunner.Option(
                  "excavate",
                  "dig",
                  object("x", obstruction.getX(), "y", obstruction.getY(), "z", obstruction.getZ()),
                  1,
                  context));
        } else {
          if (!level.noCollision(
              npc,
              new net.minecraft.world.phys.AABB(
                  p.getX() + .2,
                  p.getY(),
                  p.getZ() + .2,
                  p.getX() + .8,
                  p.getY() + 1.8,
                  p.getZ() + .8))) continue;
          var path = npc.getNavigation().createPath(p, 0);
          if (path != null && path.canReach())
            out.add(
                new LocalTaskRunner.Option(
                    "explore",
                    "move_to",
                    object("x", p.getX(), "y", p.getY(), "z", p.getZ()),
                    1,
                    context));
        }
        if (!out.isEmpty()) break;
      }
    // Larger, already walkable paths are useful for surface searches and existing cave passages.
    for (int i = 0; i < 8; i++)
      for (int dy : new int[] {-2, -1, 0, 1, 2}) {
        double angle = (i + rotation * 2) * Math.PI / 4;
        var p =
            origin.offset(
                (int) Math.round(Math.cos(angle) * 8), dy, (int) Math.round(Math.sin(angle) * 8));
        if (!inExplorationArea(p) || visited.contains(key(p)) || !safeStand(p)) continue;
        var path = npc.getNavigation().createPath(p, 0);
        if (path != null && path.canReach())
          out.add(
              new LocalTaskRunner.Option(
                  "explore",
                  "move_to",
                  object("x", p.getX(), "y", p.getY(), "z", p.getZ()),
                  8,
                  object(
                      "unvisited",
                      1,
                      "depth_gain",
                      NpcResources.underground(s)
                          ? Math.clamp(origin.getY() - p.getY(), -1, 1)
                          : 0)));
        break;
      }
    // Reach an unvisited frontier through an already excavated path instead of refusing to
    // backtrack.
    if (out.isEmpty()) {
      BlockPos route = null;
      double best = Double.POSITIVE_INFINITY;
      for (String entry : visited) {
        var xyz = entry.split(",");
        if (xyz.length != 3) continue;
        var stand =
            new BlockPos(
                Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
        if (stand.distSqr(origin) < 2 || stand.distSqr(origin) > 16 * 16 || !safeStand(stand))
          continue;
        for (var direction : Direction.Plane.HORIZONTAL) {
          var next = stand.relative(direction).below(stand.getY() > preferred ? 1 : 0);
          if (visited.contains(key(next))
              || !inExplorationArea(next)
              || dangerous(next)
              || !level.getBlockState(next.below()).isFaceSturdy(level, next.below(), Direction.UP))
            continue;
          var obstruction =
              !level.getBlockState(next.above(2)).getCollisionShape(level, next.above(2)).isEmpty()
                  ? next.above(2)
                  : !level
                          .getBlockState(next.above())
                          .getCollisionShape(level, next.above())
                          .isEmpty()
                      ? next.above()
                      : next;
          var state = level.getBlockState(obstruction);
          if (state.hasBlockEntity()
              || state.getDestroySpeed(level, obstruction) < 0
              || protectedSupport(obstruction, visited)
              || state.requiresCorrectToolForDrops()
                  && !npc.getMainHandItem().isCorrectToolForDrops(state)) continue;
          var ray =
              level.clip(
                  new net.minecraft.world.level.ClipContext(
                      Vec3.atBottomCenterOf(stand).add(0, npc.getEyeHeight(), 0),
                      Vec3.atCenterOf(obstruction),
                      net.minecraft.world.level.ClipContext.Block.COLLIDER,
                      net.minecraft.world.level.ClipContext.Fluid.NONE,
                      npc));
          if (!state.isAir()
              && ray.getType() != net.minecraft.world.phys.HitResult.Type.MISS
              && !ray.getBlockPos().equals(obstruction)) continue;
          var path = npc.getNavigation().createPath(stand, 0);
          if (path == null || !path.canReach()) continue;
          double score =
              (focus == null ? next.distSqr(origin) : next.distSqr(focus))
                  + .1 * stand.distSqr(origin);
          if (score < best) {
            best = score;
            route = stand;
          }
        }
      }
      if (route != null)
        out.add(
            new LocalTaskRunner.Option(
                "explore",
                "move_to",
                object("x", route.getX(), "y", route.getY(), "z", route.getZ()),
                Math.sqrt(route.distSqr(origin)),
                object("unvisited", 0)));
    }
    if (out.isEmpty()) {
      var reasons = new JsonArray();
      for (var direction : Direction.Plane.HORIZONTAL) {
        var p = origin.relative(direction).below(origin.getY() > preferred ? 1 : 0);
        var obstruction =
            !level.getBlockState(p.above(2)).getCollisionShape(level, p.above(2)).isEmpty()
                ? p.above(2)
                : !level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                    ? p.above()
                    : p;
        reasons.add(
            object(
                "x",
                p.getX(),
                "y",
                p.getY(),
                "z",
                p.getZ(),
                "in_area",
                inExplorationArea(p),
                "visited",
                visited.contains(key(p)),
                "danger",
                dangerous(p),
                "support",
                level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP),
                "obstruction",
                level.getBlockState(obstruction).toString(),
                "protected_support",
                protectedSupport(obstruction, visited)));
      }
      NpcService.get(level.getServer())
          .record(
              npc,
              "exploration_blocked",
              "No legal exploration candidate",
              null,
              object(
                  "position",
                  new Gson().toJsonTree(List.of(npc.getX(), npc.getY(), npc.getZ())),
                  "preferredY",
                  preferred,
                  "actions",
                  actions,
                  "directions",
                  reasons));
    }
    return out;
  }

  public static String key(BlockPos p) {
    return p.getX() + "," + p.getY() + "," + p.getZ();
  }

  private boolean protectedSupport(BlockPos block, Set<String> visited) {
    if (NpcWorkRoute.protectedBlock(npc, block)) return true;
    String standing = key(block.above());
    if (!visited.contains(standing)) return false;
    if (!visited.isEmpty() && visited.iterator().next().equals(standing)) return true;
    // Keep narrow retreat stairs. A broad old surface can route around a one-block hole.
    int alternatives = 0;
    for (var direction : Direction.Plane.HORIZONTAL)
      if (safeStand(block.above().relative(direction))) alternatives++;
    return alternatives < 2;
  }

  public int foodSlot() {
    int best = -1, nutrition = 0;
    for (int i = 0; i < 36; i++) {
      var food = npc.inventory.getItem(i).get(DataComponents.FOOD);
      if (food != null && food.nutrition() > nutrition) {
        best = i;
        nutrition = food.nutrition();
      }
    }
    return best;
  }

  @Override
  public boolean storageReachable(JsonObject chest) {
    var p =
        BlockPos.containing(
            chest.get("x").getAsDouble(),
            chest.get("y").getAsDouble(),
            chest.get("z").getAsDouble());
    return skills.container(p, 4) != null;
  }

  @Override
  public int storageCapacity(JsonObject chest, int slot) {
    var p =
        BlockPos.containing(
            chest.get("x").getAsDouble(),
            chest.get("y").getAsDouble(),
            chest.get("z").getAsDouble());
    if (npc.distanceToSqr(Vec3.atCenterOf(p)) > 16) return -1;
    if (!skills.visible(p)) return -1;
    return skills.depositCapacity(p, slot, 4);
  }

  public JsonObject approach(JsonArray target) {
    var block =
        BlockPos.containing(
            target.get(0).getAsDouble(), target.get(1).getAsDouble(), target.get(2).getAsDouble());
    if (npc.level().hasChunkAt(block)
        && npc.level().getBlockState(block).getBlock() instanceof ChestBlock)
      return object("x", block.getX(), "y", block.getY(), "z", block.getZ());
    return approach(target, Set.of());
  }

  public JsonObject pickupApproach(JsonArray target) {
    var level = (ServerLevel) npc.level();
    var item =
        new Vec3(
            target.get(0).getAsDouble(), target.get(1).getAsDouble(), target.get(2).getAsDouble());
    var block = BlockPos.containing(item);
    var candidates = new ArrayList<BlockPos>();
    for (var p : BlockPos.betweenClosed(block.offset(-1, -1, -1), block.offset(1, 1, 1))) {
      var center = NpcWalkSurface.point(npc, p);
      if (Math.hypot(center.x - item.x, center.z - item.z) > 1.1
          || center.y - item.y > .65
          || item.y - center.y > 2.2
          || !safeStand(p)
          || !level.noCollision(
              npc,
              new net.minecraft.world.phys.AABB(
                  p.getX() + .2,
                  p.getY(),
                  p.getZ() + .2,
                  p.getX() + .8,
                  p.getY() + 1.8,
                  p.getZ() + .8))) continue;
      candidates.add(p.immutable());
    }
    candidates.sort(
        Comparator.comparingDouble(p -> npc.distanceToSqr(NpcWalkSurface.point(npc, p))));
    for (var p : candidates) {
      if (blocked("move_precisely", object("x", p.getX(), "y", p.getY(), "z", p.getZ()))) continue;
      var path = npc.getNavigation().createPath(p, 0);
      if (path != null && path.canReach())
        return object("x", p.getX(), "y", p.getY(), "z", p.getZ());
    }
    return null;
  }

  @Override
  public LocalTaskRunner.Option pickupWork(JsonArray target) {
    if (!NpcTools.permitted("work_route", npc.personality().allowedSkills)) return null;
    if (skills.gatheringScaffolding()) return null;
    var p =
        BlockPos.containing(
            target.get(0).getAsDouble(), target.get(1).getAsDouble(), target.get(2).getAsDouble());
    if (p.distSqr(npc.blockPosition()) > 24 * 24) return null;
    return new LocalTaskRunner.Option(
        "prepare",
        "work_route",
        object(
            "x",
            target.get(0).getAsDouble(),
            "y",
            target.get(1).getAsDouble(),
            "z",
            target.get(2).getAsDouble(),
            "goal",
            "pickup",
            "mode",
            "both",
            "max_blocks",
            64),
        0,
        object("ingredients_ready", 1));
  }

  JsonObject approach(JsonArray target, Set<BlockPos> excluded) {
    var level = (ServerLevel) npc.level();
    var block =
        BlockPos.containing(
            target.get(0).getAsDouble(), target.get(1).getAsDouble(), target.get(2).getAsDouble());
    var candidates = new ArrayList<BlockPos>();
    for (int dx = -2; dx <= 2; dx++)
      for (int dz = -2; dz <= 2; dz++)
        for (int dy = -1; dy <= 1; dy++) {
          if (dx == 0 && dz == 0) continue;
          var p = block.offset(dx, dy, dz);
          if (!excluded.contains(p)
              && level.hasChunkAt(p)
              && level.getWorldBorder().isWithinBounds(p)
              && level.getBlockState(p).getCollisionShape(level, p).isEmpty()
              && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
              && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)
              && level.noCollision(
                  npc,
                  new net.minecraft.world.phys.AABB(
                      p.getX() + .2,
                      p.getY(),
                      p.getZ() + .2,
                      p.getX() + .8,
                      p.getY() + 1.8,
                      p.getZ() + .8))
              && Vec3.atBottomCenterOf(p).distanceToSqr(Vec3.atCenterOf(block)) <= 4)
            candidates.add(p);
        }
    candidates.sort(Comparator.comparingDouble(p -> npc.distanceToSqr(Vec3.atBottomCenterOf(p))));
    for (var p : candidates) {
      if (blocked("move_to", object("x", p.getX(), "y", p.getY(), "z", p.getZ()))) continue;
      if (!skills.visibleFrom(Vec3.atBottomCenterOf(p).add(0, npc.getEyeHeight(), 0), block))
        continue;
      var path = npc.getNavigation().createPath(p, 0);
      if (path != null && path.canReach())
        return object("x", p.getX(), "y", p.getY(), "z", p.getZ());
    }
    // The short native probe may fail while the actor is settling or outside its search radius.
    // Defer the actual reachability decision to move_precisely's bounded walking search.
    for (var p : candidates)
      if (!blocked("move_to", object("x", p.getX(), "y", p.getY(), "z", p.getZ()))
          && skills.visibleFrom(Vec3.atBottomCenterOf(p).add(0, npc.getEyeHeight(), 0), block))
        return object("x", p.getX(), "y", p.getY(), "z", p.getZ());
    return null;
  }

  public void record(JsonObject selection) {
    NpcService.get(((ServerLevel) npc.level()).getServer())
        .record(npc, "local_policy", "Action selection", null, selection);
  }
}

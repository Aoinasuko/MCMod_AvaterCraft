package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.*;
import dev.avatarcraft.npc.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;

/** Bounded voxel work paths. Every edit is an existing survival tool, never a setBlock/teleport. */
final class NpcWorkRoute implements AutoCloseable {
  private static final Set<Item> MATERIALS =
      Set.of(
          Items.DIRT,
          Items.COBBLESTONE,
          Items.COBBLED_DEEPSLATE,
          Items.STONE,
          Items.NETHERRACK,
          Items.END_STONE);
  private final CompanionEntity npc;
  private final ServerLevel level;
  private final NpcSkills skills;
  private final NpcLocalWorld world;
  private final BlockPos start, target;
  private final Vec3 point;
  private final String mode, goal;
  private final int limit;
  private static final Map<CompanionEntity, Footings> FOOTINGS = new WeakHashMap<>();

  private record Footings(String dimension, Set<BlockPos> blocks, Set<BlockPos> feet) {}

  private final Set<BlockPos> built;
  private final Set<BlockPos> walked;

  private static Set<BlockPos> footings(CompanionEntity npc) {
    String dimension = npc.level().dimension().identifier().toString();
    var memory = FOOTINGS.get(npc);
    if (memory == null || !memory.dimension.equals(dimension)) {
      memory = new Footings(dimension, new LinkedHashSet<>(), new LinkedHashSet<>());
      FOOTINGS.put(npc, memory);
    }
    return memory.blocks;
  }

  static boolean protectedBlock(CompanionEntity npc, BlockPos p) {
    return footings(npc).contains(p) || FOOTINGS.get(npc).feet.contains(p.above());
  }

  private final Set<String> rejected = new HashSet<>();
  private final Set<String> rejectedEdges = new HashSet<>();
  private BlockPos nextStep, activeFrom, activeStep;
  private String lastProblem = "";
  private NpcWalkingRoute walking;
  private List<BlockPos> walkCache = List.of();
  private boolean searchPending, walkIncomplete;
  private CompletableFuture<JsonObject> pending;
  private Operation active;
  private long nextTick, deadline;
  private int edits, placed, dug, operations, supplyAttempts, toolAttempts, failures;
  private int editSearchNodes;
  private boolean editSearchLimited;
  private boolean closed;
  private final NpcMoveProgress movement;
  final CompletableFuture<JsonObject> result = new CompletableFuture<>();

  private record Operation(String tool, BlockPos pos, int slot) {}

  private record Node(BlockPos pos, Node previous, double cost, double priority, int changes) {}

  NpcWorkRoute(CompanionEntity npc, Vec3 point, String goal, String mode, int limit, int depth) {
    this(npc, point, goal, mode, limit, depth, Set.of());
  }

  NpcWorkRoute(
      CompanionEntity npc,
      Vec3 point,
      String goal,
      String mode,
      int limit,
      int depth,
      Set<String> resourceChain) {
    this.npc = npc;
    this.level = (ServerLevel) npc.level();
    this.start = npc.blockPosition();
    this.point = point;
    this.target = BlockPos.containing(point);
    this.mode = mode;
    this.goal = goal;
    this.limit = limit;
    this.built = footings(npc);
    this.walked = FOOTINGS.get(npc).feet;
    if (depth > 4) throw new IllegalArgumentException("DEPTH_LIMIT: work route nesting exceeds 4");
    if (!Set.of("reach", "mine", "bridge", "pickup").contains(goal)
        || !Set.of("dig", "build", "both").contains(mode)
        || limit < 1
        || limit > 128
        || start.distSqr(target) > 128 * 128)
      throw new IllegalArgumentException("Work route target, mode or budget is outside bounds");
    if (!inside(target))
      throw new IllegalArgumentException(
          "VERTICAL_LIMIT: use recorded return path or intermediate waypoints (+20/-24)");
    skills = new NpcSkills(npc, (s, id) -> {}, depth, resourceChain);
    skills.continuousWork(true);
    world = new NpcLocalWorld(npc, skills);
    deadline = level.getGameTime() + 20L * 60 * 10;
    movement = new NpcMoveProgress(level.getGameTime(), NpcMoveProgress.WORK_STALL_TICKS);
  }

  void tick() {
    try {
      advance();
    } catch (RuntimeException e) {
      System.getLogger("bep_3davater_loader NPC").log(System.Logger.Level.WARNING, "Work route stopped", e);
      fail("Work route state changed or a game action was rejected");
    }
  }

  private void advance() {
    if (closed || result.isDone()) return;
    skills.tick();
    if (npc.onGround()) {
      if (walked.size() >= 512) walked.remove(walked.iterator().next());
      walked.add(npc.blockPosition());
    }
    if (!npc.isAlive()
        || npc.ownerId() == null
        || level.getServer().getPlayerList().getPlayer(npc.ownerId()) == null) {
      fail("Work route owner is offline or NPC unavailable");
      return;
    }
    long now = level.getGameTime();
    boolean stalled =
        movement.stalled(
            now, npc.getX(), npc.getY(), npc.getZ(), target.getX(), target.getY(), target.getZ());
    if ((pending == null || active != null && active.tool.startsWith("move")) && stalled) {
      fail(
          "MOVEMENT_STALLED: work route made no verified progress for 20 seconds; select another"
              + " access point");
      return;
    }
    if (now > deadline || operations > 512) {
      fail("Work route time or operation budget exhausted");
      return;
    }
    if (pending != null) {
      if (!pending.isDone()) return;
      var outcome = pending.join();
      pending = null;
      operations++;
      if (!outcome.has("ok") || !outcome.get("ok").getAsBoolean()) {
        if (active != null) rejected.add(active.tool + active.pos.toShortString());
        if (activeFrom != null && activeStep != null)
          rejectedEdges.add(edge(activeFrom, activeStep));
        lastProblem = outcome.toString();
        walking = null;
        walkCache = List.of();
        if (++failures >= 8) {
          fail("Work route operation failed: " + outcome);
          return;
        }
      } else {
        failures = 0;
        if (active == null) movement.edited(now);
        if (active != null && active.tool.startsWith("move_")) rejected.clear();
        if (active != null && active.tool.equals("place")) {
          movement.edited(now);

          walking = null;
          walkCache = List.of();
          if (built.size() >= 512) built.remove(built.iterator().next());
          built.add(active.pos);
          edits++;
          placed++;
        }
        if (active != null && active.tool.equals("dig")) {
          movement.edited(now);

          walking = null;
          walkCache = List.of();
          edits++;
          dug++;
        }
      }
      active = null;
      nextTick = now + 2;
    }
    if (now < nextTick || !npc.onGround() || npc.isUsingItem()) return;
    nextTick = now;
    if (ready(npc.blockPosition(), true)) {
      result.complete(
          object(
              "ok",
              true,
              "placed",
              placed,
              "dug",
              dug,
              "operations",
              operations,
              "position",
              new Gson().toJsonTree(List.of(npc.getX(), npc.getY(), npc.getZ()))));
      return;
    }
    var next = plan();
    if (next == null) {
      if (searchPending) return;
      if (walkIncomplete) {
        fail(
            "128-block walking search incomplete: unloaded terrain or search budget; refusing"
                + " unnecessary block edits");
        return;
      }
      fail(
          "No permitted work route; check protected blocks, hazards, tools and the edit budget. "
              + lastProblem);
      return;
    }
    if ((next.tool.equals("dig") || next.tool.equals("place")) && edits >= limit) {
      fail("Work route block edit budget exhausted");
      return;
    }
    if (next.tool.equals("place") && next.slot < 0) {
      if (mode.equals("build")) {
        fail("No carried dirt/stone for this placement-only route");
        return;
      }
      if (++supplyAttempts > 16) {
        fail("Not enough carried dirt or stone for the work route");
        return;
      }
      if (skills.gatheringScaffolding()) {
        fail("RESOURCE_CYCLE: scaffolding acquisition cannot consume more scaffolding");
        return;
      }
      // Real acquisition; it may fail when no suitable terrain/resources are available.
      pending =
          skills
              .execute(
                  "acquire", object("item", "minecraft:dirt", "count", Math.min(8, limit - edits)))
              .toCompletableFuture();
      active = null;
      activeFrom = activeStep = null;
      return;
    }
    if (next.tool.equals("dig")) {
      int slot = world.bestTool(vector(next.pos));
      if (slot == -2) {
        if (++toolAttempts > 2) {
          fail("A suitable harvesting tool could not be prepared for the obstruction");
          return;
        }
        var state = level.getBlockState(next.pos);
        Item tool =
            state.is(BlockTags.INCORRECT_FOR_STONE_TOOL)
                ? Items.IRON_PICKAXE
                : state.is(BlockTags.INCORRECT_FOR_WOODEN_TOOL)
                    ? Items.STONE_PICKAXE
                    : Items.WOODEN_PICKAXE;
        if (state.is(Blocks.OBSIDIAN)) tool = Items.DIAMOND_PICKAXE;
        pending =
            skills
                .execute("acquire", object("item", NpcRecipes.id(tool), "count", 1))
                .toCompletableFuture();
        active = null;
        activeFrom = activeStep = null;
        return;
      }
      if (slot >= 0) {
        pending =
            skills.execute("equip", object("slot", slot, "hand", "main")).toCompletableFuture();
        active = null;
        activeFrom = activeStep = null;
        return;
      }
    }
    active = next;
    activeFrom = npc.blockPosition();
    activeStep = nextStep;
    var args = xyz(next.pos);
    if (next.tool.equals("place")) args.addProperty("slot", next.slot);
    pending = skills.execute(next.tool, args).toCompletableFuture();
    NpcService.get(level.getServer())
        .record(
            npc,
            "work_route_step",
            next.tool,
            null,
            object(
                "goal",
                goal,
                "mode",
                mode,
                "target",
                xyz(target),
                "args",
                args,
                "edits",
                edits,
                "limit",
                limit));
  }

  private int materialSlot() {
    for (int i = 0; i < 36; i++)
      if (MATERIALS.contains(npc.inventory.getItem(i).getItem())
          && !npc.inventory.getItem(i).isEmpty()) return i;
    return -1;
  }

  private boolean inside(BlockPos p) {
    return level.hasChunkAt(p)
        && level.hasChunkAt(p.above(2))
        && level.isInWorldBounds(p.above(2))
        && level.getWorldBorder().isWithinBounds(p)
        && Math.abs(p.getX() - start.getX()) <= 128
        && Math.abs(p.getZ() - start.getZ()) <= 128
        && p.getY() >= start.getY() - 24
        && p.getY() <= start.getY() + 20;
  }

  private boolean harmful(BlockPos p) {
    if (!inside(p)) return true;
    var state = level.getBlockState(p);
    return !level.getFluidState(p).isEmpty()
        || state.getBlock() instanceof FallingBlock
        || state.is(Blocks.MAGMA_BLOCK)
        || state.is(Blocks.CACTUS)
        || state.is(Blocks.FIRE)
        || state.is(Blocks.SOUL_FIRE)
        || state.is(Blocks.CAMPFIRE)
        || state.is(Blocks.SOUL_CAMPFIRE)
        || state.is(Blocks.POWDER_SNOW)
        || state.is(Blocks.SWEET_BERRY_BUSH);
  }

  private boolean stable(BlockPos p) {
    return inside(p)
        && !harmful(p)
        && !(goal.equals("mine") && level.getBlockState(p).is(BlockTags.LOGS))
        && NpcWalkingRoute.support(level, p);
  }

  private boolean natural(BlockState s) {
    return s.is(BlockTags.LEAVES)
        || s.is(BlockTags.DIRT)
        || s.is(BlockTags.BASE_STONE_OVERWORLD)
        || s.is(BlockTags.BASE_STONE_NETHER)
        || s.is(Blocks.STONE)
        || s.is(Blocks.COBBLESTONE)
        || s.is(Blocks.COBBLED_DEEPSLATE)
        || s.is(Blocks.NETHERRACK)
        || s.is(Blocks.END_STONE)
        || s.is(Blocks.SNOW)
        || s.is(Blocks.SNOW_BLOCK)
        || s.is(Blocks.MOSS_BLOCK);
  }

  private boolean mayBreak(BlockPos p) {
    if (mode.equals("build")
        || !inside(p)
        || p.equals(npc.blockPosition().below())
        || p.equals(target)
        || built.contains(p)
        || walked.contains(p.above())
        || harmful(p)
        || !skills.mayChange(p, 128)) return false;
    var s = level.getBlockState(p);
    var b = s.getBlock();
    if (s.isAir()
        || s.hasBlockEntity()
        || s.getDestroySpeed(level, p) < 0
        || b instanceof DoorBlock
        || b instanceof TrapDoorBlock
        || b instanceof FenceGateBlock
        || b instanceof BedBlock
        || b instanceof CropBlock
        || b instanceof NetherPortalBlock
        || b instanceof EndPortalBlock
        || b instanceof EndPortalFrameBlock) return false;
    if (goal.equals("mine") && !natural(s)) return false;
    // Never release a liquid or gravity block into the path.
    for (var direction : Direction.values()) {
      var adjacent = p.relative(direction);
      if (!level.hasChunkAt(adjacent) || !level.getFluidState(adjacent).isEmpty()) return false;
    }
    return !(level.getBlockState(p.above()).getBlock() instanceof FallingBlock);
  }

  private int clearance(BlockPos p) {
    if (!inside(p) || harmful(p)) return -1;
    if (level.getBlockState(p).getCollisionShape(level, p).isEmpty()) return 0;
    return mayBreak(p) ? 1 : -1;
  }

  private boolean mayFill(BlockPos p) {
    return !mode.equals("dig")
        && !walked.contains(p)
        && !walked.contains(p.below())
        && inside(p)
        && level.getBlockState(p).canBeReplaced()
        && !level.getBlockState(p).hasBlockEntity()
        && skills.mayChange(p, 128)
        && level.getFluidState(p).isEmpty()
        && !harmful(p)
        && level
            .getEntities(npc, new AABB(p), e -> e instanceof LivingEntity && e.isAlive())
            .isEmpty()
        && !npc.getBoundingBox().intersects(new AABB(p));
  }

  private int changes(BlockPos from, BlockPos to) {
    if (!inside(to) || Math.abs(to.getY() - from.getY()) > 1) return -1;
    if ((mode.equals("build") || goal.equals("bridge"))
        && start.getY() == target.getY()
        && to.getY() != start.getY()) return -1;
    int feet = clearance(to), head = clearance(to.above());
    if (feet < 0 || head < 0 || harmful(to.above(2))) return -1;
    if (!level
        .getEntities(
            npc, new AABB(to).expandTowards(0, 1, 0), e -> e instanceof LivingEntity && e.isAlive())
        .isEmpty()) return -1;
    int edits = feet + head;
    if (to.getY() > from.getY()) {
      int jump = clearance(from.above(2));
      if (jump < 0) return -1;
      edits += jump;
    }
    if (to.getY() < from.getY()) {
      int lowering = clearance(to.above(2));
      if (lowering < 0) return -1;
      edits += lowering;
    }
    if (!stable(to.below())) {
      // Descend along already supported steps. A lower floating floor cannot be attached
      // diagonally to the step we are standing on.
      if (to.getY() < from.getY()) return -1;
      if (!mayFill(to.below())) return -1;
      edits++;
      // An upper stair needs a lower block to attach to the preceding stair's side.
      if (to.getY() > from.getY() && !hasSupport(to.below())) {
        if (!mayFill(to.below(2))) return -1;
        edits++;
      }
    }
    return edits;
  }

  private boolean hasSupport(BlockPos p) {
    for (var d : Direction.values()) {
      var q = p.relative(d);
      if (inside(q) && !level.getBlockState(q).getCollisionShape(level, q).isEmpty()) return true;
    }
    return false;
  }

  private BlockPos rayObstacle(BlockPos stand) {
    var eye = Vec3.atBottomCenterOf(stand).add(0, npc.getEyeHeight(), 0);
    var hit =
        level.clip(
            new ClipContext(
                eye,
                Vec3.atCenterOf(target),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                npc));
    return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target)
        ? null
        : hit.getBlockPos();
  }

  private boolean ready(BlockPos stand, boolean actual) {
    if (goal.equals("pickup")) {
      var at = actual ? npc.position() : NpcWalkSurface.point(npc,stand);
      double margin = actual ? 1.1 : .85;
      return Math.abs(at.x - point.x) <= margin
          && Math.abs(at.z - point.z) <= margin
          && point.y >= at.y - .8
          && point.y < at.y + 2.4;
    }
    if (goal.equals("reach") || goal.equals("bridge")){
      if(goal.equals("reach")&&(actual&&npc.isInWater()||NpcWaterTravel.swimmable(npc,stand))){
        var at=actual?npc.position():NpcWalkSurface.point(npc,stand);var end=NpcWalkSurface.point(npc,target);
        return at.distanceToSqr(end)<=1.6&&Math.abs(at.y-end.y)<1;
      }
      return Vec3.atBottomCenterOf(stand).distanceToSqr(Vec3.atBottomCenterOf(target))<=1.6&&Math.abs(stand.getY()-target.getY())<1;
    }
    if (stand.getY() > target.getY()
        || Vec3.atBottomCenterOf(stand).distanceToSqr(Vec3.atCenterOf(target)) > 12.0) return false;
    if (actual) return skills.visible(target) && npc.distanceToSqr(Vec3.atCenterOf(target)) <= 16.0;
    var obstruction = rayObstacle(stand);
    return obstruction == null
        || obstruction.equals(stand)
        || obstruction.equals(stand.above())
        || mayBreak(obstruction);
  }

  private double heuristic(BlockPos p) {
    double horizontal = Math.abs(p.getX() - target.getX()) + Math.abs(p.getZ() - target.getZ());
    double vertical = Math.abs(p.getY() - target.getY());
    return Math.max(0, horizontal - (goal.equals("mine") ? 3 : 1))
        + 4 * Math.max(0, vertical - (goal.equals("mine") ? 3 : 0));
  }

  private Operation plan() {
    var origin = npc.blockPosition();
    nextStep = target;
    searchPending = walkIncomplete = false;
    if (!goal.equals("bridge")) {
      if (walking == null)
        walking =
            NpcWalkingRoute.to(
                npc,
                target,
                p -> ready(p, false) && (!goal.equals("mine") || rayObstacle(p) == null));
      var state = walking.poll();
      if (state == WalkingPathSearch.State.SEARCHING) {
        searchPending = true;
        return null;
      }
      if (state == WalkingPathSearch.State.FOUND) {
        if (walkCache.isEmpty()) walkCache = walking.points();
        int closest = 0;
        double distance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < walkCache.size(); i++) {
          double d = walkCache.get(i).distSqr(origin);
          if (d < distance) {
            distance = d;
            closest = i;
          }
        }
        int end = Math.min(walkCache.size() - 1, closest + 24);
        if (!NpcWalkingRoute.validPoints(npc, walkCache.subList(closest, end + 1))) {
          walking = null;
          walkCache = List.of();
          searchPending = true;
          return null;
        }
        nextStep = walkCache.get(end);
        return operation(
            end == walkCache.size() - 1 && (goal.equals("mine") || goal.equals("pickup"))
                ? "move_precisely"
                : "move_to",
            nextStep,
            -1);
      }
      if (walking.incomplete()) {
        // An unknown distant frontier does not make a local, fully checked work path unsafe.
        // The edit search below still checks loaded terrain, permissions, supports and hazards.
        // Keep distant travel read-only until a walking route or intermediate waypoint is known.
        if (origin.distSqr(target) > 24 * 24) {
          walkIncomplete = true;
          return null;
        }
        NpcService.get(level.getServer()).record(npc, "walking_search_incomplete",
            "Trying a bounded local work path", null, walking.diagnostics());
      }
    }
    if (goal.equals("pickup") && ready(origin, false))
      return operation("move_precisely", origin, -1);
    if (goal.equals("bridge") && !rejected.contains("move_to" + target.toShortString())) {
      var path = npc.getNavigation().createPath(target, 0);
      if (path != null && path.canReach()) {
        boolean safe = true;
        for (int i = 0; i < path.getNodeCount(); i++) {
          var node = path.getNode(i);
          var p = new BlockPos(node.x, node.y, node.z);
          if (!stable(p.below())
              || harmful(p)
              || harmful(p.above())
              || (mode.equals("build") || goal.equals("bridge"))
                  && start.getY() == target.getY()
                  && p.getY() != start.getY()) {
            safe = false;
            break;
          }
        }
        if (safe) return operation("move_to", target, -1);
      }
    }
    if (goal.equals("mine") && ready(origin, false)) {
      var obstacle = rayObstacle(origin);
      if (obstacle != null
          && mayBreak(obstacle)
          && skills.visible(obstacle)
          && npc.distanceToSqr(Vec3.atCenterOf(obstacle)) <= 16)
        return operation("dig", obstacle, -1);
    }
    for (int attempt = 0; attempt < 8; attempt++) {
      var step = searchStep(origin);
      if (step == null) return null;
      nextStep = step;
      var operation = firstOperation(origin, step);
      if (operation != null) return operation;
      rejectedEdges.add(edge(origin, step));
    }
    return null;
  }

  private static String edge(BlockPos from, BlockPos to) {
    return from.toShortString() + ">" + to.toShortString();
  }

  private BlockPos searchStep(BlockPos origin) {
    var queue = new PriorityQueue<Node>(Comparator.comparingDouble(Node::priority));
    var costs = new HashMap<BlockPos, Double>();
    queue.add(new Node(origin, null, 0, heuristic(origin) * 3, 0));
    costs.put(origin, 0.);
    int nodes = 0;
    editSearchLimited = false;
    while (!queue.isEmpty() && ++nodes <= 4096) {
      editSearchNodes = nodes;
      var node = queue.remove();
      if (node.cost > costs.getOrDefault(node.pos, Double.POSITIVE_INFINITY) + .001) continue;
      if (node.previous != null && ready(node.pos, false)) {
        while (node.previous.previous != null) node = node.previous;
        return node.pos;
      }
      for (var d : Direction.Plane.HORIZONTAL)
        for (int dy : new int[] {0, 1, -1}) {
          var next = node.pos.relative(d).offset(0, dy, 0);
          if (node.previous == null && rejectedEdges.contains(edge(origin, next))) continue;
          int change = changes(node.pos, next);
          if (change < 0 || node.changes + change > limit - edits) continue;
          double cost = node.cost + 1 + change * 3 + (dy == 0 ? 0 : .5);
          if (cost >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
          costs.put(next, cost);
          queue.add(new Node(next, node, cost, cost + heuristic(next) * 3, node.changes + change));
        }
    }
    editSearchLimited = !queue.isEmpty();
    return null;
  }

  private Operation operation(String tool, BlockPos pos, int slot) {
    return rejected.contains(tool + pos.toShortString())
        ? null
        : new Operation(tool, pos.immutable(), slot);
  }

  private Operation firstOperation(BlockPos from, BlockPos next) {
    if (next.getY() > from.getY() && clearance(from.above(2)) > 0)
      return operation("dig", from.above(2), -1);
    for (var p :
        next.getY() < from.getY()
            ? List.of(next.above(2), next.above(), next)
            : List.of(next.above(), next))
      if (clearance(p) > 0) {
        var obstruction =
            level.clip(
                new ClipContext(
                    npc.getEyePosition(),
                    Vec3.atCenterOf(p),
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    npc));
        if (obstruction.getType() != HitResult.Type.MISS && !obstruction.getBlockPos().equals(p))
          p = obstruction.getBlockPos();
        return mayBreak(p) ? operation("dig", p, -1) : null;
      }
    if (!stable(next.below())) {
      var fill = next.below();
      if (!hasSupport(fill) && next.getY() > from.getY()) fill = fill.below();
      return operation("place", fill, materialSlot());
    }
    return operation("move_precisely", next, -1);
  }

  private static JsonArray vector(BlockPos p) {
    return new Gson()
        .toJsonTree(List.of(p.getX() + .5, p.getY() + .5, p.getZ() + .5))
        .getAsJsonArray();
  }

  private static JsonObject xyz(BlockPos p) {
    return object("x", p.getX(), "y", p.getY(), "z", p.getZ());
  }

  private void fail(String reason) {
    skills.cancel(reason);
    var response = object("ok", false, "error", reason, "placed", placed, "dug", dug);
    response.addProperty("editSearchNodes", editSearchNodes);
    response.addProperty("editSearchLimited", editSearchLimited);
    response.addProperty("rejectedEdges", rejectedEdges.size());
    if (walking != null) response.add("walkingSearch", walking.diagnostics());
    result.complete(response);
  }

  JsonObject progress() {
    return object(
        "target",
        xyz(target),
        "goal",
        goal,
        "mode",
        mode,
        "edits",
        edits,
        "placed",
        placed,
        "dug",
        dug,
        "max_blocks",
        limit,
        "operations",
        operations,
        "searchPending",
        searchPending,
        "walkingNodes",
        walking == null ? 0 : walking.expanded(),
        "cachedPoints",
        walkCache.size(),
        "childBusy",
        skills.busy());
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    skills.cancel("Work route cancelled");
    result.complete(
        object("ok", false, "error", "Work route cancelled", "placed", placed, "dug", dug));
  }
}

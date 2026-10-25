package dev.avatarcraft.npc.ai;

import dev.avatarcraft.npc.CompanionEntity;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.pathfinder.*;

/** Cached terrain, incremental server-thread search, and native navigation paths. */
final class NpcWalkingRoute {
  private static final Map<MinecraftServer, Budget> BUDGETS = new WeakHashMap<>();

  private static final class Budget {
    long tick = -1, spent;
    int nodes;
  }

  private final CompanionEntity npc;
  private final WalkingPathSearch search;
  private final Terrain terrain;
  private final BlockPos destination;
  private final long started;
  private boolean incomplete;
  private boolean unknownGoals;

  NpcWalkingRoute(CompanionEntity npc, BlockPos destination, Collection<BlockPos> goals) {
    this(npc, destination, goals, p -> true);
  }

  NpcWalkingRoute(
      CompanionEntity npc,
      BlockPos destination,
      Collection<BlockPos> goals,
      Predicate<BlockPos> permitted) {
    this.npc = npc;
    this.destination = destination.immutable();
    this.terrain = new Terrain(npc, destination, 128);
    this.terrain.permitted = permitted;
    this.started = npc.level().getGameTime();
    search =
        new WalkingPathSearch(
            cell(NpcWalkSurface.standingCell(npc)),
            goals.stream().filter(terrain::stand).map(NpcWalkingRoute::cell).toList(),
            terrain);
    if (search.state() == WalkingPathSearch.State.UNREACHABLE && terrain.unloaded)
      incomplete = true;
  }

  static NpcWalkingRoute to(CompanionEntity npc, BlockPos destination, Predicate<BlockPos> goal) {
    var goals = new ArrayList<BlockPos>();
    boolean unknown = false;
    for (var p :
        BlockPos.betweenClosed(destination.offset(-4, -4, -4), destination.offset(4, 1, 4))) {
      boolean loaded = true;
      for (int cx = Math.min(p.getX(), destination.getX()) >> 4;
          cx <= Math.max(p.getX(), destination.getX()) >> 4;
          cx++)
        for (int cz = Math.min(p.getZ(), destination.getZ()) >> 4;
            cz <= Math.max(p.getZ(), destination.getZ()) >> 4;
            cz++) if (npc.level().getChunkSource().getChunkNow(cx, cz) == null) loaded = false;
      if (!loaded) {
        unknown = true;
        continue;
      }
      if (goal.test(p)) goals.add(p.immutable());
    }
    var route = new NpcWalkingRoute(npc, destination, goals);
    route.unknownGoals = unknown;
    if (unknown && route.search.state() == WalkingPathSearch.State.UNREACHABLE)
      route.incomplete = true;
    return route;
  }

  WalkingPathSearch.State poll() {
    if (search.state() != WalkingPathSearch.State.SEARCHING) return search.state();
    if (npc.level().getGameTime() - started > 240) {
      incomplete = true;
      return WalkingPathSearch.State.LIMIT;
    }
    var server = npc.level().getServer();
    var budget = BUDGETS.computeIfAbsent(server, s -> new Budget());
    long tick = server.getTickCount();
    if (budget.tick != tick) {
      budget.tick = tick;
      budget.nodes = 0;
      budget.spent = 0;
    }
    if (budget.nodes >= 4096 || budget.spent >= 2_000_000) return WalkingPathSearch.State.SEARCHING;
    long begin = System.nanoTime();
    int before = search.expanded();
    var state =
        search.step(
            Math.min(512, 4096 - budget.nodes),
            begin + Math.min(750_000, 2_000_000 - budget.spent));
    budget.spent += System.nanoTime() - begin;
    budget.nodes += search.expanded() - before;
    if (state == WalkingPathSearch.State.UNREACHABLE && (terrain.unloaded || unknownGoals))
      incomplete = true;
    return state;
  }

  boolean incomplete() {
    return incomplete || search.state() == WalkingPathSearch.State.LIMIT;
  }

  List<BlockPos> points() {
    var points =
        new ArrayList<>(
            search.path().stream().map(c -> new BlockPos(c.x(), c.y(), c.z())).toList());
    // An actor can be physically grounded while its centre overhangs the supporting voxel.
    // A* may expand that initial cell, but navigation must start at its first stable neighbour,
    // not repeatedly invalidate the overhanging start cell. Every subsequent cell is validated.
    if (points.size() > 1 && !terrain.stand(points.getFirst())) points.removeFirst();
    return List.copyOf(points);
  }

  int expanded() {
    return search.expanded();
  }

  Path path() {
    return nativePath(points(), destination);
  }

  static Path nativePath(List<BlockPos> points, BlockPos destination) {
    var nodes = new ArrayList<Node>();
    for (var p : points) {
      var n = new Node(p.getX(), p.getY(), p.getZ());
      n.type = PathType.WALKABLE;
      nodes.add(n);
    }
    var path = new Path(nodes, destination, true);
    NpcGroundNavigation.mark(path);
    return path;
  }

  static boolean validAhead(CompanionEntity npc, Path path) {
    var terrain = new Terrain(npc, path.getTarget(), 8);
    for (int i = path.getNextNodeIndex();
        i < Math.min(path.getNodeCount(), path.getNextNodeIndex() + 6);
        i++) {
      var n = path.getNode(i);
      if (!terrain.stand(new BlockPos(n.x, n.y, n.z))) return false;
      if (i > 0) {
        var previous = path.getNode(i - 1);
        if (!terrain.transition(
            new WalkingPathSearch.Cell(previous.x, previous.y, previous.z),
            new WalkingPathSearch.Cell(n.x, n.y, n.z))) return false;
      }
    }
    return true;
  }

  static boolean validPoints(CompanionEntity npc, List<BlockPos> points) {
    var terrain = new Terrain(npc, null, 8);
    for (int i = 0; i < points.size(); i++) {
      var p = points.get(i);
      if (!terrain.stand(p) || i > 0 && !terrain.transition(cell(points.get(i - 1)), cell(p)))
        return false;
    }
    return true;
  }

  static boolean support(net.minecraft.world.level.Level level, BlockPos p) {
    var shape = level.getBlockState(p).getCollisionShape(level, p);
    return !shape.isEmpty() && shape.max(Direction.Axis.Y) <= 1.00001;
  }

  static BlockPos landing(CompanionEntity npc, BlockPos preferred) {
    var terrain = new Terrain(npc, null, 8);
    if (npc.distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(preferred)) <= 120 * 120
        && terrain.stand(preferred)) return preferred;
    BlockPos best = null;
    double score = Double.POSITIVE_INFINITY;
    // A stair's landing height can differ from straight-line interpolation by many blocks.
    for (int dy = -96; dy <= 96; dy++) {
      var p = preferred.above(dy);
      double cost = p.distSqr(preferred);
      if (cost < score
          && npc.distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(p)) <= 120 * 120
          && terrain.stand(p)) {
        best = p;
        score = cost;
      }
    }
    for (var p :
        BlockPos.betweenClosed(preferred.offset(-6, -12, -6), preferred.offset(6, 12, 6))) {
      double cost = p.distSqr(preferred);
      if (cost >= score
          || npc.distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(p)) > 120 * 120
          || !terrain.stand(p)) continue;
      best = p.immutable();
      score = cost;
    }
    return best;
  }

  private static WalkingPathSearch.Cell cell(BlockPos p) {
    return new WalkingPathSearch.Cell(p.getX(), p.getY(), p.getZ());
  }

  private static final class Terrain implements WalkingPathSearch.Terrain {
    final CompanionEntity npc;
    final Map<Long, Boolean> cache = new HashMap<>();
    final Set<Long> occupied = new HashSet<>();
    final BlockPos allowed;
    Predicate<BlockPos> permitted = p -> true;
    boolean unloaded;

    Terrain(CompanionEntity npc, BlockPos allowed, int actorRange) {
      this.npc = npc;
      this.allowed = allowed;
      for (var actor :
          npc
              .level()
              .getEntitiesOfClass(
                  net.minecraft.world.entity.LivingEntity.class,
                  npc.getBoundingBox().inflate(actorRange),
                  e -> e != npc && e.isAlive() && !e.isSpectator())
              .stream()
              .limit(256)
              .toList()) {
        var box =
            actor
                .getBoundingBox()
                .inflate(npc.getBbWidth() / 2. + .03, 0, npc.getBbWidth() / 2. + .03);
        for (int x = (int) Math.ceil(box.minX - .5); x <= Math.floor(box.maxX - .5); x++)
          for (int z = (int) Math.ceil(box.minZ - .5); z <= Math.floor(box.maxZ - .5); z++)
            for (int y = (int) Math.ceil(box.minY - npc.getBbHeight() + .01); y < box.maxY; y++)
              occupied.add(BlockPos.asLong(x, y, z));
      }
    }

    public boolean stand(WalkingPathSearch.Cell p) {
      return stand(new BlockPos(p.x(), p.y(), p.z()));
    }

    boolean stand(BlockPos p) {
      return cache.computeIfAbsent(p.asLong(), k -> compute(p));
    }

    public boolean headroom(WalkingPathSearch.Cell p) {
      // Continuous collision clearance is checked by transition, including partial floors.
      return true;
    }

    private boolean compute(BlockPos p) {
      if (!permitted.test(p)) return false;
      if (occupied.contains(p.asLong()) && !p.equals(allowed)) return false;
      if (!npc.level().hasChunkAt(p)
          || !npc.level().hasChunkAt(p.below())
          || !npc.level().hasChunkAt(p.above())) {
        unloaded = true;
        return false;
      }
      return NpcWalkSurface.standable(npc, p);
    }

    public boolean transition(WalkingPathSearch.Cell from, WalkingPathSearch.Cell to) {
      var a = new BlockPos(from.x(), from.y(), from.z());
      var b = new BlockPos(to.x(), to.y(), to.z());
      double ay = NpcWalkSurface.height(npc.level(), npc, a),
          by = NpcWalkSurface.height(npc.level(), npc, b);
      if (!Double.isFinite(ay)) ay = npc.getY();
      return Double.isFinite(by)
          && by - ay <= 1.00001
          && ay - by <= 1.25001
          && (Math.abs(ay - by) < 1e-5 || NpcWalkSurface.stepClear(npc, a, b, ay, by));
    }
  }
}

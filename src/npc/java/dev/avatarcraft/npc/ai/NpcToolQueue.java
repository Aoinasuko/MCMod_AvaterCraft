package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.*;
import dev.avatarcraft.npc.CompanionEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;

/** Shared bounded scheduler. All side effects go through the existing NpcSkills tool dispatcher. */
final class NpcToolQueue implements AutoCloseable {
  private final CompanionEntity npc;
  private final ServerLevel level;
  private final NpcSkills tools;
  private final NpcLocalWorld world;
  private final List<ToolProgram.Node> nodes;
  private final Map<String, Integer> ids = new HashMap<>();
  private final BitSet done = new BitSet();
  private final Map<Integer, String> failures = new HashMap<>();
  private final String key;
  private final ToolSchedule schedule;
  private final long deadline;
  private CompletableFuture<JsonObject> pending;
  private NpcWalkingRoute walking;
  private int active = -1,
      cursor,
      passProgress,
      pass = 1,
      edits,
      remaining,
      verify = -1,
      inFlight = -1;
  private String pendingKind = "", reason = "";
  private final JsonArray unfinished = new JsonArray();
  private final Set<BlockPos> triedStands = new HashSet<>();
  private boolean closed, forceApproach;
  final CompletableFuture<JsonObject> result = new CompletableFuture<>();

  NpcToolQueue(CompanionEntity npc, List<ToolProgram.Node> nodes, int depth, Set<String> chain) {
    if (depth > 4 || nodes.isEmpty() || nodes.size() > 8192)
      throw new IllegalArgumentException("Invalid queue size/depth");
    ToolProgram.validateDependencies(nodes);
    this.npc = npc;
    this.level = (ServerLevel) npc.level();
    this.nodes = List.copyOf(nodes);
    tools = new NpcSkills(npc, (s, id) -> {}, depth, chain);
    tools.continuousWork(true);
    world = new NpcLocalWorld(npc, tools);
    for (int i = 0; i < nodes.size(); i++) {
      ids.put(nodes.get(i).id(), i);
      var c = nodes.get(i).cell();
      var p = new BlockPos(c.x(), c.y(), c.z());
      if (!level.isInWorldBounds(p)
          || !level.getWorldBorder().isWithinBounds(p)
          || npc.distanceToSqr(Vec3.atCenterOf(p)) > 128 * 128)
        throw new IllegalArgumentException(
            "Every work cell must be within the 128-block work range");
    }
    try {
      key =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(
                          (npc.instruction()
                                  + level.dimension().identifier()
                                  + new Gson().toJson(nodes))
                              .getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    deadline = level.getGameTime() + 36000;
    try {
      var saved = JsonParser.parseString(npc.workSequenceState()).getAsJsonObject();
      if (saved.get("key").getAsString().equals(key)) {
        done.or(BitSet.valueOf(Base64.getDecoder().decode(saved.get("done").getAsString())));
        int previous = saved.get("inflight").getAsInt();
        if (done.length() > nodes.size())
          throw new IllegalArgumentException("Invalid completed queue bits");
        if (previous >= 0
            && previous < nodes.size()
            && nodes.get(previous).expect().isEmpty()
            && !Set.of(
                    "move_to",
                    "move_precisely",
                    "equip",
                    "look_at",
                    "observe",
                    "inspect_block",
                    "find_chests",
                    "find_blocks")
                .contains(nodes.get(previous).tool())
            && !alreadyPlaced(nodes.get(previous)))
          reason =
              "An interrupted non-idempotent tool has no verifiable outcome; inspect before"
                  + " retrying";
      }
    } catch (RuntimeException ignored) {
      done.clear();
    }
    for (int i = 0; i < nodes.size(); i++) {
      var n = nodes.get(i);
      if (done.get(i)
          && !n.expect().isEmpty()
          && !matches(n, n.expect())
          && (n.required() || matches(n, n.when()))) done.clear(i);
    }
    schedule = new ToolSchedule(nodes, done);
    if (!reason.isEmpty()) verify = 0;
  }

  void tick() {
    if (closed || result.isDone()) return;
    try {
      tools.tick();
      long deadlineNanos = System.nanoTime() + 1_000_000;
      for (int transitions = 0;
          transitions < 16 && !result.isDone() && System.nanoTime() < deadlineNanos;
          transitions++) {
        var oldPending = pending;
        int oldActive = active,
            oldCursor = cursor,
            oldDone = done.cardinality(),
            oldVerify = verify;
        advance();
        if (walking != null || pending != null && !pending.isDone()) break;
        if (oldPending == pending
            && oldActive == active
            && oldCursor == cursor
            && oldDone == done.cardinality()
            && oldVerify == verify) break;
      }
    } catch (RuntimeException e) {
      finishWith("Queue action rejected: " + e.getMessage());
    }
  }

  private void advance() {
    if (!npc.isAlive()
        || npc.level() != level
        || npc.ownerId() == null
        || level.getServer().getPlayerList().getPlayer(npc.ownerId()) == null) {
      close();
      return;
    }
    if (verify >= 0) {
      verify();
      return;
    }
    if (level.getGameTime() > deadline) {
      finishWith("Queue time budget exceeded");
      return;
    }
    if (pending != null) {
      if (!pending.isDone()) return;
      var r = pending.join();
      pending = null;
      inFlight = -1;
      if (r.get("ok").getAsBoolean()) {
        if (pendingKind.equals("action")) {
          edits++;
          schedule.changed();
          var n = nodes.get(active);
          if (n.expect().isEmpty() || matches(n, n.expect())) complete(active);
          else {
            failures.put(active, "Tool accepted but expected world state not reached");
            schedule.defer(active);
            active = -1;
          }
        }
      } else {
        failures.put(active, r.has("error") ? r.get("error").getAsString() : "Tool failed");
        if (pendingKind.equals("action") && spatial(nodes.get(active))) {
          triedStands.add(NpcWalkSurface.standingCell(npc));
          forceApproach = true;
        } else {
          schedule.defer(active);
          active = -1;
        }
      }
      persist();
      return;
    }
    if (walking != null) {
      var state = walking.poll();
      if (state == WalkingPathSearch.State.SEARCHING) return;
      if (state != WalkingPathSearch.State.FOUND || walking.points().isEmpty()) {
        reject("No walking approach or search incomplete");
        return;
      }
      var p = walking.points().getLast();
      walking = null;
      triedStands.add(p);
      start("move_to", xyz(p), "move");
      return;
    }
    if (!npc.onGround() && !npc.isInWater()) return;
    for (int budget = 0; budget < 8; budget++) {
      if (active < 0) {
        active = schedule.next();
        pass = schedule.pass();
        cursor++;
        if (active < 0) {
          verify = 0;
          return;
        }
        triedStands.clear();
        forceApproach = false;
      }
      var n = nodes.get(active);
      if (!done.get(active) && n.expect().isEmpty() && alreadyPlaced(n)) {
        complete(active);
        continue;
      }
      if (done.get(active)) {
        active = -1;
        continue;
      }
      if (!n.expect().isEmpty() && matches(n, n.expect())) {
        complete(active);
        continue;
      }
      if (!matches(n, n.when())) {
        if (!n.required()) {
          complete(active);
          continue;
        }
        reject("Required cell precondition is not satisfied");
        continue;
      }
      var p = target(n);
      String unsafe = safety(n, p);
      if (!unsafe.isEmpty()) {
        reject(unsafe);
        continue;
      }
      if (spatial(n) && (forceApproach || !ready(n, p, npc.position()))) {
        forceApproach = false;
        if (triedStands.size() >= 4) {
          reject("No usable interaction face after bounded alternative approaches");
          continue;
        }
        var goals = new ArrayList<BlockPos>();
        for (var stand : BlockPos.betweenClosed(p.offset(-3, -2, -3), p.offset(3, 2, 3)))
          if (!triedStands.contains(stand)
              && NpcWalkSurface.standable(npc, stand)
              && ready(n, p, NpcWalkSurface.point(npc, stand))) goals.add(stand.immutable());
        if (goals.isEmpty()) {
          reject("No safe visible approach without extra block edits");
          continue;
        }
        walking = new NpcWalkingRoute(npc, p, goals);
        return;
      }
      if (n.tool().equals("dig")) {
        int slot = world.bestTool(vector(p));
        if (slot == -2) {
          finishWith("TOOL_MISSING: prepare a harvesting tool");
          return;
        }
        if (slot >= 0) {
          start("equip", object("slot", slot, "hand", "main"), "equip");
          return;
        }
      }
      var args = n.arguments().deepCopy();
      if (args.has("slot")
          && args.get("slot").isJsonPrimitive()
          && args.get("slot").getAsJsonPrimitive().isString()) {
        int slot = slot(n, args.get("slot").getAsString());
        if (slot < 0
            && n.tool().equals("place")
            && NpcRecipes.id(npc.getMainHandItem().getItem()).equals(n.item())) {
          for (int bag = 0; bag < 36; bag++)
            if (npc.inventory.getItem(bag).isEmpty()) {
              start("equip", object("slot", bag, "hand", "main"), "equip");
              return;
            }
        }
        if (slot < 0) {
          finishWith("MATERIAL_MISSING: " + n.item() + " or required inventory buffer/tool");
          return;
        }
        args.addProperty("slot", slot);
      }
      start(n.tool(), args, "action");
      return;
    }
  }

  private int slot(ToolProgram.Node n, String binding) {
    for (int i = 0; i <= 41; i++) {
      if (i >= 36 && i != 41 || n.tool().equals("place") && i == 41) continue;
      var stack = npc.inventory.getItem(i);
      if (!stack.isEmpty()
          && (binding.equals("$hoe")
              ? stack.is(ItemTags.HOES)
              : NpcRecipes.id(stack.getItem()).equals(n.item()))) return i;
    }
    return -1;
  }

  private boolean alreadyPlaced(ToolProgram.Node n) {
    if (!n.tool().equals("place") || n.item().isEmpty()) return false;
    var p = target(n);
    return level.hasChunkAt(p)
        && NpcRecipes.id(level.getBlockState(p).getBlock().asItem()).equals(n.item());
  }

  private boolean matches(ToolProgram.Node n, JsonObject predicates) {
    var p = new BlockPos(n.cell().x(), n.cell().y(), n.cell().z());
    for (var e : predicates.entrySet()) {
      var q = e.getKey().equals("above") ? p.above() : p;
      if (!level.hasChunkAt(q)) return false;
      var state = level.getBlockState(q);
      String id = e.getValue().getAsString();
      if (id.startsWith("#")
          ? !state.is(TagKey.create(Registries.BLOCK, Identifier.parse(id.substring(1))))
          : !state.is(BuiltInRegistries.BLOCK.getValue(Identifier.parse(id)))) return false;
    }
    return true;
  }

  private BlockPos target(ToolProgram.Node n) {
    var a = n.arguments();
    return a.has("x") && a.has("y") && a.has("z")
        ? BlockPos.containing(
            a.get("x").getAsDouble(), a.get("y").getAsDouble(), a.get("z").getAsDouble())
        : new BlockPos(n.cell().x(), n.cell().y(), n.cell().z());
  }

  private boolean spatial(ToolProgram.Node n) {
    return Set.of(
            "dig",
            "place",
            "use_on_block",
            "interact_block",
            "deposit",
            "withdraw",
            "inspect_block",
            "furnace_put",
            "furnace_take")
        .contains(n.tool());
  }

  private String safety(ToolProgram.Node n, BlockPos p) {
    if (!spatial(n)) return "";
    if (!level.hasChunkAt(p)
        || !level.isInWorldBounds(p)
        || !level.getWorldBorder().isWithinBounds(p)) return "Unloaded/outside work cell";
    if (!tools.mayChange(p, 256)) return "Protected work cell";
    var state = level.getBlockState(p);
    var b = state.getBlock();
    if (n.tool().equals("dig")) {
      if (state.hasBlockEntity()
          || b instanceof DoorBlock
          || b instanceof TrapDoorBlock
          || b instanceof FenceGateBlock
          || b instanceof BedBlock
          || b instanceof CropBlock
          || b instanceof NetherPortalBlock
          || b instanceof EndPortalBlock
          || b instanceof EndPortalFrameBlock
          || state.getDestroySpeed(level, p) < 0) return "Protected structure or unbreakable block";
      if (!level.getFluidState(p).isEmpty()
          || level.getBlockState(p.above()).getBlock() instanceof FallingBlock)
        return "Unsafe fluid/gravity excavation";
      for (var d : Direction.values())
        if (!level.hasChunkAt(p.relative(d)) || !level.getFluidState(p.relative(d)).isEmpty())
          return "Fluid/unloaded neighbour";
    }
    if (n.tool().equals("place") && !state.isAir() && !state.is(Blocks.WATER))
      return "Occupied cell must not be overwritten";
    if (n.tool().equals("use_on_block")
        && n.arguments().get("face").getAsString().equals("up")
        && !tools.mayChange(p.above(), 256)) return "Protected upper face";
    return "";
  }

  private boolean ready(ToolProgram.Node n, BlockPos p, Vec3 feet) {
    if (feet.distanceToSqr(Vec3.atCenterOf(p)) > 15.5) return false;
    if (n.tool().equals("dig") && p.equals(BlockPos.containing(feet).below())) return false;
    var eye = feet.add(0, npc.getEyeHeight(), 0);
    if (n.tool().equals("place")) {
      if (npc.getBoundingBox().move(feet.subtract(npc.position())).intersects(new AABB(p)))
        return false;
      for (var face : Direction.values()) {
        var support = p.relative(face.getOpposite());
        if (!level.hasChunkAt(support)
            || !tools.mayChange(support, 256)
            || level.getBlockState(support).getCollisionShape(level, support).isEmpty()) continue;
        if (tools.visibleHitFrom(
            eye,
            Vec3.atCenterOf(support)
                .add(face.getStepX() * .5, face.getStepY() * .5, face.getStepZ() * .5),
            support)) return true;
      }
      return false;
    }
    if (n.arguments().has("face")) {
      var face = Direction.byName(n.arguments().get("face").getAsString());
      return tools.visibleHitFrom(
          eye,
          Vec3.atCenterOf(p).add(face.getStepX() * .5, face.getStepY() * .5, face.getStepZ() * .5),
          p);
    }
    return tools.visibleFrom(eye, p);
  }

  private void start(String name, JsonObject args, String kind) {
    pendingKind = kind;
    inFlight = kind.equals("action") ? active : -1;
    persist();
    pending = tools.execute(name, args).toCompletableFuture();
  }

  private void complete(int i) {
    schedule.complete(i);
    active = -1;
    persist();
  }

  private void reject(String message) {
    failures.put(active, message);
    schedule.defer(active);
    active = -1;
    walking = null;
  }

  private void finishWith(String error) {
    reason = error;
    tools.cancel(error);
    pending = null;
    walking = null;
    inFlight = -1;
    if (verify < 0) verify = 0;
  }

  private void persist() {
    npc.workSequenceState(
        object(
                "key",
                key,
                "done",
                Base64.getEncoder().encodeToString(done.toByteArray()),
                "inflight",
                inFlight)
            .toString());
  }

  private void verify() {
    for (int budget = 0; budget < 8 && verify < nodes.size(); budget++, verify++) {
      var n = nodes.get(verify);
      boolean satisfied =
          !n.expect().isEmpty()
              ? matches(n, n.expect()) || !n.required() && !matches(n, n.when())
              : done.get(verify);
      if (!satisfied) {
        remaining++;
        if (unfinished.size() < 16) {
          var o = xyz(target(n));
          o.addProperty("node", n.id());
          o.addProperty("tool", n.tool());
          o.addProperty(
              "reason",
              failures.getOrDefault(
                  verify, reason.isEmpty() ? "Unfinished/dependency blocked" : reason));
          unfinished.add(o);
        }
      }
    }
    if (verify < nodes.size()) return;
    var out = progress();
    out.addProperty("ok", remaining == 0 && reason.isEmpty());
    out.addProperty("remaining", remaining);
    out.add("unfinished", unfinished);
    if (!out.get("ok").getAsBoolean())
      out.addProperty(
          "error",
          "WORK_INCOMPLETE: "
              + remaining
              + " nodes remain; "
              + (reason.isEmpty()
                  ? unfinished.isEmpty()
                      ? "Unverified outcome"
                      : unfinished.get(0).getAsJsonObject().get("reason").getAsString()
                  : reason));
    result.complete(out);
    close();
  }

  JsonObject progress() {
    return object(
        "total",
        nodes.size(),
        "completed",
        done.cardinality(),
        "worked",
        edits,
        "changes",
        edits,
        "pass",
        pass,
        "checking",
        verify >= 0);
  }

  private JsonObject xyz(BlockPos p) {
    return object("x", p.getX(), "y", p.getY(), "z", p.getZ());
  }

  private JsonArray vector(BlockPos p) {
    return new Gson().toJsonTree(List.of(p.getX(), p.getY(), p.getZ())).getAsJsonArray();
  }

  public void close() {
    if (closed) return;
    closed = true;
    tools.cancel("Work queue stopped");
    if (!result.isDone()) {
      var out = progress();
      out.addProperty("ok", false);
      out.addProperty("error", "Work queue stopped before verification");
      result.complete(out);
    }
  }
}

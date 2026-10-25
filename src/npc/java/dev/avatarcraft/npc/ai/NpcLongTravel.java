package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.*;
import dev.avatarcraft.npc.CompanionEntity;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.*;

/** Sequential walking reservations. Each leg keeps the existing 128-block search and watchdog. */
final class NpcLongTravel implements AutoCloseable {
  private final CompanionEntity npc;
  private final NpcSkills child;
  private final ServerLevel level;
  private final List<BlockPos> reservations = new ArrayList<>();
  private final JsonArray arrived = new JsonArray();
  private final String tool;
  private final Vec3 start;
  private final long deadline;
  private int index;
  private CompletableFuture<JsonObject> pending;
  final CompletableFuture<JsonObject> result = new CompletableFuture<>();

  NpcLongTravel(CompanionEntity npc, String tool, BlockPos target, int depth, Set<String> chain) {
    this.npc = npc;
    this.tool = tool;
    level = (ServerLevel) npc.level();
    start = npc.position();
    if (start.distanceToSqr(Vec3.atBottomCenterOf(target)) > 512 * 512 + .0001)
      throw new IllegalArgumentException("TRAVEL_TOO_FAR: maximum reservation is 512 blocks");
    if (!level.isInWorldBounds(target) || !level.getWorldBorder().isWithinBounds(target))
      throw new IllegalArgumentException("Travel target outside world bounds");
    var from = npc.blockPosition();
    var route =
        npc.executionMemory.returnPath(
            level.dimension().identifier().toString(),
            from.getX(),
            from.getY(),
            from.getZ(),
            target.getX(),
            target.getY(),
            target.getZ());
    if (!route.isEmpty()) {
      var anchor = from;
      BlockPos previous = from;
      for (var v : route) {
        var p = new BlockPos(v.get(0), v.get(1), v.get(2));
        if (anchor.distSqr(p) > 112 * 112) {
          reservations.add(previous);
          anchor = previous;
        }
        previous = p;
      }
      reservations.add(target);
    } else
      for (var v :
          NpcTravelReservations.straight(
              from.getX(), from.getY(), from.getZ(), target.getX(), target.getY(), target.getZ()))
        reservations.add(new BlockPos(v.get(0), v.get(1), v.get(2)));
    child = new NpcSkills(npc, (text, id) -> {}, depth, chain);
    deadline = level.getGameTime() + 20 * 60 * 15;
    NpcService.get(level.getServer())
        .record(npc, "travel_reserved", "Long-distance walking reservation", null, progress());
  }

  void tick() {
    if (result.isDone()) return;
    child.tick();
    if (level.getGameTime() > deadline) {
      finish(false, "Long travel reservation timed out");
      return;
    }
    if (pending != null) {
      if (!pending.isDone()) return;
      var outcome = pending.join();
      pending = null;
      if (!outcome.has("ok") || !outcome.get("ok").getAsBoolean()) {
        finish(false, "Travel leg " + (index + 1) + " failed: " + outcome);
        return;
      }
      arrived.add(object("x", npc.getX(), "y", npc.getY(), "z", npc.getZ()));
      NpcService.get(level.getServer())
          .record(npc, "travel_leg_complete", "Verified intermediate arrival", null, progress());
      if (++index == reservations.size()) {
        finish(true, "");
        return;
      }
    }
    var target = reservations.get(index);
    if (index < reservations.size() - 1) target = NpcWalkingRoute.landing(npc, target);
    if (target == null || !level.hasChunkAt(target)) {
      finish(
          false,
          "TRAVEL_UNLOADED_OR_UNSAFE: no loaded safe intermediate landing; remaining reservations"
              + " cancelled");
      return;
    }
    if (npc.distanceToSqr(Vec3.atBottomCenterOf(target)) > 128 * 128) {
      finish(false, "Travel intermediate exceeds the 128-block leg limit");
      return;
    }
    NpcService.get(level.getServer())
        .record(
            npc,
            "travel_leg_start",
            "Walking reserved leg",
            null,
            object("leg", index + 1, "x", target.getX(), "y", target.getY(), "z", target.getZ()));
    pending =
        child
            .execute(
                index < reservations.size() - 1 ? "move_to" : tool,
                object("x", target.getX(), "y", target.getY(), "z", target.getZ()))
            .toCompletableFuture();
  }

  JsonObject progress() {
    return object(
        "leg",
        Math.min(index + 1, reservations.size()),
        "completedLegs",
        arrived.size(),
        "totalLegs",
        reservations.size(),
        "arrived",
        arrived.deepCopy(),
        "reservations",
        new Gson()
            .toJsonTree(
                reservations.stream().map(p -> List.of(p.getX(), p.getY(), p.getZ())).toList()));
  }

  private void finish(boolean ok, String error) {
    var out = progress();
    out.addProperty("ok", ok);
    out.addProperty("error", error);
    out.addProperty("moved_distance", npc.position().distanceTo(start));
    child.cancel("Travel reservation ended");
    result.complete(out);
  }

  public void close() {
    if (!result.isDone()) finish(false, "Travel reservation cancelled");
  }
}

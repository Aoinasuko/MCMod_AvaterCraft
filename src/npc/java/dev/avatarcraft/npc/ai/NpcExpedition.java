package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.*;
import dev.avatarcraft.npc.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/** Searches only loaded chunk references while physically moving through walkable terrain. */
final class NpcExpedition implements AutoCloseable {
  private final CompanionEntity npc;
  private final String structure;
  private final Set<String> visited = new LinkedHashSet<>();
  private int moves;
  private long next;
  private BlockPos destination;
  private final TravelPace pace = new TravelPace();
  final CompletableFuture<JsonObject> result = new CompletableFuture<>();

  NpcExpedition(CompanionEntity npc, String structure) {
    this.npc = npc;
    this.structure = structure;
    var level = (ServerLevel) npc.level();
    if (!level
        .registryAccess()
        .lookupOrThrow(Registries.STRUCTURE)
        .containsKey(Identifier.parse(structure)))
      throw new IllegalArgumentException("Unknown structure ID");
    try {
      var saved = JsonParser.parseString(npc.expeditionState()).getAsJsonObject();
      if (saved.get("structure").getAsString().equals(structure)
          && saved
              .get("dimension")
              .getAsString()
              .equals(level.dimension().identifier().toString())) {
        moves = saved.get("moves").getAsInt();
        if (moves < 0 || moves > 256 || saved.getAsJsonArray("visited").size() > 256)
          throw new IllegalArgumentException();
        for (var v : saved.getAsJsonArray("visited")) visited.add(v.getAsString());
      }
    } catch (RuntimeException ignored) {
    }
  }

  static JsonArray inspect(CompanionEntity npc, int radius) {
    if (radius < 0 || radius > 1)
      throw new IllegalArgumentException("Loaded structure scan radius is 0..1 chunks");
    var level = (ServerLevel) npc.level();
    var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
    var found = new JsonArray();
    var seen = new HashSet<String>();
    var center = npc.chunkPosition();
    for (int dx = -radius; dx <= radius; dx++)
      for (int dz = -radius; dz <= radius; dz++) {
        var chunk = new ChunkPos(center.x() + dx, center.z() + dz);
        var p = chunk.getMiddleBlockPosition(npc.blockPosition().getY());
        if (!level.hasChunkAt(p)) continue;
        for (var s : level.structureManager().getAllStructuresAt(p).keySet()) {
          String id = registry.getKey(s).toString();
          for (var start : level.structureManager().startsForStructure(chunk.x(), chunk.z(), s)) {
            var box = start.getBoundingBox();
            var at = box.getCenter();
            String key = id + "/" + at;
            if (!seen.add(key)) continue;
            found.add(
                object(
                    "structure",
                    id,
                    "x",
                    at.getX(),
                    "y",
                    at.getY(),
                    "z",
                    at.getZ(),
                    "source",
                    "loaded_chunk_reference"));
            if (found.size() >= 24) return found;
          }
        }
      }
    return found;
  }

  void tick() {
    if (result.isDone()) return;
    if (destination != null) {
      pace.update(
          npc.distanceToSqr(Vec3.atBottomCenterOf(destination)), NpcSkills.canSprint(npc), false);
      npc.setSprinting(pace.sprinting());
      npc.getNavigation().setSpeedModifier(pace.speed());
    }
    var level = (ServerLevel) npc.level();
    long now = level.getGameTime();
    if (now < next) return;
    next = now + 10;
    for (var e : inspect(npc, 1))
      if (e.getAsJsonObject().get("structure").getAsString().equals(structure)) {
        var found = e.getAsJsonObject();
        found.addProperty("dimension", level.dimension().identifier().toString());
        found.addProperty("worker", npc.getUUID().toString());
        found.addProperty("owner", npc.ownerId().toString());
        var data = NpcWorkData.get(level);
        if (data.findings.size() < 64
            || data.findings.containsKey(npc.getUUID() + "/" + structure)) {
          data.findings.put(npc.getUUID() + "/" + structure, found.deepCopy());
          data.setDirty();
        }
        result.complete(object("ok", true, "found", found));
        npc.getNavigation().stop();
        save();
        return;
      }
    if (npc.getHealth() < 6 || npc.isOnFire() || npc.isInLava()) {
      fail("危険のため構造物探索を保留しました");
      return;
    }
    if (destination != null && npc.distanceToSqr(Vec3.atBottomCenterOf(destination)) < 2) {
      visited.add(NpcLocalWorld.key(destination));
      destination = null;
      moves++;
      save();
    }
    if (moves >= 256 || visited.size() >= 256) {
      fail("探索予算内で構造物を発見できませんでした");
      return;
    }
    if (destination != null && !npc.getNavigation().isDone()) return;
    if (destination != null) {
      visited.add(NpcLocalWorld.key(destination));
      destination = null;
      moves++;
      save();
    }
    int offset = (moves / 8) % 8;
    for (int i = 0; i < 16; i++)
      for (int dy : new int[] {0, 1, -1, 2, -2}) {
        double angle = (i + offset) * Math.PI / 8;
        var p =
            npc.blockPosition()
                .offset(
                    (int) Math.round(Math.cos(angle) * 8),
                    dy,
                    (int) Math.round(Math.sin(angle) * 8));
        if (visited.contains(NpcLocalWorld.key(p))
            || !level.hasChunkAt(p)
            || !level.getWorldBorder().isWithinBounds(p)
            || !level.getBlockState(p).getCollisionShape(level, p).isEmpty()
            || !level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
            || !level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)
            || !level.getFluidState(p).isEmpty()
            || !level.getFluidState(p.below()).isEmpty()) continue;
        var path = npc.getNavigation().createPath(p, 0);
        pace.update(npc.distanceToSqr(Vec3.atBottomCenterOf(p)), NpcSkills.canSprint(npc), false);
        if (path != null && path.canReach() && npc.getNavigation().moveTo(path, pace.speed())) {
          npc.setSprinting(pace.sprinting());
          destination = p;
          return;
        }
      }
    fail("進める構造物探索経路がありません。通路の確保や別方向への計画が必要です");
  }

  private void save() {
    npc.setExpeditionState(
        object(
                "structure",
                structure,
                "dimension",
                npc.level().dimension().identifier().toString(),
                "moves",
                moves,
                "visited",
                new Gson().toJsonTree(visited))
            .toString());
  }

  private void fail(String reason) {
    result.complete(object("ok", false, "error", reason));
    save();
  }

  public void close() {
    pace.reset();
    npc.setSprinting(false);
    save();
    npc.getNavigation().stop();
    if (!result.isDone()) result.complete(object("ok", false, "error", "Expedition paused"));
  }
}

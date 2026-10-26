package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.JsonObject;
import dev.avatarcraft.npc.CompanionEntity;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;

/**
 * Where the body is relative to the water around it, measured the way a player would judge it:
 * eyes under or not, standing on the bottom, how deep below, how far up to air, whether that air is
 * open sky or a lid of ice, and the nearest air pocket and shore.
 */
public record NpcWaterState(
    boolean inWater,
    boolean eyeSubmerged,
    boolean wading,
    int depthBelowFeet,
    double toSurface,
    boolean surfaceOpenAir,
    BlockPos nearestAir,
    BlockPos nearestShore) {
  /** Columns deeper than this are reported as this many; the planner only needs "very deep". */
  static final int MAX_DEPTH = 32;

  public static NpcWaterState of(CompanionEntity npc) {
    var level = npc.level();
    var feet = npc.blockPosition();
    boolean inWater = npc.isInWater();
    boolean eye = npc.isEyeInFluid(FluidTags.WATER);
    int depth = 0;
    for (var p = feet.below(); depth < MAX_DEPTH && NpcWaterTravel.water(level, p); p = p.below())
      depth++;
    var lid = BlockPos.containing(npc.getEyePosition());
    int up = 0;
    while (up < MAX_DEPTH && NpcWaterTravel.water(level, lid)) {
      lid = lid.above();
      up++;
    }
    boolean open = open(level, lid);
    double toSurface = eye ? lid.getY() - npc.getEyePosition().y : 0;
    BlockPos air = !inWater ? null : eye && open ? lid : nearestAir(level, feet);
    BlockPos shore = inWater ? nearestShore(npc, feet) : null;
    return new NpcWaterState(
        inWater, eye, inWater && !eye && npc.onGround(), depth, toSurface, open, air, shore);
  }

  /** True when the first non-water cell above the eyes lets the head out: no ice, roof or fluid. */
  public static boolean surfaceOpen(CompanionEntity npc) {
    var lid = BlockPos.containing(npc.getEyePosition());
    for (int up = 0; up < MAX_DEPTH && NpcWaterTravel.water(npc.level(), lid); up++) lid = lid.above();
    return open(npc.level(), lid);
  }

  private static boolean open(Level level, BlockPos p) {
    return level.hasChunkAt(p)
        && level.getFluidState(p).isEmpty()
        && level.getBlockState(p).getCollisionShape(level, p).isEmpty();
  }

  /** A water cell with breathable air above it, by a small search through connected water. */
  private static BlockPos nearestAir(Level level, BlockPos from) {
    var queue = new ArrayDeque<BlockPos>(List.of(from));
    var seen = new HashSet<Long>(List.of(from.asLong()));
    for (int n = 0; !queue.isEmpty() && n < 2048; n++) {
      var p = queue.removeFirst();
      if (!NpcWaterTravel.water(level, p) && n > 0) continue;
      if (open(level, p.above()) && NpcWaterTravel.water(level, p)) return p.above().immutable();
      for (var d : Direction.values()) {
        var q = p.relative(d);
        if (Math.abs(q.getX() - from.getX()) > 12 || Math.abs(q.getZ() - from.getZ()) > 12) continue;
        if (seen.add(q.asLong()) && NpcWaterTravel.water(level, q)) queue.addLast(q);
      }
    }
    return null;
  }

  /** The nearest dry standing cell within ten blocks, around the surface height. */
  private static BlockPos nearestShore(CompanionEntity npc, BlockPos from) {
    var level = npc.level();
    var column = NpcWaterTravel.current(npc);
    int y = column.top() + 1;
    BlockPos best = null;
    double score = Double.POSITIVE_INFINITY;
    for (int r = 1; r <= 10 && best == null; r++)
      for (int dx = -r; dx <= r; dx++)
        for (int dz = -r; dz <= r; dz++) {
          if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
          for (int dy = -1; dy <= 1; dy++) {
            var p = new BlockPos(from.getX() + dx, y + dy, from.getZ() + dz);
            if (!level.hasChunkAt(p) || !level.getFluidState(p).isEmpty()) continue;
            if (!NpcWalkSurface.standable(npc, p)) continue;
            double d = p.distSqr(from);
            if (d < score) {
              score = d;
              best = p.immutable();
            }
          }
        }
    return best;
  }

  /** The compact form sent to the planner, or null on dry land. */
  public JsonObject json() {
    if (!inWater) return null;
    return object(
        "eye_submerged", eyeSubmerged,
        "wading", wading,
        "depth_below_feet", depthBelowFeet,
        "to_surface", Math.round(toSurface * 10) / 10.,
        "surface_open_air", surfaceOpenAir,
        "nearest_air", cell(nearestAir),
        "nearest_shore", cell(nearestShore));
  }

  private static Object cell(BlockPos p) {
    if (p == null) return com.google.gson.JsonNull.INSTANCE;
    var a = new com.google.gson.JsonArray();
    a.add(p.getX());
    a.add(p.getY());
    a.add(p.getZ());
    return a;
  }
}

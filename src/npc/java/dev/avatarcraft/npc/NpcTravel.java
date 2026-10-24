package dev.avatarcraft.npc;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.*;

/** Explicit owner recall/recovery only; autonomous walking and tools do not teleport. */
public final class NpcTravel {
  public static Vec3 safePosition(ServerLevel level, Vec3 origin) {
    var center = BlockPos.containing(origin);
    for (int r = 0; r <= 8; r++)
      for (int dy : new int[] {0, 1, 2, 3, -1, -2})
        for (int x = -r; x <= r; x++)
          for (int z = -r; z <= r; z++) {
            if (Math.max(Math.abs(x), Math.abs(z)) != r) continue;
            var p = center.offset(x, dy, z);
            if (!level.isInWorldBounds(p.above()) || !level.getWorldBorder().isWithinBounds(p))
              continue;
            level.getChunkAt(p);
            var floor = level.getBlockState(p.below());
            if (!level.isInWorldBounds(p.above())
                || !level.getWorldBorder().isWithinBounds(p)
                || !floor.isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)
                || floor.is(Blocks.MAGMA_BLOCK)
                || floor.is(Blocks.CAMPFIRE)
                || floor.is(Blocks.SOUL_CAMPFIRE)
                || floor.is(Blocks.CACTUS)
                || !level.getFluidState(p).isEmpty()
                || !level.getFluidState(p.above()).isEmpty()) continue;
            var at = Vec3.atBottomCenterOf(p);
            if (level.noCollision(
                new AABB(at.x - .3, at.y, at.z - .3, at.x + .3, at.y + 1.8, at.z + .3))) return at;
          }
    return null;
  }

  public static CompanionEntity move(
      CompanionEntity npc, ServerLevel level, Vec3 destination, float yaw) {
    var at = safePosition(level, destination);
    if (at == null) return null;
    npc.stopRiding();
    npc.ejectPassengers();
    var moved =
        npc.teleport(
            new TeleportTransition(level, at, Vec3.ZERO, yaw, 0, TeleportTransition.DO_NOTHING));
    if (moved instanceof CompanionEntity actor) {
      actor.setDeltaMovement(Vec3.ZERO);
      actor.fallDistance = 0;
      NpcRoster.get(level.getServer()).update(actor);
      return actor;
    }
    return null;
  }

  private NpcTravel() {}
}

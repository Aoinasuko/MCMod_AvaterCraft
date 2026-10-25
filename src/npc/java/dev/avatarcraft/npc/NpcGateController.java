package dev.avatarcraft.npc;

import dev.avatarcraft.npc.ai.NpcWalkSurface;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.*;

/** Opens only gates on the active walking path; closes only gates this NPC opened. */
final class NpcGateController {
  private final CompanionEntity npc;
  private final Map<BlockPos, Long> opened = new LinkedHashMap<>();

  NpcGateController(CompanionEntity npc) {
    this.npc = npc;
  }

  void tick() {
    if (!(npc.level() instanceof ServerLevel level)) return;
    for (var entry : new ArrayList<>(opened.entrySet())) {
      var p = entry.getKey();
      if (!level.hasChunkAt(p)) continue;
      var state = level.getBlockState(p);
      if (!(state.getBlock() instanceof FenceGateBlock) || !state.getValue(FenceGateBlock.OPEN)) {
        opened.remove(p);
        continue;
      }
      if (state.getValue(FenceGateBlock.POWERED)) {
        opened.remove(p);
        continue;
      }
      if (level.getGameTime() - entry.getValue() < 10
          || npc.distanceToSqr(Vec3.atCenterOf(p)) < 2.25) continue;
      var path = npc.getNavigation().getPath();
      boolean ahead = false;
      if (path != null)
        for (int i = path.getNextNodeIndex();
            i < Math.min(path.getNodeCount(), path.getNextNodeIndex() + 5);
            i++) if (path.getNodePos(i).equals(p)) ahead = true;
      if (ahead
          || !level
              .getEntities(
                  npc,
                  new AABB(p).inflate(.6, 1, .6),
                  e -> e.isAlive() && e instanceof LivingEntity)
              .isEmpty()) continue;
      if (NpcWalkSurface.mayOpen(npc, p)) change(p, false);
      opened.remove(p);
    }
    var path = npc.getNavigation().getPath();
    if (path == null || path.isDone()) return;
    for (int i = path.getNextNodeIndex();
        i < Math.min(path.getNodeCount(), path.getNextNodeIndex() + 5);
        i++) {
      var p = path.getNodePos(i);
      var state = level.getBlockState(p);
      if (!(state.getBlock() instanceof FenceGateBlock)
          || state.getValue(FenceGateBlock.OPEN)
          || npc.distanceToSqr(Vec3.atCenterOf(p)) > 9
          || !NpcWalkSurface.mayOpen(npc, p)) continue;
      if (opened.size() >= 8) continue;
      change(p, true);
      opened.put(p.immutable(), level.getGameTime());
    }
  }

  private void change(BlockPos p, boolean open) {
    var level = (ServerLevel) npc.level();
    var state = level.getBlockState(p);
    if (!(state.getBlock() instanceof FenceGateBlock gate)) return;
    if (open && state.getValue(FenceGateBlock.FACING) == npc.getDirection().getOpposite())
      state = state.setValue(FenceGateBlock.FACING, npc.getDirection());
    level.setBlock(p, state.setValue(FenceGateBlock.OPEN, open), 10);
    level.playSound(
        null,
        p,
        open ? gate.openSound : gate.closeSound,
        SoundSource.BLOCKS,
        1f,
        .9f + level.getRandom().nextFloat() * .1f);
    level.gameEvent(npc, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, p);
  }
}

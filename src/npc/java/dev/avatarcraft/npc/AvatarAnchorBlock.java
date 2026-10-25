package dev.avatarcraft.npc;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class AvatarAnchorBlock extends BaseEntityBlock {
  public AvatarAnchorBlock(Properties properties) {
    super(properties);
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
    return new AvatarAnchorBlockEntity(pos, state);
  }

  @Override
  public RenderShape getRenderShape(BlockState state) {
    return RenderShape.MODEL;
  }

  @Override
  protected InteractionResult useWithoutItem(
      BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
    if (player instanceof ServerPlayer server) NpcNetwork.openAnchor(server, pos);
    return InteractionResult.SUCCESS;
  }

  @Override
  protected void affectNeighborsAfterRemoval(
      BlockState state, ServerLevel level, BlockPos pos, boolean moved) {
    NpcAnchors.remove(level, pos);
    super.affectNeighborsAfterRemoval(state, level, pos, moved);
  }
}

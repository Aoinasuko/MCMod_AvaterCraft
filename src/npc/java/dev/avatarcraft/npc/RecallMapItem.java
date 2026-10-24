package dev.avatarcraft.npc;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

public final class RecallMapItem extends Item {
  public RecallMapItem(Properties properties) {
    super(properties);
  }

  @Override
  public InteractionResult use(Level level, Player player, InteractionHand hand) {
    if (player instanceof ServerPlayer server) NpcNetwork.openRecall(server, 0);
    return InteractionResult.SUCCESS;
  }
}

package dev.avatarcraft.npc;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

public final class SummoningItem extends Item {
  public SummoningItem(Properties properties) {
    super(properties);
  }

  public InteractionResult useOn(UseOnContext context) {
    var player = context.getPlayer();
    if (player == null || !player.mayBuild()) return InteractionResult.FAIL;
    var level = context.getLevel();
    if (!(level instanceof ServerLevel server)) return InteractionResult.SUCCESS;
    var pos = context.getClickedPos().relative(context.getClickedFace());
    if (!server.mayInteract(player, pos) || !server.hasChunkAt(pos)) return InteractionResult.FAIL;
    var npc = NpcMod.COMPANION.get().create(server, EntitySpawnReason.SPAWN_ITEM_USE);
    if (npc == null) return InteractionResult.FAIL;
    npc.setPos(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
    npc.setYRot(player.getYRot());
    npc.initialize(player, "tortobu");
    if (!server.noCollision(npc) || !server.addFreshEntity(npc)) return InteractionResult.FAIL;
    dev.avatarcraft.npc.ai.NpcService.get(server.getServer())
        .record(npc, "summoned", "召喚されました", null, new com.google.gson.JsonObject());
    if (!player.getAbilities().instabuild) context.getItemInHand().shrink(1);
    return InteractionResult.SUCCESS;
  }
}

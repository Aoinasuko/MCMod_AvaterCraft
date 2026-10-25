package dev.avatarcraft.npc;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.phys.AABB;

/** Native wooden-door interaction; never breaks a door or bypasses owner build restrictions. */
final class NpcDoorGoal extends OpenDoorGoal {
  private final CompanionEntity npc;
  private boolean opened;
  private int remaining;
  private net.minecraft.world.phys.Vec3 entrySide;

  NpcDoorGoal(CompanionEntity npc) {
    super(npc, true);
    this.npc = npc;
  }

  private boolean permitted() {
    if (!(npc.level() instanceof ServerLevel level) || npc.ownerId() == null) return false;
    var owner = level.getServer().getPlayerList().getPlayer(npc.ownerId());
    return owner != null
        && owner.mayBuild()
        && level.mayInteract(owner, doorPos)
        && level.mayInteract(owner, doorPos.below());
  }

  @Override
  public boolean canUse() {
    return super.canUse() && !isOpen() && permitted();
  }

  @Override
  public void start() {
    remaining = 60;
    entrySide =
        npc.position()
            .subtract(net.minecraft.world.phys.Vec3.atCenterOf(doorPos))
            .multiply(1, 0, 1);
    super.start();
  }

  @Override
  public boolean canContinueToUse() {
    if (remaining <= 0 || !isOpen() || !permitted()) return false;
    var side =
        npc.position()
            .subtract(net.minecraft.world.phys.Vec3.atCenterOf(doorPos))
            .multiply(1, 0, 1);
    return side.dot(entrySide) >= 0 || side.lengthSqr() < .81;
  }

  @Override
  public void tick() {
    remaining--;
    super.tick();
  }

  @Override
  protected void setOpen(boolean open) {
    if (!permitted() || !hasDoor) return;
    var state = npc.level().getBlockState(doorPos);
    if (!(state.getBlock() instanceof DoorBlock) || !DoorBlock.isWoodenDoor(npc.level(), doorPos))
      return;
    if (!open) {
      if (!opened || state.getValue(DoorBlock.POWERED)) return;
      // Do not shut the door on a following player or another NPC.
      if (!npc.level()
          .getEntities(
              npc,
              new AABB(doorPos.below()).expandTowards(0, 1, 0).inflate(.6),
              e -> e.isAlive() && e instanceof net.minecraft.world.entity.LivingEntity)
          .isEmpty()) return;
    }
    super.setOpen(open);
    opened = open;
  }
}

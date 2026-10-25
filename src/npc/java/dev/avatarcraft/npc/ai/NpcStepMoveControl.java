package dev.avatarcraft.npc.ai;

import dev.avatarcraft.npc.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;

/** Lets collision auto-stepping handle partial floors instead of vanilla's in-block jump rule. */
public final class NpcStepMoveControl extends MoveControl<CompanionEntity> {
  public NpcStepMoveControl(CompanionEntity npc) {
    super(npc);
  }

  @Override
  public void tick() {
    boolean partialHere = Math.abs(mob.getY() - Math.rint(mob.getY())) > 1e-4;
    boolean partialThere = Math.abs(wantedY - Math.rint(wantedY)) > 1e-4;
    double rise = wantedY - mob.getY();
    var target = BlockPos.containing(wantedX, Math.ceil(wantedY - 1e-6), wantedZ);
    if (operation != Operation.MOVE_TO
        || !mob.onGround()
        || !(partialHere || partialThere)
        || rise > mob.maxUpStep() + 1e-5
        || rise < -1.25001
        || !NpcWalkSurface.standable(mob, target)) {
      super.tick();
      return;
    }
    operation = Operation.WAIT;
    double dx = wantedX - mob.getX(), dz = wantedZ - mob.getZ();
    if (dx * dx + dz * dz + rise * rise < MIN_SPEED_SQR) {
      mob.setZza(0);
      return;
    }
    float yaw = (float) (Mth.atan2(dz, dx) * 180 / Math.PI) - 90;
    mob.setYRot(rotlerp(mob.getYRot(), yaw, 90));
    mob.setSpeed((float) (speedModifier * mob.getAttributeValue(Attributes.MOVEMENT_SPEED)));
  }
}

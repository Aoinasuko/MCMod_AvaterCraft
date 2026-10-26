package dev.avatarcraft.npc.ai;

import dev.avatarcraft.npc.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;

/** Lets collision auto-stepping handle partial floors instead of vanilla's in-block jump rule. */
public final class NpcStepMoveControl extends MoveControl<CompanionEntity> {
  /** Modifier that callers use for ordinary walking; it maps to the player's full forward input. */
  static final double WALKING_MODIFIER = 1.6;

  /** Precise one-block edit steps keep the previous slow pace; their stairs are tuned for it. */
  private boolean precise;

  public NpcStepMoveControl(CompanionEntity npc) {
    super(npc);
  }

  void precise(boolean precise) {
    this.precise = precise;
  }

  @Override
  public void tick() {
    if(operation==Operation.MOVE_TO&&mob.isInWater()){
      var wanted=new net.minecraft.world.phys.Vec3(wantedX,wantedY,wantedZ);
      super.tick();NpcWaterTravel.propel(mob,wanted,false);return;
    }
    boolean moving = operation == Operation.MOVE_TO || operation == Operation.JUMPING;
    stepTick();
    if (moving) playerInput();
  }

  /**
   * Mob.setSpeed also writes the forward input, so acceleration grows with the speed squared and
   * Player attributes give about a quarter of the player's pace. Players move at the attribute speed
   * (sprinting adds its own modifier) with input up to 1, so use that instead.
   */
  private void playerInput() {
    if (precise || mob.zza == 0 || mob.isInWater()) return;
    double input = Math.min(1, speedModifier / WALKING_MODIFIER);
    // Ground momentum coasts about 1.2 times the current speed, so brake into the last point
    // like a player would; intermediate path nodes keep full input.
    var path = mob.getNavigation().getPath();
    if (path == null || path.isDone() || path.getNextNodeIndex() >= path.getNodeCount() - 1) {
      double dx = wantedX - mob.getX(), dz = wantedZ - mob.getZ();
      input = Math.min(input, Math.max(.3, Math.sqrt(dx * dx + dz * dz) / 1.2));
    }
    mob.setSpeed((float) mob.getAttributeValue(Attributes.MOVEMENT_SPEED));
    mob.setZza((float) input);
  }

  private void stepTick() {
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

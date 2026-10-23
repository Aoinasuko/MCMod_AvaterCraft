package dev.avatarcraft.npc.ai;

import dev.avatarcraft.npc.CompanionEntity;
import net.minecraft.world.entity.Entity;

/** Server navigation independent of model inference and line of sight. Never teleports. */
final class NpcFollowing {
  private final CompanionEntity npc;
  private final FollowPace pace = new FollowPace();
  private long nextPath;

  NpcFollowing(CompanionEntity npc) {
    this.npc = npc;
  }

  void tick(Entity target) {
    if (target == null
        || !target.isAlive()
        || target.level() != npc.level()
        || npc.isPassenger()
        || npc.isUsingItem()) {
      stop();
      return;
    }
    boolean wasMoving = pace.moving(), wasSprinting = pace.sprinting();
    pace.update(npc.distanceToSqr(target));
    npc.setSprinting(pace.sprinting());
    if (!pace.moving()) {
      if (wasMoving) npc.getNavigation().stop();
      return;
    }
    npc.getLookControl().setLookAt(target);
    long now = npc.level().getGameTime();
    if (!wasMoving || wasSprinting != pace.sprinting() || now >= nextPath) {
      nextPath = now + 10;
      // A temporarily blocked path is retried; foliage and corners must not cancel following.
      npc.getNavigation().moveTo(target, pace.speed());
    }
  }

  void stop() {
    if (pace.moving()) npc.getNavigation().stop();
    pace.reset();
    npc.setSprinting(false);
    nextPath = 0;
  }
}

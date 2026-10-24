package dev.avatarcraft.npc.ai;

import dev.avatarcraft.npc.CompanionEntity;
import net.minecraft.world.entity.Entity;

/** Server navigation independent of model inference and line of sight. Never teleports. */
final class NpcFollowing {
  private final CompanionEntity npc;
  private final FollowPace pace = new FollowPace();
  private long nextPath;
  private long nextPathNotice;
  private boolean missingNotified, pathNotified;

  NpcFollowing(CompanionEntity npc) {
    this.npc = npc;
  }

  void tick(Entity target) {
    if (target == null
        || !target.isAlive()
        || target.level() != npc.level()
        || npc.isPassenger()
        || npc.isUsingItem()) {
      if ((target == null || !target.isAlive() || target.level() != npc.level())
          && !missingNotified
          && npc.level().getServer().getPlayerList().getPlayer(npc.ownerId()) != null) {
        missingNotified = true;
        NpcService.get(npc.level().getServer())
            .notice(npc, "追従先のオーナーが見つからないか、別のディメンジョンにいます。呼び戻しの地図で呼び戻せます。");
      }
      stop();
      return;
    }
    missingNotified = false;
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
      boolean found = npc.getNavigation().moveTo(target, pace.speed());
      if (!found && !pathNotified && now >= nextPathNotice) {
        pathNotified = true;
        nextPathNotice = now + 1200;
        NpcService.get(npc.level().getServer()).notice(npc, "オーナーへの追従経路が見つかりません。経路を再探索しています。");
      }
      if (found) pathNotified = false;
    }
  }

  void stop() {
    if (pace.moving()) npc.getNavigation().stop();
    pace.reset();
    npc.setSprinting(false);
    nextPath = 0;
  }
}

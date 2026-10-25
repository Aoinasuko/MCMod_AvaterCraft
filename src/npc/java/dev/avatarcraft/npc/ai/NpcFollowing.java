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
  private NpcWalkingRoute walking;
  private net.minecraft.world.level.pathfinder.Path path;
  private net.minecraft.core.BlockPos destination;

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
    boolean wasMoving = pace.moving();
    pace.update(npc.distanceToSqr(target));
    npc.setSprinting(pace.sprinting());
    if (!pace.moving()) {
      if (wasMoving) npc.getNavigation().stop();
      return;
    }
    npc.getLookControl().setLookAt(target);
    long now = npc.level().getGameTime();
    if (npc.distanceToSqr(target) > 128 * 128) {
      if (!pathNotified && now >= nextPathNotice) {
        NpcService.get(npc.level().getServer())
            .notice(npc, "オーナーが128ブロックの経路探索範囲外です。近づくか呼び戻しの地図を使ってください。");
        pathNotified = true;
        nextPathNotice = now + 1200;
      }
      npc.getNavigation().stop();
      walking = null;
      path = null;
      return;
    }
    var goal = target.blockPosition();
    if (path != null && now >= nextPath) {
      nextPath = now + 10;
      if (!NpcWalkingRoute.validAhead(npc, path) || path.isDone()) {
        walking = null;
        path = null;
      }
    }
    if (!wasMoving || destination == null || destination.distSqr(goal) > 9) {
      walking = null;
      path = null;
      nextPath = now;
    }
    if (path == null && (walking != null || now >= nextPath)) {
      if (walking == null) {
        destination = goal;
        walking =
            NpcWalkingRoute.to(npc, goal, p -> p.getY() == goal.getY() && p.distSqr(goal) <= 2);
      }
      var state = walking.poll();
      if (state == WalkingPathSearch.State.SEARCHING) return;
      if (state == WalkingPathSearch.State.FOUND) {
        path = walking.path();
        npc.getNavigation().moveTo(path, pace.speed());
        pathNotified = false;
      } else {
        walking = null;
        nextPath = now + 40;
        if (!pathNotified && now >= nextPathNotice) {
          pathNotified = true;
          nextPathNotice = now + 1200;
          NpcService.get(npc.level().getServer()).notice(npc, "オーナーへの追従経路が見つかりません。経路を再探索しています。");
        }
      }
    }
    npc.getNavigation().setSpeedModifier(pace.speed());
    if (path != null && npc.getNavigation().getPath() != path)
      npc.getNavigation().moveTo(path, pace.speed());
  }

  void stop() {
    if (pace.moving()) npc.getNavigation().stop();
    pace.reset();
    npc.setSprinting(false);
    nextPath = 0;
    walking = null;
    path = null;
    destination = null;
  }
}

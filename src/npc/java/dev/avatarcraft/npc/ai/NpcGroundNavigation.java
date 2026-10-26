package dev.avatarcraft.npc.ai;

import java.util.*;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;

/** Retains vanilla movement/door handling, with accurate corners for our cached grid paths. */
public final class NpcGroundNavigation extends GroundPathNavigation {
  private static final Map<Path, Boolean> GRID_PATHS = new WeakHashMap<>();

  public NpcGroundNavigation(Mob mob, Level level) {
    super(mob, level);
  }

  static void mark(Path path) {
    GRID_PATHS.put(path, true);
  }

  @Override
  public boolean moveTo(Path path,double speed){
    if(path!=null&&mob instanceof dev.avatarcraft.npc.CompanionEntity npc)
      for(int i=path.getNextNodeIndex();i<path.getNodeCount();i++){
        var p=path.getNodePos(i);var water=NpcWaterTravel.water(level,p)?p:p.below();
        if(!NpcWaterTravel.water(level,water))continue;
        var column=NpcWaterTravel.column(level,water);
        if(!column.known()||column.depth()>NpcWaterTravel.SAFE_DEPTH){stop();return false;}
      }
    return super.moveTo(path,speed);
  }

  @Override
  protected void followThePath() {
    var path = getPath();
    if (path == null || !GRID_PATHS.containsKey(path)) {
      super.followThePath();
      return;
    }
    if (!path.isDone()) {
      var p = path.getNextNodePos();
      if (Math.abs(mob.getX() - p.getX() - .5) <= .2
          && Math.abs(mob.getZ() - p.getZ() - .5) <= .2
          && Math.abs(mob.getY() - NpcWalkSurface.point(mob, p).y) < .5) path.advance();
    }
    doStuckDetection(getTempMobPos());
  }
}

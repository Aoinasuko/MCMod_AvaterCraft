package dev.avatarcraft.npc.ai;

import dev.avatarcraft.npc.CompanionEntity;
import net.minecraft.core.*;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Loaded, bounded water columns and collision-respecting surface swimming. Never teleports. */
public final class NpcWaterTravel {
  public static final int SAFE_DEPTH=5;
  public record Column(int top,int depth,boolean known,double surface){}
  public static boolean water(Level level,BlockPos p){return level.hasChunkAt(p)&&level.isInWorldBounds(p)&&level.getFluidState(p).is(FluidTags.WATER);}
  public static Column column(Level level,BlockPos at){
    if(!water(level,at))return new Column(at.getY(),0,false,at.getY());
    var top=at;
    for(int i=0;i<32&&water(level,top.above());i++)top=top.above();
    if(water(level,top.above())||!level.hasChunkAt(top.above()))return new Column(top.getY(),6,false,top.getY()+1);
    int depth=0;var p=top;
    while(depth<=SAFE_DEPTH&&water(level,p)){depth++;p=p.below();}
    boolean known=depth<=SAFE_DEPTH&&level.hasChunkAt(p)&&level.isInWorldBounds(p);
    double surface=top.getY()+level.getFluidState(top).getHeight(level,top);
    return new Column(top.getY(),depth,known,surface);
  }
  public static Column current(CompanionEntity npc){
    var p=npc.blockPosition();return column(npc.level(),water(npc.level(),p)?p:p.below());
  }
  public static boolean bodyClear(CompanionEntity npc,AABB body){
    var level=npc.level();
    for(var p:BlockPos.betweenClosed(BlockPos.containing(body.minX,body.minY,body.minZ),BlockPos.containing(body.maxX-1e-6,body.maxY-1e-6,body.maxZ-1e-6))){
      if(!level.hasChunkAt(p)||!level.isInWorldBounds(p)||!level.getWorldBorder().isWithinBounds(p))return false;
      var s=level.getBlockState(p);var f=s.getFluidState();
      if(!f.isEmpty()&&!f.is(FluidTags.WATER)||NpcWalkSurface.hazard(s.getBlock()))return false;
      if(NpcWalkSurface.openable(npc,p))continue;
      for(var box:s.getCollisionShape(level,p,CollisionContext.of(npc)).toAabbs())if(box.move(p.getX(),p.getY(),p.getZ()).intersects(body))return false;
    }
    return true;
  }
  public static boolean swimmable(CompanionEntity npc,BlockPos cell){
    var level=npc.level();if(water(level,cell)||!water(level,cell.below()))return false;
    var c=column(level,cell.below());
    if(!c.known||c.depth<1||c.depth>SAFE_DEPTH||cell.getY()!=c.top+1)return false;
    var floor=cell.below(c.depth+1);
    if(level.getBlockState(floor).getCollisionShape(level,floor).isEmpty()||NpcWalkSurface.hazard(level.getBlockState(floor).getBlock()))return false;
    double feet=c.surface-.4,r=npc.getBbWidth()/2.;
    var head=BlockPos.containing(cell.getX()+.5,feet+npc.getEyeHeight(),cell.getZ()+.5);
    if(!level.getFluidState(head).isEmpty())return false;
    return bodyClear(npc,new AABB(cell.getX()+.5-r+1e-5,feet+1e-5,cell.getZ()+.5-r+1e-5,
        cell.getX()+.5+r-1e-5,feet+npc.getBbHeight(),cell.getZ()+.5+r-1e-5));
  }
  public static boolean passable(CompanionEntity npc,BlockPos p){return NpcWalkSurface.standable(npc,p)||swimmable(npc,p);}
  public static double height(CompanionEntity npc,BlockPos p){
    double dry=NpcWalkSurface.height(npc.level(),npc,p);
    return Double.isFinite(dry)?dry:swimmable(npc,p)?column(npc.level(),p.below()).surface-.4:Double.NaN;
  }
  public static boolean transition(CompanionEntity npc,BlockPos a,BlockPos b,double ay,double by){
    if(!Double.isFinite(ay)||!Double.isFinite(by)||by-ay>1.00001||ay-by>1.25001)return false;
    if(!swimmable(npc,a)&&!swimmable(npc,b))return Math.abs(ay-by)<1e-5||NpcWalkSurface.stepClear(npc,a,b,ay,by);
    double r=npc.getBbWidth()/2.,y=Math.max(ay,by);
    return bodyClear(npc,new AABB(a.getX()+.5-r+1e-5,y+1e-5,a.getZ()+.5-r+1e-5,
        a.getX()+.5+r-1e-5,y+npc.getBbHeight(),a.getZ()+.5+r-1e-5).expandTowards(b.getX()-a.getX(),0,b.getZ()-a.getZ()));
  }
  public static boolean flowing(CompanionEntity npc){
    var box=npc.getBoundingBox();
    for(var p:BlockPos.betweenClosed(BlockPos.containing(box.minX,box.minY,box.minZ),BlockPos.containing(box.maxX,box.maxY,box.maxZ))){
      if(!water(npc.level(),p))continue;
      var v=npc.level().getFluidState(p).getFlow(npc.level(),p);if(v.x*v.x+v.z*v.z>1e-6)return true;
    }
    return false;
  }
  /** Extra propulsion, not position changes: vanilla collision, buoyancy and current remain active. */
  public static void propel(CompanionEntity npc,Vec3 target,boolean emergency){
    if(!npc.isInWater())return;
    var delta=target.subtract(npc.position());double length=Math.sqrt(delta.x*delta.x+delta.z*delta.z);
    boolean sprint=flowing(npc)||emergency;npc.setSprinting(sprint);npc.setSwimming(sprint);
    var v=npc.getDeltaMovement();double thrust=sprint?.045:.025,cap=sprint?.22:.16;
    double x=v.x,z=v.z;
    if(length>.15){x+=delta.x/length*thrust;z+=delta.z/length*thrust;}
    double horizontal=Math.sqrt(x*x+z*z);if(horizontal>cap){x=x/horizontal*cap;z=z/horizontal*cap;}
    double y=v.y;
    if(delta.y>.12)y=Math.max(y,emergency?.14:.10);
    else if(delta.y<-.35&&!emergency)y=Math.max(-.08,Math.min(y,-.025));
    npc.setDeltaMovement(x,y,z);
    if(delta.y>.25||npc.horizontalCollision)npc.getJumpControl().jump();
  }
  static void follow(CompanionEntity npc,net.minecraft.world.level.pathfinder.Path path,double speed){
    if(path==null)return;
    if(npc.isInWater())npc.setSprinting(flowing(npc));
    double pace=npc.isInWater()&&flowing(npc)?2.2:speed;
    // Re-issuing the same path every tick resets vanilla stuck detection, so only start it once.
    if(npc.getNavigation().getPath()!=path)npc.getNavigation().moveTo(path,pace);else npc.getNavigation().setSpeedModifier(pace);
    if(!path.isDone()){
      var p=path.getNextNodePos();var point=NpcWalkSurface.point(npc,p);
      if(npc.isInWater()||swimmable(npc,p))npc.getMoveControl().setWantedPosition(point.x,point.y,point.z,flowing(npc)?2.2:Math.max(1.2,speed));
    }
  }
  private NpcWaterTravel(){}
}

package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;
import com.google.gson.JsonObject;
import dev.avatarcraft.npc.CompanionEntity;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.phys.*;

/** Highest-priority local breathing recovery; does not use a model, teleport or replenish air. */
public final class NpcWaterSafety {
  private final CompanionEntity npc;
  private boolean active;private BlockPos start;private BlockPos goal;
  private final ArrayDeque<BlockPos> frontier=new ArrayDeque<>();
  private final Map<Long,Long> parents=new HashMap<>();
  private List<BlockPos> path=List.of();private int index,escapes;private String reason="";
  public NpcWaterSafety(CompanionEntity npc){this.npc=npc;}
  public boolean active(){return active;}
  private boolean breathing(){
    var eye=npc.getEyePosition();var p=BlockPos.containing(eye);if(!npc.level().hasChunkAt(p))return false;
    // Use the same test vanilla uses to drain air (eye height minus 0.111), so the emergency does
    // not end in the top sliver of a water block while air is still running out.
    return !npc.isEyeInFluid(net.minecraft.tags.FluidTags.WATER);
  }
  private boolean danger(){
    if(!npc.isInWater()||breathing())return false;
    var c=NpcWaterTravel.current(npc);
    // Under ice or a roof the air never comes back by waiting; leave for open air at once.
    return !c.known()||c.depth()>5||c.surface()-npc.getY()>5||npc.getAirSupply()<=120||!NpcWaterState.surfaceOpen(npc);
  }
  public void preTick(){
    if(!active&&danger()){
      active=true;reason=npc.getAirSupply()<=120?"LOW_AIR":"DEEP_OR_UNKNOWN_WATER";resetSearch();
      if(npc.level().getServer()!=null)NpcService.get(npc.level().getServer()).record(npc,"water_emergency_start",reason,null,observation());
    }
    if(active){npc.getNavigation().stop();npc.setSprinting(true);npc.getMoveControl().setWantedPosition(npc.getX(),npc.getY(),npc.getZ(),0);}
  }
  private void resetSearch(){
    start=npc.blockPosition();goal=null;path=List.of();index=0;frontier.clear();parents.clear();
    frontier.add(start);parents.put(start.asLong(),start.asLong());
  }
  private boolean clear(BlockPos p){
    double r=npc.getBbWidth()/2.;return NpcWaterTravel.bodyClear(npc,new AABB(p.getX()+.5-r+1e-4,p.getY()+.1,p.getZ()+.5-r+1e-4,
        p.getX()+.5+r-1e-4,p.getY()+.1+npc.getBbHeight(),p.getZ()+.5+r-1e-4));
  }
  private boolean air(BlockPos p){
    var head=BlockPos.containing(p.getX()+.5,p.getY()+.1+npc.getEyeHeight(),p.getZ()+.5);
    return npc.level().hasChunkAt(head)&&npc.level().getFluidState(head).isEmpty()&&clear(p);
  }
  private void search(){
    long deadline=System.nanoTime()+1_000_000;
    for(int n=0;n<256&&!frontier.isEmpty()&&parents.size()<8192;n++){
      if((n&15)==0&&System.nanoTime()>deadline)return;
      var p=frontier.removeFirst();
      if(air(p)){
        goal=p;var reverse=new ArrayList<BlockPos>();
        for(var at=p;;){reverse.add(at);long previous=parents.get(at.asLong());if(previous==at.asLong())break;at=BlockPos.of(previous);}
        Collections.reverse(reverse);path=List.copyOf(reverse);index=Math.min(1,path.size()-1);return;
      }
      for(var direction:new Direction[]{Direction.UP,Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST,Direction.DOWN}){
        var q=p.relative(direction);
        if(Math.abs(q.getX()-start.getX())>16||Math.abs(q.getZ()-start.getZ())>16||q.getY()<start.getY()-2||q.getY()>start.getY()+32
            ||parents.containsKey(q.asLong())||!clear(q))continue;
        parents.put(q.asLong(),p.asLong());frontier.addLast(q);
      }
    }
  }
  public boolean tick(){
    preTick();if(!active)return false;
    if(breathing()){
      active=false;escapes++;npc.getNavigation().stop();npc.setSprinting(false);npc.setSwimming(false);
      NpcService.get(npc.level().getServer()).record(npc,"water_emergency_end","Breathing air restored",null,observation());return false;
    }
    npc.setAiStatus("水中から空気のある場所へ緊急退避中");
    if(goal==null)search();
    Vec3 target;
    if(!path.isEmpty()){
      while(index<path.size()-1&&npc.distanceToSqr(Vec3.atBottomCenterOf(path.get(index)).add(0,.1,0))<.4)index++;
      var next=path.get(index);
      if(!clear(next)){resetSearch();return true;}
      target=Vec3.atBottomCenterOf(next).add(0,.1,0);
    }else{
      // Search is bounded. Ascend only into collision-free observed water/air while it resumes.
      var up=npc.blockPosition().above();target=clear(up)?new Vec3(npc.getX(),npc.getY()+1,npc.getZ()):npc.position();
    }
    npc.getMoveControl().setWantedPosition(target.x,target.y,target.z,2.2);NpcWaterTravel.propel(npc,target,true);return true;
  }
  public JsonObject observation(){return object("active",active,"reason",reason,"air",npc.getAirSupply(),"safe_depth",5,"escapes",escapes,
      "searchNodes",parents.size(),"airGoal",goal==null?new com.google.gson.JsonArray():NpcMission.position(goal));}
}

package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;
import com.google.gson.*;
import dev.avatarcraft.npc.CompanionEntity;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;

/** Harvest only mature observed crops, pick up their actual drops, reserve seed and replant. */
final class NpcFarming implements AutoCloseable {
  record Plot(BlockPos pos,CropBlock crop,Item seed){}
  final CompletableFuture<JsonObject> result=new CompletableFuture<>();
  private final CompanionEntity npc;
  private final NpcSkills tools;
  private final List<Plot> plots=new ArrayList<>();
  private final long deadline;
  private CompletableFuture<JsonObject> pending;
  private int index,stage,waits;
  private long pickupUntil;
  private String operation="";
  private boolean closed,planting;
  NpcFarming(CompanionEntity npc,BlockPos center,int radius,int depth,Set<String> chain){
    this.npc=npc;tools=new NpcSkills(npc,(a,b)->{},depth+1,chain);tools.continuousWork(true);
    deadline=npc.level().getGameTime()+12000;
    for(var p:BlockPos.betweenClosed(center.offset(-radius,-4,-radius),center.offset(radius,4,radius))){
      if(!npc.level().hasChunkAt(p))continue;
      var s=npc.level().getBlockState(p);
      if(s.getBlock() instanceof CropBlock crop&&crop.isMaxAge(s)){
        Item seed=crop==Blocks.WHEAT?Items.WHEAT_SEEDS:crop==Blocks.BEETROOTS?Items.BEETROOT_SEEDS:
            crop==Blocks.CARROTS?Items.CARROT:crop==Blocks.POTATOES?Items.POTATO:null;
        if(seed!=null&&plots.size()<256)plots.add(new Plot(p.immutable(),crop,seed));
      }
    }
    plots.sort(Comparator.comparingDouble(p->npc.distanceToSqr(Vec3.atCenterOf(p.pos))));
    if(plots.isEmpty())result.complete(object("ok",false,"error","NO_MATURE_CROPS: no mature supported crop in the observed farm"));
  }
  void tick(){
    if(closed||result.isDone())return;
    tools.tick();
    if(npc.level().getGameTime()>deadline){fail("FARM_TIMEOUT: unfinished crops retained");return;}
    if(pending!=null){
      if(!pending.isDone())return;
      var r=pending.join();pending=null;
      if(!r.get("ok").getAsBoolean()){fail("FARM_"+operation.toUpperCase(Locale.ROOT)+": "+r);return;}
      if(operation.equals("dig"))stage=1;
      else if(operation.equals("pickup")){stage=3;pickupUntil=npc.level().getGameTime()+12;}
      else if(operation.equals("plant")){index++;stage=2;waits=0;}
      return;
    }
    if(index>=plots.size()){
      if(!planting){planting=true;index=0;stage=2;waits=0;return;}
      for(var p:plots){
        var s=npc.level().getBlockState(p.pos);
        if(s.getBlock()!=p.crop||p.crop.isMaxAge(s)){fail("FARM_UNFINISHED: verify planted crop at "+p.pos);return;}
      }
      result.complete(object("ok",true,"harvested",plots.size(),"replanted",plots.size()));return;
    }
    var plot=plots.get(index);var p=plot.pos;var state=npc.level().getBlockState(p);
    if(stage==3){if(npc.level().getGameTime()<pickupUntil)return;index++;stage=0;return;}
    if(stage==0){
      if(state.getBlock()!=plot.crop||!plot.crop.isMaxAge(state)){fail("FARM_CHANGED: inspect changed crop before retrying");return;}
      start("dig",xyz(p),"dig");return;
    }
    if(stage==1){
      // Walking to the harvested cell uses the real pickup system; no drop spawning or remote collection.
      start("move_precisely",xyz(p),"pickup");return;
    }
    if(stage==2){
      int slot=-1;for(int i=0;i<42;i++)if(i<36||i==41)
        if(npc.inventory.getItem(i).is(plot.seed)){slot=i;break;}
      if(slot<0){if(++waits<40)return;fail("FARM_SEED_SHORTFALL: "+BuiltInRegistries.ITEM.getKey(plot.seed));return;}
      if(!npc.level().getBlockState(p.below()).is(Blocks.FARMLAND)){
        fail("FARM_SOIL_CHANGED: farmland was altered; prepare a hoe before resuming "+p);return;
      }
      if(!state.isAir()){fail("FARM_OCCUPIED: planting cell changed");return;}
      var args=xyz(p.below());args.addProperty("slot",slot);args.addProperty("face","up");
      start("use_on_block",args,"plant");
    }
  }
  private JsonObject xyz(BlockPos p){return object("x",p.getX(),"y",p.getY(),"z",p.getZ());}
  private void start(String tool,JsonObject args,String op){operation=op;pending=tools.execute(tool,args).toCompletableFuture();}
  private void fail(String error){result.complete(object("ok",false,"error",error,"harvested",index+(stage>0?1:0),"replanted",index));}
  public void close(){if(closed)return;closed=true;tools.cancel("Farming stopped");if(!result.isDone())fail("Farming stopped");}
}

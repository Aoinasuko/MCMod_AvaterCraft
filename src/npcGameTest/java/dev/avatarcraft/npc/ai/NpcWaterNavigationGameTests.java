package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;
import dev.avatarcraft.npc.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import com.google.gson.JsonObject;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

final class NpcWaterNavigationGameTests {
  static void register(RegisterGameTestsEvent event){
    for(String name:List.of("water_depth5_land_swim_land","water_depth6_not_planned","water_deep_escape_without_ai","water_roof_escape_without_ai","water_low_air_escape_without_ai","water_sprint_against_current")){
      var env=event.registerEnvironment(bep_3davater_companion.id(name+"_qa"),new TestEnvironmentDefinition.AllOf());
      event.registerTest(bep_3davater_companion.id(name),new FunctionGameTestInstance(BuiltinTestFunctions.ALWAYS_PASS,
          new TestData<>(env,bep_3davater_companion.id("qa_empty"),1600,1,true)){
        public void run(GameTestHelper h){
          if(name.contains("current"))current(h);else if(name.contains("without_ai"))escape(h,name.contains("roof"),name.contains("low_air"));else crossing(h,name.contains("depth6"));
        }
      });
    }
  }
  static BlockPos p(GameTestHelper h,int x,int y,int z){return h.absolutePos(new BlockPos(x,y,z));}
  static void pool(GameTestHelper h,int depth){
    for(int x=0;x<=20;x++)for(int z=0;z<=12;z++){
      h.setBlock(x,0,z,Blocks.STONE);
      for(int y=1;y<=depth;y++)h.setBlock(x,y,z,x==0||x==20||z==0||z==12?Blocks.STONE:Blocks.WATER);
      for(int y=depth+1;y<=depth+4;y++)h.setBlock(x,y,z,Blocks.AIR);
    }
    for(int x=2;x<=5;x++)for(int z=4;z<=8;z++)h.setBlock(x,depth,z,Blocks.OAK_PLANKS);
    for(int x=14;x<=18;x++)for(int z=4;z<=8;z++)h.setBlock(x,depth,z,Blocks.OAK_PLANKS);
  }
  static dev.avatarcraft.npc.CompanionEntity npc(GameTestHelper h,int x,int y,int z){
    var n=NpcNeoRecoveryGameTests.npc(h,x,y,z);
    h.getLevel().getServer().getPlayerList().getPlayer(n.ownerId()).setPos(Vec3.atBottomCenterOf(p(h,2,y,4)));return n;
  }
  static void crossing(GameTestHelper h,boolean deep){
    int depth=deep?6:5;pool(h,depth);var n=npc(h,4,depth+1,6);var target=p(h,16,depth+1,6);
    var water=p(h,10,depth+1,6);h.assertTrue(NpcWaterTravel.swimmable(n,water)!=deep,"Depth5 accepted;6 excluded");
    var skills=new NpcSkills(n,(a,b)->{});var future=skills.execute("move_to",object("x",target.getX(),"y",target.getY(),"z",target.getZ())).toCompletableFuture();
    boolean[] entered={false};h.onEachTick(()->{entered[0]|=n.isInWater();skills.tick();if(!future.isDone())return;
      if(deep)h.assertTrue(!future.join().get("ok").getAsBoolean()&&!entered[0],"No planned entry to deep water");
      else{h.assertTrue(future.join().get("ok").getAsBoolean(),"Cross shallow water: "+future.join()+" pos="+n.position());
        h.assertTrue(entered[0]&&n.position().distanceToSqr(Vec3.atBottomCenterOf(target))<1,"Physical dry-to-water-to-dry crossing");}
      n.discard();h.succeed();});
  }
  static void escape(GameTestHelper h,boolean roof,boolean lowAir){
    int depth=lowAir?5:8;pool(h,depth);
    if(roof)for(int x=8;x<=12;x++)for(int z=4;z<=8;z++)h.setBlock(x,depth+1,z,Blocks.STONE);
    var n=npc(h,10,1,6);if(lowAir)n.setAirSupply(100);
    int[] during={0};
    NpcBrain brain=new NpcBrain(){
      public CompletableFuture<Void> initialize(){return CompletableFuture.completedFuture(null);}
      public boolean connected(){return true;}public String status(){return "Local water safety test";}public void close(){}
      public CompletableFuture<? extends Session> open(Personality personality,BiFunction<String,JsonObject,CompletionStage<JsonObject>> tools){
        if(n.waterSafety.active())during[0]++;return CompletableFuture.failedFuture(new IllegalStateException("No inference permitted in rescue test"));
      }
    };
    n.gameTestMind(new NpcMind(n,NpcService.get(h.getLevel().getServer()),brain));n.setInstruction("Keep the original task while returning to air");
    boolean[] activated={false};h.onEachTick(()->{
      activated[0]|=n.waterSafety.active();h.assertTrue(n.isAlive(),"Rescue before drowning");h.assertTrue(during[0]==0,"No model call during emergency");
      if(!activated[0]||n.waterSafety.active())return;
      var eye=BlockPos.containing(n.getEyePosition());var fluid=n.level().getFluidState(eye);
      h.assertTrue(fluid.isEmpty()||n.getEyePosition().y>=eye.getY()+fluid.getHeight(n.level(),eye),"Eyes physically reach breathing air above fluid surface");
      h.assertTrue(n.waterSafety.observation().get("escapes").getAsInt()>0&&!n.instruction().isBlank(),"Emergency preserves original instruction");
      if(roof)h.assertTrue(n.getX()<p(h,8,0,0).getX()||n.getX()>=p(h,13,0,0).getX()||n.getZ()<p(h,0,0,4).getZ()||n.getZ()>=p(h,0,0,9).getZ(),"Detoured around solid ceiling");
      n.discard();h.succeed();
    });
  }
  static void current(GameTestHelper h){
    for(int x=0;x<=16;x++)for(int z=2;z<=6;z++)for(int y=0;y<=5;y++)h.setBlock(x,y,z,Blocks.AIR);
    for(int x=1;x<=15;x++){
      h.setBlock(x,0,4,Blocks.STONE);
      for(int y=1;y<=4;y++){h.setBlock(x,y,3,Blocks.STONE);h.setBlock(x,y,5,Blocks.STONE);}
      if(x>=3&&x<=14){h.setBlock(x,1,4,Blocks.WATER);h.setBlock(x,2,4,Blocks.WATER);}
      else for(int y=1;y<=3;y++)h.setBlock(x,y,4,Blocks.STONE);
    }
    for(int x=3;x<=10;x++)h.setBlock(new BlockPos(x,3,4),Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL,x-3));
    var n=npc(h,10,3,4);var target=p(h,2,4,4);var skills=new NpcSkills(n,(a,b)->{});
    var future=skills.execute("move_to",object("x",target.getX(),"y",target.getY(),"z",target.getZ())).toCompletableFuture();
    boolean[] sprint={false},flow={false};h.onEachTick(()->{
      skills.tick();if(n.isInWater()&&NpcWaterTravel.flowing(n)){flow[0]=true;sprint[0]|=n.isSprinting();}
      if(!future.isDone())return;
      h.assertTrue(future.join().get("ok").getAsBoolean(),"Upstream physical movement: "+future.join()+" pos="+n.position());
      h.assertTrue(flow[0]&&sprint[0],"Used sprint in real flowing water");
      h.assertTrue(n.position().distanceToSqr(Vec3.atBottomCenterOf(target))<1,"Reached upstream bank");n.discard();h.succeed();
    });
  }
}

package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;
import dev.avatarcraft.npc.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

final class NpcMissionGameTests {
  static void register(RegisterGameTestsEvent event){
    for(String name:List.of("farm_real_harvest_replant_eight","mission_rejects_partial_farm","mission_shared_verified_kills","mission_original_deposit_count_blocks_false_completion","swimming_exits_observed_bank")){
      var env=event.registerEnvironment(bep_3davater_companion.id(name+"_qa"),new TestEnvironmentDefinition.AllOf());
      event.registerTest(bep_3davater_companion.id(name),new FunctionGameTestInstance(BuiltinTestFunctions.ALWAYS_PASS,
          new TestData<>(env,bep_3davater_companion.id("qa_empty"),3000,1,true)){
        public void run(GameTestHelper h){
          for(int x=0;x<16;x++)for(int z=0;z<16;z++){h.setBlock(x,0,z,Blocks.STONE);h.setBlock(x,-1,z,Blocks.STONE);}
          if(name.startsWith("farm_"))farm(h);else if(name.contains("partial"))partial(h);
          else if(name.contains("kills"))kills(h);else if(name.contains("deposit"))deposit(h);else swim(h);
        }
      });
    }
  }
  static BlockPos p(GameTestHelper h,int x,int y,int z){return h.absolutePos(new BlockPos(x,y,z));}
  static List<BlockPos> field(GameTestHelper h){
    var plots=new ArrayList<BlockPos>();
    for(int x=5;x<=7;x++)for(int z=5;z<=7;z++){
      if(x==6&&z==6){h.setBlock(x,0,z,Blocks.WATER);continue;}
      h.setBlock(new BlockPos(x,0,z),Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));
      h.setBlock(new BlockPos(x,1,z),((CropBlock)Blocks.WHEAT).getStateForAge(7));plots.add(p(h,x,1,z));
    }return plots;
  }
  static void farm(GameTestHelper h){
    var plots=field(h);var n=NpcNeoRecoveryGameTests.npc(h,3,1,4);var skills=new NpcSkills(n,(a,b)->{});
    var center=p(h,6,1,6);var future=skills.execute("harvest_replant",object("x",center.getX(),"y",center.getY(),"z",center.getZ(),"radius",2)).toCompletableFuture();
    h.onEachTick(()->{skills.tick();if(!future.isDone())return;
      h.assertTrue(future.join().get("ok").getAsBoolean(),"Real farming succeeds without supplied seed: "+future.join());
      h.assertTrue(NpcNeoRecoveryGameTests.count(n,Items.WHEAT)==8,"All eight actual wheat drops picked up");
      h.assertTrue(plots.stream().allMatch(q->h.getLevel().getBlockState(q).is(Blocks.WHEAT)
          &&h.getLevel().getBlockState(q.below()).is(Blocks.FARMLAND)),"Eight replanted crops and soil intact");n.discard();h.succeed();
    });
  }
  static void partial(GameTestHelper h){
    var plots=field(h);var n=NpcNeoRecoveryGameTests.npc(h,3,1,4);n.setInstruction("Harvest and replant all crops");
    for(int i=0;i<7;i++)h.getLevel().setBlockAndUpdate(plots.get(i),Blocks.WHEAT.defaultBlockState());
    n.mission.adopt(object("type","farm","target","","count",1,"location","","position",NpcMission.position(p(h,6,1,6)),"radius",2));
    h.assertTrue(n.mission.observation().get("required").getAsInt()==8,"Planning latency must not shrink the original field snapshot");
    h.assertTrue(!n.mission.verified(),"Seven of eight is incomplete");
    h.getLevel().setBlockAndUpdate(plots.get(7),Blocks.WHEAT.defaultBlockState());
    h.assertTrue(!n.mission.verified(),"Planted cells alone do not prove collecting harvest");
    n.inventory.insert(new ItemStack(Items.WHEAT,8));h.assertTrue(n.mission.verified(),"World and inventory jointly verify goal");
    String state=n.mission.save();n.mission.restore(state);h.assertTrue(n.mission.verified(),"Verified goal survives serialization");n.discard();h.succeed();
  }
  static void kills(GameTestHelper h){
    h.getLevel().getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);h.setTime(18000);
    var owner=h.makeMockServerPlayerInLevel();owner.setPos(Vec3.atBottomCenterOf(p(h,1,1,1)));
    var leader=NpcNeoRecoveryGameTests.npc(h,owner,2,1,2);var worker=NpcNeoRecoveryGameTests.npc(h,owner,3,1,2);
    leader.setInstruction("Defeat two skeletons together");
    leader.mission.adopt(object("type","defeat","target","minecraft:skeleton","count",2,"location","","position",new com.google.gson.JsonArray(),"radius",16));
    var job=new NpcWorkData.Job();job.id=UUID.randomUUID();job.leader=leader.getUUID();job.worker=worker.getUUID();
    job.owner=owner.getUUID();job.parentInstruction=leader.instruction();job.item="";job.count=1;NpcWorkData.get(h.getLevel()).jobs.add(job);
    for(var actor:List.of(leader,worker)){
      var mob=EntityTypes.SKELETON.create(h.getLevel(),EntitySpawnReason.COMMAND);mob.setNoAi(true);mob.setPos(Vec3.atBottomCenterOf(p(h,6,1,6)));
      h.getLevel().addFreshEntity(mob);NpcCombat.hit(actor,mob,actor,100);mob.discard();
    }
    h.assertTrue(leader.mission.verified(),"Shared unique deaths remain verified after entities disappear");
    h.assertTrue(leader.mission.observation().get("actual").getAsInt()==2,"Exactly two, not repeated observations");
    leader.discard();worker.discard();h.succeed();
  }
  static void swim(GameTestHelper h){
    for(int x=1;x<=7;x++)for(int z=1;z<=7;z++)h.setBlock(x,0,z,Blocks.WATER);
    var n=NpcNeoRecoveryGameTests.npc(h,4,1,4);var skills=new NpcSkills(n,(a,b)->{});var target=p(h,10,1,4);
    h.getLevel().getServer().getPlayerList().getPlayer(n.ownerId()).setPos(Vec3.atBottomCenterOf(p(h,0,1,0)));
    var future=skills.execute("move_to",object("x",target.getX(),"y",target.getY(),"z",target.getZ())).toCompletableFuture();
    h.onEachTick(()->{skills.tick();if(!future.isDone())return;
      h.assertTrue(future.join().get("ok").getAsBoolean(),"Observed swimming exit: "+future.join()+" position="+n.position()+" target="+target+" water="+n.isInWater());
      h.assertTrue(n.position().distanceToSqr(Vec3.atBottomCenterOf(target))<1,"Physically reached the bank");n.discard();h.succeed();});
  }
  static void deposit(GameTestHelper h){
    var n=NpcNeoRecoveryGameTests.npc(h,3,1,3);h.setBlock(6,1,3,Blocks.CHEST);
    var chest=(net.minecraft.world.Container)h.getLevel().getBlockEntity(p(h,6,1,3));
    n.setInstruction("Collect and deposit 64 logs");
    var goal=object("type","deposit","target","#minecraft:logs","count",64,"location","",
        "position",NpcMission.position(p(h,6,1,3)),"radius",8);
    n.mind().acceptDelegatedPlan(new TaskPlan(1,"short phase cannot complete original goal","",List.of(
        new TaskPlan.Step("wait","","",1,-1,-1,"main",1,List.of())),goal));
    chest.setItem(0,new ItemStack(Items.OAK_LOG,63));
    var reduced=goal.deepCopy();reduced.addProperty("count",1);n.mission.adopt(reduced);
    h.runAfterDelay(80,()->{
      h.assertTrue(!n.instruction().isBlank()&&!n.executionMemory.hasCompletedGoal(),"Short plan must not clear instruction before 64 actual logs");
      h.assertTrue(n.mission.observation().get("required").getAsInt()==64&&!n.mission.verified(),"Replanning cannot lower original count");
      chest.setItem(0,new ItemStack(Items.OAK_LOG,64));h.assertTrue(n.mission.verified(),"Actual chest count finally satisfies goal");n.discard();h.succeed();
    });
  }
}

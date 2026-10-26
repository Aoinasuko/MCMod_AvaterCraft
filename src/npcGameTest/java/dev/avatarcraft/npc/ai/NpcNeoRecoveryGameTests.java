package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.*;
import dev.avatarcraft.npc.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

final class NpcNeoRecoveryGameTests {
  static void register(RegisterGameTestsEvent event) {
    for (String name : List.of("failed_ore_keeps_lower_alternative", "known_furnace_reused_after_travel", "waiting_leader_delivers_missing_iron", "recorded_drop_return_repairs_real_steps")) {
      var env = event.registerEnvironment(bep_3davater_companion.id("neo_qa_"+name), new TestEnvironmentDefinition.AllOf());
      event.registerTest(bep_3davater_companion.id(name), new FunctionGameTestInstance(
          BuiltinTestFunctions.ALWAYS_PASS, new TestData<>(env, bep_3davater_companion.id("qa_empty"), 4000, 1, true)) {
        public void run(GameTestHelper h) {
          if (name.startsWith("failed")) ore(h);
          else if (name.startsWith("known")) station(h);
          else if(name.startsWith("waiting")) assistance(h);
          else returning(h);
        }
      });
    }
  }

  static CompanionEntity npc(GameTestHelper h, int x, int y, int z) {
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(x+2,y,z))));
    return npc(h, owner, x,y,z);
  }

  static CompanionEntity npc(GameTestHelper h, net.minecraft.server.level.ServerPlayer owner, int x, int y, int z) {
    var n = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    n.initialize(owner,"tortobu"); n.setFollowsOwner(false);
    n.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(x,y,z))));
    h.getLevel().addFreshEntity(n); new NpcChunkLoading().update(n); return n;
  }

  static int count(CompanionEntity n, Item item) {
    int count=0; for(int i=0;i<42;i++) if(n.inventory.getItem(i).is(item)) count+=n.inventory.getItem(i).getCount();
    return count;
  }

  static void ore(GameTestHelper h) {
    for(int x=0;x<20;x++) for(int z=0;z<20;z++) for(int y=0;y<8;y++) h.setBlock(x,y,z,Blocks.STONE);
    var n=npc(h,8,8,8); n.inventory.setItem(0,new ItemStack(Items.STONE_PICKAXE));
    var low=h.absolutePos(new BlockPos(10,5,8));
    var high=h.absolutePos(new BlockPos(15,11,8));
    h.getLevel().setBlockAndUpdate(low,Blocks.IRON_ORE.defaultBlockState());
    h.getLevel().setBlockAndUpdate(high,Blocks.IRON_ORE.defaultBlockState());
    var args=object("x",high.getX(),"y",high.getY(),"z",high.getZ(),"goal","mine","mode","both","max_blocks",96);
    n.executionMemory.failed(h.getLevel().dimension().identifier().toString(),"work_route",args,"No permitted work route",h.getLevel().getGameTime());
    var targets=new JsonArray();
    targets.add(new Gson().toJsonTree(List.of(high.getX()+.5,high.getY()+.5,high.getZ()+.5)));
    targets.add(new Gson().toJsonTree(List.of(low.getX()+.5,low.getY()+.5,low.getZ()+.5)));
    var skills=new NpcSkills(n,(a,b)->{}); var world=new NpcLocalWorld(n,skills);
    var step=NpcSurvival.step("collect","minecraft:raw_iron","minecraft:iron_ore",1);
    var options=world.exploration(step,Set.of(),0,targets);
    h.assertTrue(options.stream().anyMatch(o -> o.args().get("y").getAsInt()==low.getY()),"Lower ore remains a work target");
    h.assertTrue(options.stream().noneMatch(o -> o.args().equals(args)),"Rejected ore is skipped before returning candidates");
    var lower=options.stream().filter(o->o.args().get("y").getAsInt()==low.getY()).findFirst().orElseThrow();
    var cx=world.candidateContext(lower.tool(),lower.args());
    h.assertTrue(cx.get("height_delta").getAsDouble()<0 && cx.get("distance").getAsDouble()>0,"Real geometry reaches the ranker");
    n.discard(); h.succeed();
  }

  static void station(GameTestHelper h) {
    for(int x=0;x<40;x++) for(int z=0;z<12;z++) {
      h.setBlock(x,0,z,Blocks.STONE);
      for(int y=1;y<=10;y++) h.setBlock(x,y,z,Blocks.AIR);
    }
    h.setBlock(4,1,4,Blocks.FURNACE);
    var n=npc(h,30,1,4);
    n.inventory.setItem(0,new ItemStack(Items.RAW_IRON,3));
    n.inventory.setItem(1,new ItemStack(Items.COAL));
    NpcTeams.rememberStation(n,h.absolutePos(new BlockPos(4,1,4)));
    var work=new NpcSurvival(n,"minecraft:iron_ingot",3,1);
    h.onEachTick(()->{
      work.tick(); if(!work.result.isDone())return;
      h.assertTrue(work.result.join().get("ok").getAsBoolean(),"Known furnace reused: "+work.result.join());
      h.assertTrue(count(n,Items.IRON_INGOT)==3,"Only actual smelted output completes acquisition");
      int furnaces=0;for(int x=0;x<40;x++)for(int z=0;z<12;z++)if(h.getBlockState(new BlockPos(x,1,z)).is(Blocks.FURNACE))furnaces++;
      h.assertTrue(furnaces==1,"No replacement furnace was built");work.close();n.discard();h.succeed();
    });
  }

  static void assistance(GameTestHelper h) {
    for(int x=0;x<12;x++)for(int z=0;z<12;z++)h.setBlock(x,0,z,Blocks.STONE);
    var leader=npc(h,4,1,4);var owner=h.getLevel().getServer().getPlayerList().getPlayer(leader.ownerId());
    var worker=npc(h,owner,6,1,4);leader.setInstruction("協力してダイヤ10個を収納");
    var delegated=NpcTeams.delegate(leader,worker.getId(),"minecraft:iron_pickaxe",1);
    h.assertTrue(delegated.get("ok").getAsBoolean(),"Real worker lease");worker.mind().stop();
    var job=NpcTeams.job(worker);job.neededItem="minecraft:raw_iron";job.neededCount=3;job.availableCount=1;job.lastProgressTick=h.getLevel().getGameTime()-601;
    leader.inventory.setItem(0,new ItemStack(Items.RAW_IRON,2));worker.inventory.setItem(0,new ItemStack(Items.RAW_IRON));
    var wait=new TaskPlan.Step("tool","bep_3davater_companion:await_teammate","",1,-1,-1,"main",10,List.of(),"",object("entity_id",worker.getId()).toString());
    var mission=new TaskPlan(1,"Original diamond mission","",List.of(wait,NpcSurvival.step("collect","minecraft:diamond","minecraft:diamond_ore",10)));
    var support=NpcTeams.assistance(leader,mission);
    h.assertTrue(support!=null && support.steps().size()==4 && support.steps().getLast().count()==10,"Support preserves the original diamond quantity");
    var skills=new NpcSkills(leader,(a,b)->{});
    var runner=new LocalTaskRunner(new TaskPlan(1,"Deliver missing iron","",support.steps().subList(0,2)),new NpcLocalWorld(leader,skills),leader.personality().allowedSkills);
    h.onEachTick(()->{
      skills.tick();runner.tick(h.getLevel().getGameTime());
      h.assertTrue(!runner.needsReplan(),"Support delivery: "+runner.problem());if(!runner.complete())return;
      h.assertTrue(count(worker,Items.RAW_IRON)==3 && count(leader,Items.RAW_IRON)==0,"The two real items were delivered");
      h.assertTrue(NpcTeams.job(worker)==job,"Worker retains its original assignment");
      runner.close();leader.discard();worker.discard();h.succeed();
    });
  }

  static void returning(GameTestHelper h) {
    for(int x=0;x<24;x++)for(int z=0;z<9;z++) {
      int top=x<=3?17:Math.max(0,19-x);
      for(int y=0;y<=22;y++)h.setBlock(x,y,z,y<=top?Blocks.STONE:Blocks.AIR);
    }
    var n=npc(h,19,1,4);n.inventory.setItem(0,new ItemStack(Items.DIRT,16));
    n.inventory.setItem(1,new ItemStack(Items.WOODEN_PICKAXE));
    var dim=h.getLevel().dimension().identifier().toString();
    var top=h.absolutePos(new BlockPos(3,18,4));
    n.executionMemory.walked(dim,top.getX(),top.getY(),top.getZ());
    for(int x=4;x<=19;x++) {
      var p=h.absolutePos(new BlockPos(x,20-x,4));
      n.executionMemory.walked(dim,p.getX(),p.getY(),p.getZ());
    }
    var skills=new NpcSkills(n,(a,b)->{});
    var pending=skills.execute("move_to",object("x",top.getX(),"y",top.getY(),"z",top.getZ())).toCompletableFuture();
    Vec3[] previous={n.position()};
    h.onEachTick(()->{
      skills.tick();
      h.assertTrue(previous[0].distanceToSqr(n.position())<4,"A return checkpoint cannot teleport the NPC");
      previous[0]=n.position();if(!pending.isDone())return;
      h.assertTrue(pending.join().get("ok").getAsBoolean(),"Recorded return with a small drop: "+pending.join());
      h.assertTrue(n.getY()>=top.getY()-.5,"Actual return to the upper platform");
      h.assertTrue(count(n,Items.DIRT)<16 || !h.getBlockState(new BlockPos(3,17,4)).is(Blocks.STONE),
          "The repair must place real blocks or excavate a real step");
      skills.cancel("Test done");n.discard();h.succeed();
    });
  }
}

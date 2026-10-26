package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;
import dev.avatarcraft.npc.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

final class NpcSpatialGameTests {
  static void register(RegisterGameTestsEvent event){
    for(String name:List.of("terrain_sphere32_and_wall_connectivity","entity_sphere16_and_real_drop_details","chunk_radius3_and_planning_observation")){
      var env=event.registerEnvironment(bep_3davater_companion.id(name+"_qa"),new TestEnvironmentDefinition.AllOf());
      event.registerTest(bep_3davater_companion.id(name),new FunctionGameTestInstance(BuiltinTestFunctions.ALWAYS_PASS,
          new TestData<>(env,bep_3davater_companion.id("qa_empty"),600,1,true)){
        public void run(GameTestHelper h){if(name.startsWith("terrain"))terrain(h);else if(name.startsWith("entity"))entities(h);else chunks(h);}
      });
    }
  }
  static void terrain(GameTestHelper h){
    var center=h.absolutePos(new BlockPos(8,1,8));var level=h.getLevel();
    for(int x=-33;x<=33;x++)for(int z=-33;z<=33;z++){
      level.setBlockAndUpdate(center.offset(x,-1,z),Blocks.STONE.defaultBlockState());
      for(int y=0;y<4;y++)level.setBlockAndUpdate(center.offset(x,y,z),Blocks.AIR.defaultBlockState());
    }
    for(int z=-33;z<=33;z++)for(int y=0;y<4;y++)level.setBlockAndUpdate(center.offset(2,y,z),Blocks.STONE.defaultBlockState());
    for(int x:new int[]{10,32,33})level.setBlockAndUpdate(center.offset(x,0,0),Blocks.OAK_LOG.defaultBlockState());
    var n=NpcNeoRecoveryGameTests.npc(h,8,1,8);var tools=new NpcSkills(n,(a,b)->{});
    var result=tools.execute("observe_terrain",object()).toCompletableFuture();
    h.onEachTick(()->{if(!result.isDone())return;var r=result.join();
      h.assertTrue(r.get("ok").getAsBoolean(),"Terrain observation succeeds: "+r);
      h.assertTrue(r.get("radius").getAsInt()==32,"Exact radius32");
      h.assertTrue(r.get("unknown_cells").getAsInt()==0,"Fixture covers loaded sphere");
      h.assertTrue(r.getAsJsonArray("height_rows").size()==65,"Full horizontal projection");
      int logs=0;for(var e:r.getAsJsonArray("resources"))if(e.getAsJsonObject().get("block").getAsString().equals("minecraft:oak_log"))logs=e.getAsJsonObject().get("count").getAsInt();
      h.assertTrue(logs==2,"Includes occluded and exact32 boundary; excludes33: "+logs);
      for(var e:r.getAsJsonArray("waypoints"))h.assertTrue(NpcMission.block(e.getAsJsonObject().getAsJsonArray("position")).getX()<center.getX()+2,"Static wall separates walking graph");
      h.assertTrue(level.getBlockState(center.offset(2,0,0)).is(Blocks.STONE),"Observation never edits wall");
      h.assertTrue(NpcNeoRecoveryGameTests.count(n,Items.OAK_LOG)==0,"Observation does not collect resources");n.discard();h.succeed();
    });
  }
  static void entities(GameTestHelper h){
    for(int x=0;x<32;x++)for(int z=0;z<16;z++)h.setBlock(x,0,z,Blocks.STONE);
    var n=NpcNeoRecoveryGameTests.npc(h,4,1,4);var at=n.position();
    var drops=new ArrayList<ItemEntity>();
    for(int distance:new int[]{4,16,17}){
      var p=at.add(distance,0,0);var e=new ItemEntity(h.getLevel(),p.x,p.y,p.z,new ItemStack(Items.WHEAT,5));
      e.setPickUpDelay(1000);e.setNoGravity(true);e.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);h.getLevel().addFreshEntity(e);drops.add(e);
    }
    for(int y=1;y<=3;y++)h.setBlock(6,y,4,Blocks.STONE);
    var future=new NpcSkills(n,(a,b)->{}).execute("observe_entities",object()).toCompletableFuture();
    h.onEachTick(()->{if(!future.isDone())return;var r=future.join();h.assertTrue(r.get("ok").getAsBoolean(),"Entity tool succeeds");
      var ids=new HashSet<Integer>();boolean hidden=false;
      for(var e:r.getAsJsonArray("entities")){var row=e.getAsJsonObject();if(!row.has("item"))continue;
        ids.add(row.get("entity_id").getAsInt());h.assertTrue(row.get("count").getAsInt()==5&&row.get("pickup_delayed").getAsBoolean(),"Real item count and pickup delay");
        h.assertTrue(row.has("velocity")&&!row.get("collection_verified").getAsBoolean(),"Observation is not collection");hidden|=!row.get("visible").getAsBoolean();
      }
      h.assertTrue(ids.contains(drops.get(0).getId())&&ids.contains(drops.get(1).getId())&&!ids.contains(drops.get(2).getId()),"Exact 3D radius16 boundary");
      h.assertTrue(hidden,"Occluded drops are included with visibility flag");
      h.assertTrue(NpcNeoRecoveryGameTests.count(n,Items.WHEAT)==0,"Observation does not transfer drops");drops.forEach(ItemEntity::discard);n.discard();h.succeed();
    });
  }
  static void chunks(GameTestHelper h){
    var square=NpcChunkLoading.square(-2,3);h.assertTrue(square.size()==49,"Seven by seven chunk tickets");
    h.assertTrue(square.contains(ChunkPos.pack(-5,0))&&square.contains(ChunkPos.pack(1,6)),"Radius3 corners included");
    h.assertTrue(!square.contains(ChunkPos.pack(2,3)),"Radius4 excluded");
    var overlap=new HashSet<>(square);overlap.retainAll(NpcChunkLoading.square(-1,3));h.assertTrue(overlap.size()==42,"Moving one chunk retains overlap");
    for(int x=0;x<16;x++)for(int z=0;z<16;z++)h.setBlock(x,0,z,Blocks.STONE);
    var n=NpcNeoRecoveryGameTests.npc(h,4,1,4);n.setInstruction("Inspect the environment");
    var o=new NpcLocalWorld(n,new NpcSkills(n,(a,b)->{})).planningObservation();
    h.assertTrue(o.has("terrain_32")&&o.has("entities_16")&&o.has("mission_goal"),"Reserved planning context contains real spatial information");
    h.assertTrue(o.getAsJsonObject("terrain_32").get("radius").getAsInt()==32&&o.getAsJsonObject("entities_16").get("radius").getAsInt()==16,"Planning ranges match tools");
    n.discard();h.succeed();
  }
}

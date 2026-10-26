package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;
import com.google.gson.*;
import dev.avatarcraft.npc.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/** Controlled water study only: real planner, real tools, no scripted NPC route or item transfer. */
final class NpcWaterPoolGameTests {
  static final Map<String,String> COMMANDS=Map.of(
      "drops","近くの水中に浮かんでいるアイテムを回収して、ここのチェストにいれてほしい",
      "chest","周囲の水の上にあるチェストからアイテムを回収して、ここのチェストにいれてほしい",
      "combat","水の上にいるスケルトンを倒して");
  static void register(RegisterGameTestsEvent event){
    String name=System.getProperty("avatarcraft.waterCase","drops");
    if(!COMMANDS.containsKey(name))throw new IllegalArgumentException("Unknown water case");
    var env=event.registerEnvironment(bep_3davater_companion.id("water_pool_qa"),new TestEnvironmentDefinition.AllOf());
    event.registerTest(bep_3davater_companion.id("water_pool_"+name),new FunctionGameTestInstance(BuiltinTestFunctions.ALWAYS_PASS,
        new TestData<>(env,bep_3davater_companion.id("qa_empty"),8000,1,true)){
      public void run(GameTestHelper h){new Run(h,name).start();}
    });
  }
  private static final class Run {
    final GameTestHelper h;final ServerLevel level;final String name;
    final Path output;final JsonObject data=new JsonObject();final JsonArray frames=new JsonArray();
    final List<ItemEntity> drops=new ArrayList<>();
    final Consumer<LivingDeathEvent> deathListener=this::death;
    ServerPlayer owner;CompanionEntity npc;Skeleton skeleton;NpcComplexLiveBrain live;
    BlockPos destination,source;long began,tick;boolean ended,started,killedByNpc;String targetDeathCause="";
    Run(GameTestHelper h,String name){this.h=h;level=h.getLevel();this.name=name;
      output=Path.of(System.getProperty("avatarcraft.qaOutputRoot","../build")).resolve("npc-water-results").resolve(name);
    }
    BlockPos p(int x,int y,int z){return h.absolutePos(new BlockPos(x,y,z));}
    void set(int x,int y,int z,Block b){level.setBlock(p(x,y,z),b.defaultBlockState(),2);}
    void start(){
      try{
        Files.createDirectories(output);NeoForge.EVENT_BUS.addListener(LivingDeathEvent.class,deathListener);
        // Inclusive inner pool 1..24 x 1..16, four full source-water layers, sealed bottom/rim.
        for(int x=0;x<=25;x++)for(int z=0;z<=17;z++){
          set(x,0,z,Blocks.STONE);
          for(int y=1;y<=4;y++)set(x,y,z,x==0||x==25||z==0||z==17?Blocks.STONE:Blocks.WATER);
          for(int y=5;y<=9;y++)set(x,y,z,Blocks.AIR);
        }
        for(int x=3;x<=7;x++)for(int z=6;z<=10;z++)set(x,4,z,Blocks.OAK_PLANKS);
        for(int x=16;x<=20;x++)for(int z=6;z<=10;z++)set(x,4,z,Blocks.OAK_PLANKS);
        for(int x=11;x<=12;x++)for(int z=13;z<=14;z++)set(x,4,z,Blocks.OAK_PLANKS);
        destination=p(4,5,8);source=p(18,5,8);
        level.setBlock(destination,Blocks.CHEST.defaultBlockState(),2);level.setBlock(source,Blocks.CHEST.defaultBlockState(),2);
        owner=h.makeMockServerPlayerInLevel();owner.setPos(Vec3.atBottomCenterOf(p(4,5,6)));
        npc=bep_3davater_companion.COMPANION.get().create(level,EntitySpawnReason.COMMAND);npc.initialize(owner,"tortobu");
        npc.setCustomName(net.minecraft.network.chat.Component.literal("water_probe"));npc.setFollowsOwner(false);
        npc.setPos(Vec3.atBottomCenterOf(p(6,5,8)));npc.inventory.setItem(0,new ItemStack(Items.BREAD,16));level.addFreshEntity(npc);
        new NpcChunkLoading().update(npc);
        if(name.equals("chest"))((Container)level.getBlockEntity(source)).setItem(0,new ItemStack(Items.WHEAT,8));
        data.addProperty("case",name);data.addProperty("command",COMMANDS.get(name));data.addProperty("policyVersion",LocalActionPolicy.VERSION);
        data.addProperty("weightsSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
            LocalActionPolicy.class.getResourceAsStream("/data/bep_3davater_companion/ai/action-policy.bin").readAllBytes())));
        data.addProperty("initialNpcFood",16);data.addProperty("initialTools",0);data.addProperty("initialScaffoldingItems",0);
        data.addProperty("chunkRadius",NpcChunkLoading.RADIUS);data.addProperty("maxWallSeconds",240);data.addProperty("maxActualPlans",6);
        data.addProperty("clockPolicy","50ms wait each server tick; at most20TPS, no accelerated physics");
        data.add("layout",object("origin",NpcMission.position(p(0,0,0)),"poolInnerFrom",NpcMission.position(p(1,1,1)),
            "poolInnerTo",NpcMission.position(p(24,4,16)),"depth",4,"surfaceFeetY",p(0,5,0).getY(),
            "destination",NpcMission.position(destination),"source",NpcMission.position(source),"npcStart",NpcMission.position(p(6,5,8)),
            "nearPlatform",new Gson().toJsonTree(List.of(3,7,6,10)),"farPlatform",new Gson().toJsonTree(List.of(16,20,6,10)),"smallPlatform",new Gson().toJsonTree(List.of(11,12,13,14))));
        var hashes=new JsonObject();
        for(var type:List.of(CompanionEntity.class,NpcChunkLoading.class,NpcMind.class,NpcSkills.class,NpcMission.class,NpcSpatialObservation.class,
            TaskPlan.class,NpcTools.class,NpcWalkingRoute.class,NpcWaterPoolGameTests.class,Run.class))
          try(var bytes=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")){
            hashes.addProperty(type.getName(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes())));
          }
        data.add("runtimeClassSha256",hashes);
        h.onEachTick(this::tick);h.runAfterDelay(20,this::begin);
      }catch(Exception e){finish("setup_failed",e.toString());}
    }
    void begin(){
      try{
        if(name.equals("drops"))for(var at:List.of(p(11,4,8),p(12,4,10))){
          var e=new ItemEntity(level,at.getX()+.5,at.getY()+.2,at.getZ()+.5,new ItemStack(Items.WHEAT,4));
          e.setDeltaMovement(Vec3.ZERO);e.setPickUpDelay(10);level.addFreshEntity(e);drops.add(e);
        }
        if(name.equals("combat")){
          h.setTime(18000);level.getServer().setDifficulty(Difficulty.NORMAL,true);
          npc.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.DIAMOND_HELMET));npc.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.DIAMOND_CHESTPLATE));
          npc.setItemSlot(EquipmentSlot.LEGS,new ItemStack(Items.DIAMOND_LEGGINGS));npc.setItemSlot(EquipmentSlot.FEET,new ItemStack(Items.DIAMOND_BOOTS));
          npc.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SWORD));
          skeleton=EntityTypes.SKELETON.create(level,EntitySpawnReason.COMMAND);skeleton.setPersistenceRequired();
          skeleton.setPos(Vec3.atBottomCenterOf(p(17,5,8)));skeleton.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.BOW));level.addFreshEntity(skeleton);
          data.addProperty("nightStart",18000);data.addProperty("difficulty","NORMAL");data.addProperty("combatEquipment","full diamond armor and diamond sword");
        }
        live=new NpcComplexLiveBrain("water-pool-"+name,()->"",6);npc.gameTestMind(new NpcMind(npc,NpcService.get(level.getServer()),live));
        NpcLocationGameTests.aim(owner,destination);npc.setInstruction(COMMANDS.get(name),NpcCommandContext.capture(npc,owner));
        began=System.nanoTime();tick=level.getGameTime();started=true;
        data.add("initialObservation",new NpcLocalWorld(npc,new NpcSkills(npc,(a,b)->{})).planningObservation());
        NpcService.get(level.getServer()).record(npc,"water_pool_mission",COMMANDS.get(name),null,data.getAsJsonObject("layout"));save("running","");
      }catch(Exception e){finish("setup_failed",e.toString());}
    }
    void death(LivingDeathEvent e){if(e.getEntity()==skeleton){killedByNpc=e.getSource().getEntity()==npc;targetDeathCause=e.getSource().getMsgId();}}
    int stored(BlockPos chest){int count=0;if(chest!=null&&level.getBlockEntity(chest) instanceof Container c)for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(Items.WHEAT))count+=c.getItem(i).getCount();return count;}
    int carried(){return npc==null?0:NpcNeoRecoveryGameTests.count(npc,Items.WHEAT);}
    JsonObject frame(){
      var entities=new JsonArray();for(var e:level.getEntitiesOfClass(ItemEntity.class,new net.minecraft.world.phys.AABB(p(0,0,0)).inflate(32),e->e.isAlive()&&e.getItem().is(Items.WHEAT)))
        entities.add(object("id",e.getId(),"position",NpcSpatialObservation.vector(e.position()),"count",e.getItem().getCount(),"water",e.isInWater(),"pickupDelayed",e.hasPickUpDelay()));
      var row=object("tick",level.getGameTime()-tick,"seconds",began==0?0:(System.nanoTime()-began)/1e9,
          "npcPosition",NpcSpatialObservation.vector(npc.position()),"health",npc.getHealth(),"air",npc.getAirSupply(),"water",npc.isInWater(),
          "ground",npc.onGround(),"status",npc.aiStatus(),"instruction",npc.instruction(),"carriedWheat",carried(),"destinationWheat",stored(destination),"sourceWheat",stored(source),"drops",entities);
      if(skeleton!=null)row.add("skeleton",object("position",NpcSpatialObservation.vector(skeleton.position()),"health",skeleton.getHealth(),"alive",skeleton.isAlive(),
          "water",skeleton.isInWater(),"killedByNpc",killedByNpc,"deathCause",targetDeathCause));return row;
    }
    void tick(){
      if(ended)return;
      // This is intentionally real-paced even when no external call is pending.
      java.util.concurrent.locks.LockSupport.parkNanos(50_000_000L);
      if(!started)return;
      if((level.getGameTime()-tick)%10==0){var f=frame();frames.add(f);try{Files.writeString(output.resolve("trajectory.jsonl"),f+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception e){finish("infrastructure_failed",e.toString());return;}}
      if(!npc.isAlive()){finish("failed","NPC_DIED");return;}
      boolean objective=name.equals("combat")?skeleton!=null&&!skeleton.isAlive()&&killedByNpc:
          stored(destination)==8&&stored(source)==0&&carried()==0;
      if(npc.instruction().isBlank()){finish(objective?"passed":"false_completion",objective?"":"INSTRUCTION_CLEARED_WITHOUT_VERIFIED_OBJECTIVE");return;}
      if(npc.aiStatus().startsWith("作業保留")){finish("blocked",npc.aiStatus());return;}
      if(name.equals("combat")&&skeleton!=null&&!skeleton.isAlive()&&!killedByNpc){finish("impossible","TARGET_DIED_WITHOUT_NPC_KILL");return;}
      if(live.requests>6){finish("blocked","PLANNING_BUDGET_EXHAUSTED_AFTER_6_ACTUAL_REQUESTS");return;}
      if(System.nanoTime()-began>java.util.concurrent.TimeUnit.SECONDS.toNanos(240)){finish("timeout","WALL_LIMIT_240_SECONDS");return;}
      if((level.getGameTime()-tick)%100==0)save("running","");
    }
    void save(String state,String reason){
      data.addProperty("state",state);data.addProperty("reason",reason);data.addProperty("wallSeconds",began==0?0:(System.nanoTime()-began)/1e9);
      data.addProperty("instructionComplete",npc!=null&&npc.instruction().isBlank());data.addProperty("destinationWheat",destination==null?0:stored(destination));
      data.addProperty("sourceWheat",source==null?0:stored(source));data.addProperty("carriedWheat",carried());data.addProperty("killedByNpc",killedByNpc);
      if(npc!=null){data.add("lastFrame",frame());data.add("npcObservation",new NpcLocalWorld(npc,new NpcSkills(npc,(a,b)->{})).planningObservation());}
      if(live!=null){data.addProperty("planningRequestsAttempted",live.requests);data.add("planningExchanges",new Gson().toJsonTree(live.exchanges));data.add("planningErrors",new Gson().toJsonTree(live.errors));}
      try{Files.createDirectories(output);Files.writeString(output.resolve("result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(data));}
      catch(Exception e){throw new IllegalStateException(e);}
    }
    void finish(String state,String reason){
      if(ended)return;ended=true;save(state,reason);NeoForge.EVENT_BUS.unregister(deathListener);
      if(npc!=null)npc.mind().close();if(live!=null)live.close();
      if(state.equals("setup_failed")||state.equals("infrastructure_failed"))h.fail(reason);else h.succeed();
    }
  }
}

package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;
import com.google.gson.*;
import dev.avatarcraft.npc.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** Sequential real-planner missions. Natural resources are neither injected nor searched for by the harness. */
final class NpcCampaignGameTests {
  static final String[] COMMANDS={
      "周りにある木を切って、原木を1スタック集めてここのチェストに入れてほしい",
      "道具を作って、石炭を1スタック集めてここのチェストに入れてほしい",
      "畑に向かい、作物を収穫して植えなおしてほしい",
      "協力して、道具を作ってダイアモンドを10個採掘してここのチェストに入れてほしい",
      "協力して、スケルトンを5体倒してほしい"};

  static void register(RegisterGameTestsEvent event) {
    var env=event.registerEnvironment(bep_3davater_companion.id("campaign_qa"),new TestEnvironmentDefinition.AllOf());
    event.registerTest(bep_3davater_companion.id(System.getProperty("avatarcraft.targetMission", "").isEmpty()
        ? "five_mission_campaign" : "targeted_"+System.getProperty("avatarcraft.targetMission")),new FunctionGameTestInstance(
        BuiltinTestFunctions.ALWAYS_PASS,new TestData<>(env,bep_3davater_companion.id("qa_empty"),500000,1,true)) {
      public void run(GameTestHelper h) {new Run(h).start();}
    });
  }

  private static final class Run {
    final GameTestHelper h;
    final ServerLevel level;
    final long seed=Long.getLong("avatarcraft.naturalSeed",61279L),began=System.nanoTime();
    final JsonArray outcomes=new JsonArray();
    final String target=System.getProperty("avatarcraft.targetMission", "");
    final boolean independent=!target.isEmpty();
    final boolean supplied=Boolean.getBoolean("avatarcraft.suppliedDiamondTools"),realTime=Boolean.getBoolean("avatarcraft.qaRealTime");
    final int planLimit=Math.clamp(Integer.getInteger("avatarcraft.qaPlanLimit",24),1,24);
    final int wallLimit=Math.clamp(Integer.getInteger("avatarcraft.qaWallSeconds",1500),30,1500);
    final List<BlockPos> crops=new ArrayList<>();
    final List<Skeleton> skeletons=new ArrayList<>();
    final Set<BlockPos> harvestedCrops=new HashSet<>(),replantedCrops=new HashSet<>();
    final JsonObject evidence=object("seed",seed,"naturalResourceBlocksPlaced",0,"initialFoodPerNpc",16,
        "policyVersion",LocalActionPolicy.VERSION,"livePlanning",Boolean.getBoolean("avatarcraft.complexLive"),
        "startSelection","first safe dry surface, no wood or ore preference",
        "blockSearchRadiusPerCall",16,"fixtureFarmCrops",8,"fixtureSkeletons",5,
        "terrainObservationRadius",32,"entityObservationRadius",16,"chunkLoadingRadius",NpcChunkLoading.RADIUS);
    ServerPlayer owner;
    CompanionEntity leader,worker;
    NpcComplexLiveBrain live;
    BlockPos origin,chest;
    int phase;
    long phaseTick,phaseWall;
    boolean ended,farmReady,starting;

    Run(GameTestHelper helper){
      h=helper;level=h.getLevel();
      if(independent)phase=switch(target){
        case "logs"->0;case "coal"->1;case "farm"->2;case "diamond"->3;case "skeleton"->4;
        default->throw new IllegalArgumentException("Unknown targeted mission: "+target);
      };
      evidence.addProperty("independentMission",independent);
      evidence.addProperty("target",target);
      evidence.addProperty("initialTools",supplied&&Set.of(0,1,3).contains(phase)?9:0);
      evidence.addProperty("suppliedDiamondTools",supplied&&Set.of(0,1,3).contains(phase));
      evidence.addProperty("toolsPerTypePerNpc",supplied&&Set.of(0,1,3).contains(phase)?3:0);
      evidence.addProperty("maxActualPlanningRequests",planLimit);evidence.addProperty("maxMissionWallSeconds",wallLimit);
      evidence.addProperty("realTimePhysics",realTime);evidence.addProperty("safeWaterDepth",5);
      evidence.addProperty("initialFarmSeeds",independent?0:8);
    }

    void start(){
      try {
        if(!(level.getChunkSource().getGenerator() instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator)
            ||level.getSeed()!=seed)throw new IllegalStateException("Wrong natural world generator or seed");
        origin=findSurface();
        chest=surface(origin.getX()+2,origin.getZ());
        level.setBlockAndUpdate(chest,Blocks.CHEST.defaultBlockState());
        owner=h.makeMockServerPlayerInLevel();
        owner.setPos(Vec3.atBottomCenterOf(origin));
        leader=spawn(origin,"yilnel");
        evidence.add("origin",new Gson().toJsonTree(List.of(origin.getX(),origin.getY(),origin.getZ())));
        evidence.add("chest",new Gson().toJsonTree(List.of(chest.getX(),chest.getY(),chest.getZ())));
        evidence.addProperty("leader",leader.getUUID().toString());
        evidence.addProperty("generator",level.getChunkSource().getGenerator().getClass().getSimpleName());
        evidence.addProperty("weightsSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(LocalActionPolicy.class.getResourceAsStream("/data/bep_3davater_companion/ai/action-policy.bin").readAllBytes())));
        var hashes=new JsonObject();
        for(var type:List.of(CompanionEntity.class,NpcMind.class,NpcLocalWorld.class,NpcSkills.class,
            NpcTeams.class,LocalTaskRunner.class,LocalActionPolicy.class,NpcMission.class,NpcFarming.class,
            NpcToolQueue.class,NpcWalkingRoute.class,NpcExecutionMemory.class,NpcGroundNavigation.class,NpcSpatialObservation.class,
            NpcChunkLoading.class,NpcWaterTravel.class,NpcWaterSafety.class,NpcStepMoveControl.class,NpcFollowing.class,
            NpcWorkRoute.class,TaskPlan.class,NpcTools.class,NpcCampaignGameTests.class,Run.class))
          try(var stream=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")){
            hashes.addProperty(type.getSimpleName(),java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes())));
          }
        evidence.add("runtimeClassSha256",hashes);
        beginMission();
        h.onEachTick(this::tick);
      }catch(Exception e){finish("setup_failed",e.toString());}
    }

    BlockPos surface(int x,int z){
      level.getChunk(x>>4,z>>4);
      return new BlockPos(x,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z),z);
    }

    BlockPos findSurface(){
      for(int radius=0;radius<=256;radius+=16)
        for(int x=-radius;x<=radius;x+=16)for(int z=-radius;z<=radius;z+=16){
          if(Math.max(Math.abs(x),Math.abs(z))!=radius)continue;
          var p=surface(x,z); boolean safe=true;
          for(var q:List.of(p,surface(x+2,z),surface(x,z+2)))
            safe &= level.getBlockState(q).canBeReplaced()&&level.getFluidState(q).isEmpty()
                &&level.getBlockState(q.above()).getCollisionShape(level,q.above()).isEmpty()
                &&level.getBlockState(q.below()).isFaceSturdy(level,q.below(),Direction.UP)
                &&Math.abs(q.getY()-p.getY())<=2;
          if(safe)return p;
        }
      throw new IllegalStateException("NO_SAFE_DRY_START_WITHIN_256");
    }

    CompanionEntity spawn(BlockPos p,String name){
      var n=bep_3davater_companion.COMPANION.get().create(level,EntitySpawnReason.COMMAND);
      n.initialize(owner,"tortobu");n.setCustomName(net.minecraft.network.chat.Component.literal(name));
      n.setFollowsOwner(false);n.setPos(Vec3.atBottomCenterOf(p));
      n.inventory.setItem(0,new ItemStack(Items.BREAD,16));
      if(supplied&&Set.of(0,1,3).contains(phase)){
        int slot=1;for(var item:List.of(Items.DIAMOND_PICKAXE,Items.DIAMOND_SHOVEL,Items.DIAMOND_AXE))
          for(int i=0;i<3;i++)n.inventory.setItem(slot++,new ItemStack(item));
      }
      level.addFreshEntity(n);new NpcChunkLoading().update(n);return n;
    }

    String command(){String override=System.getProperty("avatarcraft.qaCommand","");return override.isBlank()?COMMANDS[phase]:override;}

    void beginMission(){
      if(live!=null)live.close();
      if(phase==2&&!farmReady){
        starting=true;farm();farmReady=true;
        h.runAfterDelay(20,()->{
          try{
            int removed=0;
            var bounds=new net.minecraft.world.phys.AABB(Vec3.atCenterOf(crops.getFirst()),Vec3.atCenterOf(crops.getLast())).inflate(4);
            for(var drop:level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,bounds,
                item->item.getItem().is(Items.WHEAT)||item.getItem().is(Items.WHEAT_SEEDS))){
              removed+=drop.getItem().getCount();drop.discard();
            }
            for(var p:crops)level.setBlock(p,((CropBlock)Blocks.WHEAT).getStateForAge(7),2);
            if(crops.size()!=8||crops.stream().anyMatch(p->!level.getBlockState(p).is(Blocks.WHEAT)
                ||!((CropBlock)Blocks.WHEAT).isMaxAge(level.getBlockState(p))))throw new IllegalStateException("FARM_FIXTURE_NOT_EIGHT_MATURE_CROPS");
            evidence.addProperty("fixtureMatureCropsAtCommand",8);
            evidence.addProperty("fixturePreCommandDropsRemoved",removed);
            evidence.addProperty("fixtureCropDropsAtCommand",0);starting=false;beginMission();
          }catch(Exception e){finish("setup_failed",e.toString());}
        });return;
      }
      if(phase>=3&&worker==null){worker=spawn(surface(origin.getX(),origin.getZ()+2),"clonka");evidence.addProperty("worker",worker.getUUID().toString());}
      if(phase==4)combat();
      live=new NpcComplexLiveBrain((independent?"targeted-":"campaign-")+seed+"-"+phase,()->"",planLimit);
      leader.gameTestMind(new NpcMind(leader,NpcService.get(level.getServer()),live));
      if(worker!=null)worker.gameTestMind(new NpcMind(worker,NpcService.get(level.getServer()),live));
      owner.setPos(Vec3.atBottomCenterOf(surface(chest.getX(),chest.getZ()-2)));
      NpcLocationGameTests.aim(owner,chest);
      leader.setInstruction(command(),NpcCommandContext.capture(leader,owner));
      phaseTick=level.getGameTime();phaseWall=System.nanoTime();
      NpcService.get(level.getServer()).record(leader,"campaign_mission",command(),null,object("seed",seed,"mission",phase));
      if(independent){
        var initial=new NpcLocalWorld(leader,new NpcSkills(leader,(a,b)->{})).planningObservation();
        evidence.add("initialObservation",initial);
        if(worker!=null)evidence.add("initialWorkerObservation",new NpcLocalWorld(worker,new NpcSkills(worker,(a,b)->{})).planningObservation());
        NpcService.get(level.getServer()).record(leader,"targeted_initial_observation",command(),null,
            object("seed",seed,"mission",phase,"observation",initial));
      }
      save("running","");
    }

    void farm(){
      // Eight mature wheat crops on a small irrigated fixture near the original starting point.
      int y=origin.getY();var center=origin.offset(independent?6:12,0,0);
      for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
        var p=new BlockPos(center.getX()+dx,y,center.getZ()+dz);
        level.setBlockAndUpdate(p.below(2),Blocks.DIRT.defaultBlockState());
        int top=level.getHeight(Heightmap.Types.WORLD_SURFACE,p.getX(),p.getZ());
        for(int clearY=y;clearY<=Math.max(y+2,top+1);clearY++)
          level.setBlockAndUpdate(new BlockPos(p.getX(),clearY,p.getZ()),Blocks.AIR.defaultBlockState());
        if(dx==0&&dz==0){level.setBlockAndUpdate(p,Blocks.AIR.defaultBlockState());level.setBlockAndUpdate(p.below(),Blocks.WATER.defaultBlockState());continue;}
        level.setBlockAndUpdate(p.below(),Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));
        crops.add(p);
      }
      var anchor=center.offset(-3,0,0);
      level.setBlockAndUpdate(anchor.below(),Blocks.DIRT.defaultBlockState());
      level.setBlockAndUpdate(anchor.above(),Blocks.AIR.defaultBlockState());
      level.setBlockAndUpdate(anchor,bep_3davater_companion.AVATAR_ANCHOR.get().defaultBlockState());
      ((AvatarAnchorBlockEntity)level.getBlockEntity(anchor)).setAnchorId("畑");
      // Seed guarantee prevents random drop RNG from making replanting impossible.
      if(!independent)leader.inventory.insert(new ItemStack(Items.WHEAT_SEEDS,8));
      evidence.add("farmCrops",new Gson().toJsonTree(crops.stream().map(p->List.of(p.getX(),p.getY(),p.getZ())).toList()));
    }

    void combat(){
      h.setTime(18000);
      level.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);
      evidence.addProperty("difficulty",level.getDifficulty().toString());
      for(var n:List.of(leader,worker)){
        n.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.DIAMOND_HELMET));
        n.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.DIAMOND_CHESTPLATE));
        n.setItemSlot(EquipmentSlot.LEGS,new ItemStack(Items.DIAMOND_LEGGINGS));
        n.setItemSlot(EquipmentSlot.FEET,new ItemStack(Items.DIAMOND_BOOTS));
        n.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SWORD));
      }
      for(int i=0;i<5;i++){
        var p=surface(origin.getX()+6+i*2,origin.getZ()+5);
        var mob=EntityTypes.SKELETON.create(level,EntitySpawnReason.COMMAND);
        mob.setPos(Vec3.atBottomCenterOf(p));mob.setPersistenceRequired();
        mob.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.BOW));
        level.addFreshEntity(mob);skeletons.add(mob);
      }
      evidence.add("skeletons",new Gson().toJsonTree(skeletons.stream().map(s->s.getUUID().toString()).toList()));
    }

    int stored(String target){
      int amount=0;if(level.getBlockEntity(chest) instanceof Container c)
        for(int i=0;i<c.getContainerSize();i++){
          var s=c.getItem(i);
          if(target.equals("logs")? s.is(net.minecraft.tags.ItemTags.LOGS):s.is(target.equals("coal")?Items.COAL:Items.DIAMOND))amount+=s.getCount();
        }
      return amount;
    }

    int kills(){int total=0;for(var s:skeletons)
      if(!s.isAlive()&&(s.getLastHurtByMob()==leader||s.getLastHurtByMob()==worker))total++;return total;}

    boolean verified(){return switch(phase){
      case 0->stored("logs")>=64;
      case 1->stored("coal")>=64;
      case 2->replantedCrops.size()==8&&crops.size()==8&&crops.stream().allMatch(p->level.getBlockState(p).is(Blocks.WHEAT))
          &&NpcNeoRecoveryGameTests.count(leader,Items.WHEAT)>=8;
      case 3->stored("diamond")>=10;
      case 4->kills()==5;
      default->false;};}

    void tick(){
      if(ended||starting||live==null)return;
      if(live.requests>planLimit){endMission("blocked","QA_LIVE_PLANNING_BUDGET_EXHAUSTED_AFTER_"+planLimit+"_REQUESTS");return;}
      for(var p:crops){
        var state=level.getBlockState(p);
        if(!state.is(Blocks.WHEAT)||((CropBlock)Blocks.WHEAT).getAge(state)<7)harvestedCrops.add(p);
        if(harvestedCrops.contains(p)&&state.is(Blocks.WHEAT)&&((CropBlock)Blocks.WHEAT).getAge(state)<7)replantedCrops.add(p);
      }
      if(realTime||live.waiting())java.util.concurrent.locks.LockSupport.parkNanos(50_000_000L);
      if(realTime&&(level.getGameTime()-phaseTick)%20==0)for(var actor:worker==null?List.of(leader):List.of(leader,worker))
        NpcService.get(level.getServer()).record(actor,"campaign_vitals","Runtime water/health trace",null,
            object("tick",level.getGameTime()-phaseTick,"position",NpcSpatialObservation.vector(actor.position()),"health",actor.getHealth(),
                "air",actor.getAirSupply(),"water",actor.isInWater(),"sprinting",actor.isSprinting(),"current",actor.isInWater()&&NpcWaterTravel.flowing(actor),
                "safety",actor.waterSafety.observation()));
      if(!leader.isAlive()||worker!=null&&!worker.isAlive()){endMission("failed","NPC_DIED");return;}
      if(leader.instruction().isBlank()){
        if(!verified()){endMission("failed","PLANNER_COMPLETION_WITHOUT_VERIFIED_GOAL");return;}
        if(!leader.locations.entries().isEmpty()){endMission("failed","LOCATION_MEMORY_NOT_CLEARED");return;}
        endMission("passed","");return;
      }
      if(leader.aiStatus().startsWith("作業保留")){
        endMission("blocked",leader.aiStatus());return;
      }
      if(level.getGameTime()-phaseTick>98000||System.nanoTime()-phaseWall>java.util.concurrent.TimeUnit.SECONDS.toNanos(wallLimit)){
        endMission("timeout","MISSION_LIMIT_REACHED_IMPOSSIBILITY_NOT_PROVEN");return;
      }
      if((level.getGameTime()-phaseTick)%400==0)save("running","");
    }

    void endMission(String state,String reason){
      var outcome=object("mission",phase,"instruction",command(),"state",state,"reason",reason,
          "gameTicks",level.getGameTime()-phaseTick,"wallSeconds",(System.nanoTime()-phaseWall)/1e9,
          "planningRequests",live.requests,"plans",new Gson().toJsonTree(live.plans),
          "planningErrors",new Gson().toJsonTree(live.errors),"locationsAfter",leader.locations.snapshot(),
          "logsInChest",stored("logs"),"coalInChest",stored("coal"),"diamondsInChest",stored("diamond"),"npcSkeletonKills",kills());
      outcome.addProperty("cropsHarvested",harvestedCrops.size());outcome.addProperty("cropsReplanted",replantedCrops.size());
      outcome.add("planningExchanges",new Gson().toJsonTree(live.exchanges));
      outcomes.add(outcome);
      if(independent){finish(state,reason);return;}
      if(!state.equals("passed")){finish("stopped",reason);return;}
      if(++phase==COMMANDS.length){finish("passed","");return;}
      beginMission();
    }

    void save(String state,String reason){
      evidence.addProperty("state",state);evidence.addProperty("reason",reason);evidence.addProperty("currentMission",phase);
      evidence.addProperty("wallSeconds",(System.nanoTime()-began)/1e9);evidence.add("outcomes",outcomes);
      evidence.addProperty("remainingMissionsNotAttempted",independent?0:Math.max(0,COMMANDS.length-outcomes.size()));
      if(leader!=null){
        evidence.addProperty("status",leader.aiStatus());
        evidence.add("leaderState",new NpcLocalWorld(leader,new NpcSkills(leader,(a,b)->{})).planningObservation());
        evidence.addProperty("logsInChest",stored("logs"));evidence.addProperty("coalInChest",stored("coal"));evidence.addProperty("diamondsInChest",stored("diamond"));
      }
      if(live!=null)evidence.addProperty("planningRequests",live.requests);
      if(worker!=null)evidence.add("workerState",new NpcLocalWorld(worker,new NpcSkills(worker,(a,b)->{})).planningObservation());
      evidence.addProperty("cropsHarvested",harvestedCrops.size());evidence.addProperty("cropsReplanted",replantedCrops.size());
      evidence.addProperty("npcSkeletonKills",kills());
      if(leader!=null)evidence.add("leaderExecutionMemory",JsonParser.parseString(leader.executionMemory.save()));
      if(worker!=null)evidence.add("workerExecutionMemory",JsonParser.parseString(worker.executionMemory.save()));
      if(live!=null)evidence.add("planningExchanges",new Gson().toJsonTree(live.exchanges));
      try{var folder=Path.of(System.getProperty("avatarcraft.qaOutputRoot","../build"))
          .resolve(independent?"npc-targeted-results":"npc-campaign-results");Files.createDirectories(folder);
        Files.writeString(folder.resolve(independent?seed+"-"+target+".json":seed+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(evidence));
      }catch(Exception e){throw new IllegalStateException(e);}
    }

    void finish(String state,String reason){
      if(ended)return;ended=true;save(state,reason);
      if(leader!=null)leader.mind().close();if(worker!=null)worker.mind().close();if(live!=null)live.close();
      // A stopped world is a valid campaign outcome; the evidence, not GameTest success, measures mission success.
      if(state.equals("setup_failed"))h.fail(reason);else h.succeed();
    }
  }
}

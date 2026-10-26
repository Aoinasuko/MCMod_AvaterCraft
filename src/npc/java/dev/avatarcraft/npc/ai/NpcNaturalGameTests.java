package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.*;
import dev.avatarcraft.npc.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** Normal noise terrain. Only the designated empty chest and initial actors are placed. */
final class NpcNaturalGameTests {
  static void register(RegisterGameTestsEvent event) {
    var env = event.registerEnvironment(bep_3davater_companion.id("natural_qa"), new TestEnvironmentDefinition.AllOf());
    event.registerTest(bep_3davater_companion.id("natural_two_empty_tools_diamond_ten"),
        new FunctionGameTestInstance(BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(env, bep_3davater_companion.id("qa_empty"), 100000, 1, true)) {
          public void run(GameTestHelper h) { new Run(h).start(); }
        });
  }

  private static final class Run {
    final GameTestHelper h;
    final ServerLevel level;
    final long seed = Long.getLong("avatarcraft.naturalSeed", 61279L);
    final long began = System.nanoTime();
    final JsonObject evidence = object("seed", seed, "placedResources", 0, "initialTools", 0,
        "initialFoodPerNpc", 16, "livePlanning", Boolean.getBoolean("avatarcraft.complexLive"),
        "policyVersion", LocalActionPolicy.VERSION, "mobSpawning", false);
    CompanionEntity leader, worker;
    NpcComplexLiveBrain live;
    BlockPos chest;
    long startedTick;
    int maximumDiamonds;
    boolean ended;

    Run(GameTestHelper h) { this.h = h; level = h.getLevel(); }

    void start() {
      try {
        if (!(level.getChunkSource().getGenerator() instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator))
          throw new IllegalStateException("Natural QA requires the noise world preset, not a flat fixture");
        if (level.getSeed() != seed) throw new IllegalStateException("Wrong world seed: " + level.getSeed());
        var origin = findSurface();
        var owner = h.makeMockServerPlayerInLevel();
        owner.setPos(Vec3.atBottomCenterOf(surface(origin.getX(), origin.getZ()-3)));
        chest = surface(origin.getX()+2, origin.getZ());
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        leader = spawn(owner, origin, "yilnel");
        worker = spawn(owner, surface(origin.getX(), origin.getZ()+2), "clonka");
        for (var n : List.of(leader, worker))
          n.locations.remember(new NpcLocationMemory.Place("回収地点", level.dimension().identifier().toString(),
              chest.getX(), chest.getY(), chest.getZ(), "chest"));
        evidence.addProperty("generator", level.getChunkSource().getGenerator().getClass().getSimpleName());
        var classes=new JsonObject();
        for(var type:List.of(NpcLocalWorld.class,NpcSkills.class,NpcMind.class,NpcTeams.class,
            NpcSurvival.class,NpcWorkRoute.class,NpcLongTravel.class,LocalTaskRunner.class,LocalActionPolicy.class)) {
          try(var stream=type.getResourceAsStream(type.getSimpleName()+".class")) {
            classes.addProperty(type.getSimpleName(),HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes())));
          }
        }
        evidence.add("runtimeClassSha256",classes);
        evidence.add("start", new Gson().toJsonTree(List.of(origin.getX(), origin.getY(), origin.getZ())));
        evidence.add("chest", new Gson().toJsonTree(List.of(chest.getX(), chest.getY(), chest.getZ())));
        evidence.addProperty("leader", leader.getUUID().toString());
        evidence.addProperty("worker", worker.getUUID().toString());
        live = new NpcComplexLiveBrain("natural-" + seed, () -> "");
        for (var n : List.of(leader, worker))
          n.gameTestMind(new NpcMind(n, NpcService.get(level.getServer()), live));
        leader.setInstruction("clonkaと協力して、道具を集めるところから始めてダイヤモンドを10個掘ってきてほしい。"
            + "終わったら、記憶している回収地点のチェストに10個入れてほしい。");
        startedTick = level.getGameTime();
        save("running", "");
        h.onEachTick(this::tick);
      } catch (Exception e) { finish(false, e.toString()); }
    }

    BlockPos findSurface() {
      // Choose safe woodland by surface/wood observations only; never inspect ore locations.
      for (int radius = 0; radius <= 384; radius += 16)
        for (int x = -radius; x <= radius; x += 16)
          for (int z = -radius; z <= radius; z += 16) {
            if (Math.max(Math.abs(x), Math.abs(z)) != radius) continue;
            level.getChunk(x >> 4, z >> 4);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            var p = new BlockPos(x, y, z);
            if (y < 62 || y > 100) continue;
            boolean safe = true;
            for (var q : List.of(p, surface(x,z+2), surface(x+2,z), surface(x,z-3)))
              safe &= level.getBlockState(q).canBeReplaced() && level.getFluidState(q).isEmpty()
                  && level.getBlockState(q.above()).getCollisionShape(level,q.above()).isEmpty()
                  && level.getBlockState(q.below()).isFaceSturdy(level,q.below(),Direction.UP)
                  && Math.abs(q.getY()-p.getY()) <= 2;
            if (!safe) continue;
            int logs = 0;
            for (var q : BlockPos.betweenClosed(p.offset(-12,-2,-12),p.offset(12,12,12)))
              if (level.getBlockState(q).is(net.minecraft.tags.BlockTags.LOGS)) logs++;
            if (logs >= 6) return p;
          }
      throw new IllegalStateException("No safe woodland start within 384 blocks");
    }

    BlockPos surface(int x, int z) {
      return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z), z);
    }

    CompanionEntity spawn(ServerPlayer owner, BlockPos position, String name) {
      var n = bep_3davater_companion.COMPANION.get().create(level, EntitySpawnReason.COMMAND);
      n.initialize(owner, "tortobu");
      n.setCustomName(net.minecraft.network.chat.Component.literal(name));
      n.setFollowsOwner(false);
      n.setPos(Vec3.atBottomCenterOf(position));
      n.inventory.setItem(0, new ItemStack(Items.BREAD, 16));
      level.addFreshEntity(n);
      new NpcChunkLoading().update(n);
      return n;
    }

    void tick() {
      if (ended) return;
      if (level.getGameTime() - startedTick >= 99000) { finish(false, "GAME_TICK_TIMEOUT"); return; }
      if (live.waiting()) java.util.concurrent.locks.LockSupport.parkNanos(50_000_000L);
      if (!leader.isAlive() || !worker.isAlive()) { finish(false, "NPC_DIED"); return; }
      int stored = 0;
      if (level.getBlockEntity(chest) instanceof Container c)
        for (int i=0;i<c.getContainerSize();i++) if (c.getItem(i).is(Items.DIAMOND)) stored += c.getItem(i).getCount();
      maximumDiamonds = Math.max(maximumDiamonds, stored);
      if (stored == 10 && leader.instruction().isBlank()) { finish(true, ""); return; }
      if (leader.aiStatus().startsWith("作業保留")) { finish(false, leader.aiStatus()); return; }
      if ((System.nanoTime() - began) > java.util.concurrent.TimeUnit.MINUTES.toNanos(25)) {
        finish(false, "WALL_TIMEOUT_25_MINUTES"); return;
      }
      if ((level.getGameTime() - startedTick) % 200 == 0) save("running", "");
    }

    void save(String state, String problem) {
      evidence.addProperty("state", state); evidence.addProperty("problem", problem);
      evidence.addProperty("gameTicks", level.getGameTime() - startedTick);
      evidence.addProperty("wallSeconds", (System.nanoTime() - began) / 1_000_000_000.);
      evidence.addProperty("storedDiamonds", maximumDiamonds);
      if (leader != null) evidence.addProperty("leaderTicks", leader.tickCount);
      if (worker != null) evidence.addProperty("workerTicks", worker.tickCount);
      if (leader != null) evidence.add("leaderState", new NpcLocalWorld(leader, new NpcSkills(leader,(a,b)->{})).planningObservation());
      if (leader != null) evidence.add("leaderExecutionMemory",JsonParser.parseString(leader.executionMemory.save()));
      if (worker != null) evidence.add("workerState", new NpcLocalWorld(worker, new NpcSkills(worker,(a,b)->{})).planningObservation());
      if (live != null) {
        evidence.addProperty("planningRequests", live.requests);
        evidence.add("plans", new Gson().toJsonTree(live.plans));
        evidence.add("planningErrors", new Gson().toJsonTree(live.errors));
      }
      try {
        var folder = Path.of("../build/npc-natural-results"); Files.createDirectories(folder);
        Files.writeString(folder.resolve(seed + ".json"),new GsonBuilder().setPrettyPrinting().create().toJson(evidence));
      } catch (Exception e) { throw new IllegalStateException(e); }
    }

    void finish(boolean ok, String problem) {
      if (ended) return;
      ended = true; save(ok ? "passed" : "failed", problem);
      if (leader != null) leader.mind().close();
      if (worker != null) worker.mind().close();
      if (live != null) live.close();
      if (ok) h.succeed(); else h.fail(problem);
    }
  }
}

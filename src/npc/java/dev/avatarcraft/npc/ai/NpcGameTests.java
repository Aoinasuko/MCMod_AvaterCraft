package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.JsonObject;
import dev.avatarcraft.npc.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** Opt-in development GameTest; uses an isolated world and the actual server game operations. */
public final class NpcGameTests {
  public static void register(RegisterGameTestsEvent event) {
    if (Boolean.getBoolean("avatarcraft.waterQa")) { NpcWaterPoolGameTests.register(event); return; }
    if (Boolean.getBoolean("avatarcraft.campaignQa")) { NpcCampaignGameTests.register(event); return; }
    if (Boolean.getBoolean("avatarcraft.naturalQa")) { NpcNaturalGameTests.register(event); return; }
    if (Boolean.getBoolean("avatarcraft.journalQa")) { NpcJournalGameTests.register(event); return; }
    if (Boolean.getBoolean("avatarcraft.aiSettingsQa")) { NpcAiSettingsGameTests.register(event); return; }
    if (Boolean.getBoolean("avatarcraft.areaQa")) {
      NpcAreaWorkGameTests.register(event);
      if (!Boolean.getBoolean("avatarcraft.areaLive")) NpcConstructionGameTests.register(event);
      else NpcConstructionGameTests.registerLive(event);
      return;
    }
    if (Boolean.getBoolean("avatarcraft.anchorQa")) {
      NpcAnchorGameTests.register(event);
      return;
    }
    if (Boolean.getBoolean("avatarcraft.complexQa")) {
      NpcComplexGameTests.register(event);
      return;
    }
    NpcSurvivalGameTests.register(event);
    NpcNeoRecoveryGameTests.register(event);
    NpcLocationGameTests.register(event);
    NpcMissionGameTests.register(event);
    NpcSpatialGameTests.register(event);
    NpcWaterNavigationGameTests.register(event);
    NpcLifecycleGameTests.register(event);
    NpcInteractionGameTests.register(event);
    NpcWorkRouteGameTests.register(event);
    NpcNavigationGameTests.register(event);
    NpcSpeedGameTests.register(event);
    NpcActorRouteGameTests.register(event);
    NpcArrivalGameTests.register(event);
    NpcReflexGameTests.register(event);
    NpcChatOrderGameTests.register(event);
    NpcTerrainEdgeGameTests.register(event);
    NpcRecoveryGameTests.register(event);
    NpcRuntimeFixGameTests.register(event);
    NpcLongTravelGameTests.register(event);
    NpcExpeditionScenarioGameTests.register(event);
    NpcSurfaceGameTests.register(event);
    NpcAnchorGameTests.register(event);
    NpcAreaWorkGameTests.register(event);
    NpcConstructionGameTests.register(event);
    var environment =
        event.registerEnvironment(bep_3davater_companion.id("qa"), new TestEnvironmentDefinition.AllOf());
    event.registerTest(
        bep_3davater_companion.id("blocked_plan_reports_reason"),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(environment, bep_3davater_companion.id("qa_empty"), 300, 1, true)) {
          public void run(GameTestHelper h) {
            blockedPlanScenario(h);
          }
        });
    event.registerTest(
        bep_3davater_companion.id("location_memory_tools"),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(environment, bep_3davater_companion.id("qa_empty"), 300, 1, true)) {
          public void run(GameTestHelper h) {
            locationToolScenario(h);
          }
        });
    event.registerTest(
        bep_3davater_companion.id("diamond_stairs_and_tool_preparation"),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(environment, bep_3davater_companion.id("qa_large"), 3600, 1, true)) {
          public void run(GameTestHelper h) {
            diamondStairsScenario(h);
          }
        });
    event.registerTest(
        bep_3davater_companion.id("remembered_chest_round_trip"),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(environment, bep_3davater_companion.id("qa_large"), 1800, 1, true)) {
          public void run(GameTestHelper h) {
            rememberedTripScenario(h);
          }
        });
    event.registerTest(
        bep_3davater_companion.id("stairs_reject_liquid_and_unbreakable"),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(environment, bep_3davater_companion.id("qa_large"), 80, 1, true)) {
          public void run(GameTestHelper h) {
            hazardousStairsScenario(h);
          }
        });
    event.registerTest(
        bep_3davater_companion.id("local_policy_collect_and_store"),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(environment, bep_3davater_companion.id("qa_empty"), 600, 1, true)) {
          public void run(GameTestHelper h) {
            localPolicyScenario(h);
          }
        });
    event.registerTest(
        bep_3davater_companion.id("independent_planning_action_speech"),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(environment, bep_3davater_companion.id("qa_empty"), 1200, 1, true)) {
          public void run(GameTestHelper h) {
            independentSpeechScenario(h);
          }
        });
    event.registerTest(
        bep_3davater_companion.id("local_policy_saved_remaining_count"),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(environment, bep_3davater_companion.id("qa_empty"), 400, 1, true)) {
          public void run(GameTestHelper h) {
            savedPolicyScenario(h);
          }
        });
    event.registerTest(
        bep_3davater_companion.id("companion_lifecycle"),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(environment, bep_3davater_companion.id("qa_empty"), 600, 1, true)) {
          @Override
          public void run(GameTestHelper helper) {
            new Scenario(helper).start();
          }
        });
    event.registerTest(
        bep_3davater_companion.id("follow_catch_up"),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(environment, bep_3davater_companion.id("qa_empty"), 300, 1, true)) {
          public void run(GameTestHelper h) {
            for (int x = 0; x < 12; x++)
              for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.STONE);
            for (int y = 1; y <= 3; y++)
              for (int z = 0; z <= 5; z++) h.setBlock(5, y, z, Blocks.STONE);
            var owner = h.makeMockServerPlayerInLevel();
            owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(10, 1, 2))));
            var npc = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
            npc.initialize(owner, "tortobu");
            npc.setFollowsOwner(false);
            npc.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 2))));
            h.getLevel().addFreshEntity(npc);
            h.assertTrue(!npc.hasLineOfSight(owner), "Wall must initially obstruct the owner");
            var skills = new NpcSkills(npc, (text, target) -> {});
            var result =
                skills
                    .execute("follow", object("entity_id", owner.getId(), "seconds", 10))
                    .toCompletableFuture();
            boolean[] sprinted = {false};
            h.onEachTick(
                () -> {
                  skills.tick();
                  sprinted[0] |= npc.isSprinting();
                  if (result.isDone()) {
                    h.assertTrue(
                        result.join().get("ok").getAsBoolean(), "Follow must finish successfully");
                    h.assertTrue(sprinted[0], "A distant owner must trigger sprinting");
                    h.assertTrue(
                        npc.distanceToSqr(owner) < 9, "Must navigate around the wall and catch up");
                    h.assertTrue(!npc.isSprinting(), "Sprint must stop after following ends");
                    npc.discard();
                    h.succeed();
                  }
                });
          }
        });
  }

  private static void locationToolScenario(GameTestHelper h) {
    for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.STONE);
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(2, 1, 2))));
    var npc = testNpc(h, owner, 2, 1, 3);
    var skills = new NpcSkills(npc, (text, target) -> {});
    var queue = new ArrayDeque<Step>();
    for (int i = 0; i < 9; i++) {
      final int n = i;
      var p = h.absolutePos(new BlockPos(3 + i % 4, 1, 3 + i / 4));
      queue.add(
          new Step(
              "remember",
              () ->
                  skills.execute(
                      "remember_location",
                      object("name", "point_" + n, "x", p.getX(), "y", p.getY(), "z", p.getZ())),
              r ->
                  h.assertTrue(
                      r.get("ok").getAsBoolean() == (n < 8),
                      "Ninth memory must fail without eviction")));
    }
    queue.add(
        new Step(
            "forget",
            () -> skills.execute("forget_location", object("name", "point_0")),
            r ->
                h.assertTrue(
                    r.get("ok").getAsBoolean(), "Explicit forgetting must free one slot")));
    var p = h.absolutePos(new BlockPos(4, 1, 4));
    queue.add(
        new Step(
            "replace",
            () ->
                skills.execute(
                    "remember_location",
                    object("name", "new_point", "x", p.getX(), "y", p.getY(), "z", p.getZ())),
            r ->
                h.assertTrue(
                    r.get("ok").getAsBoolean() && npc.locations.entries().size() == 8,
                    "Freed slot must accept a new memory")));
    var pending = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<JsonObject>>();
    var current = new java.util.concurrent.atomic.AtomicReference<Step>();
    h.onEachTick(
        () -> {
          if (pending.get() != null && pending.get().isDone()) {
            current.get().verify.accept(pending.get().join());
            pending.set(null);
          }
          if (pending.get() == null) {
            if (queue.isEmpty()) {
              npc.discard();
              h.succeed();
              return;
            }
            current.set(queue.remove());
            pending.set(current.get().begin.get().toCompletableFuture());
          }
        });
  }

  private static CompanionEntity testNpc(
      GameTestHelper h, ServerPlayer owner, int x, int y, int z) {
    var npc = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    npc.initialize(owner, "tortobu");
    npc.setFollowsOwner(false);
    npc.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(x, y, z))));
    h.getLevel().addFreshEntity(npc);
    return npc;
  }

  private static int contents(net.minecraft.world.Container container, Item item) {
    int n = 0;
    for (int i = 0; i < container.getContainerSize(); i++)
      if (container.getItem(i).is(item)) n += container.getItem(i).getCount();
    return n;
  }

  private static void diamondStairsScenario(GameTestHelper h) {
    for (int x = -6; x <= 16; x++)
      for (int z = -6; z <= 16; z++)
        for (int y = -16; y <= 0; y++) h.setBlock(x, y, z, Blocks.STONE);
    var owner = h.makeMockServerPlayerInLevel();
    owner.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3, 1, 2))));
    var npc = testNpc(h, owner, 3, 1, 3);
    npc.inventory.setItem(0, new ItemStack(Items.IRON_INGOT, 3));
    npc.inventory.setItem(1, new ItemStack(Items.OAK_LOG, 2));
    h.setBlock(7, -11, 3, Blocks.DEEPSLATE_DIAMOND_ORE);
    h.setBlock(2, 1, 3, Blocks.CHEST);
    var skills = new NpcSkills(npc, (text, target) -> {});
    var world = new NpcLocalWorld(npc, skills);
    var plan =
        world.bindLocations(
            new TaskPlan(
                1,
                "Prepare tool, descend, collect and return diamond",
                "",
                List.of(
                    new TaskPlan.Step(
                        "collect",
                        "minecraft:diamond",
                        "minecraft:diamond_ore",
                        1,
                        -1,
                        -1,
                        "main",
                        1,
                        List.of()),
                    new TaskPlan.Step(
                        "deposit", "minecraft:diamond", "", 1, -1, -1, "main", 1, List.of()))));
    var runner = new LocalTaskRunner(plan, world, npc.personality().allowedSkills);
    double initial = npc.getY();
    double[] lowest = {initial};
    h.onEachTick(
        () -> {
          skills.tick();
          runner.tick(h.getLevel().getGameTime());
          lowest[0] = Math.min(lowest[0], npc.getY());
          h.assertTrue(
              !runner.needsReplan(),
              "Diamond task failed: " + runner.progress() + " pos=" + npc.position());
          if (runner.complete()) {
            var chest =
                (net.minecraft.world.Container)
                    h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(2, 1, 3)));
            h.assertTrue(
                contents(chest, Items.DIAMOND) == 1, "Must return real diamond to origin chest");
            h.assertTrue(
                npc.getMainHandItem().is(Items.IRON_PICKAXE),
                "Must craft and equip an iron pickaxe from real ingredients");
            h.assertTrue(lowest[0] < initial - 8, "Must descend beyond original search depth");
            h.assertTrue(
                h.getBlockState(new BlockPos(3, 0, 3)).is(Blocks.STONE),
                "Must not dig out the original standing support");
            runner.close();
            npc.discard();
            h.succeed();
          }
        });
  }

  private static void rememberedTripScenario(GameTestHelper h) {
    for (int x = 0; x <= 28; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.STONE);
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(2, 1, 2))));
    var npc = testNpc(h, owner, 2, 1, 3);
    h.setBlock(3, 1, 3, Blocks.CHEST);
    h.setBlock(24, 1, 5, Blocks.CHEST);
    var drop =
        new net.minecraft.world.entity.item.ItemEntity(
            h.getLevel(),
            h.absolutePos(new BlockPos(24, 1, 3)).getX() + .5,
            h.absolutePos(new BlockPos(24, 1, 3)).getY() + .2,
            h.absolutePos(new BlockPos(24, 1, 3)).getZ() + .5,
            new ItemStack(Items.DIAMOND, 2));
    drop.setNoPickUpDelay();
    h.getLevel().addFreshEntity(drop);
    var skills = new NpcSkills(npc, (text, target) -> {});
    var world = new NpcLocalWorld(npc, skills);
    world.planningObservation();
    String dim = h.getLevel().dimension().identifier().toString();
    var pickup = h.absolutePos(new BlockPos(24, 1, 3));
    npc.locations.remember(
        new NpcLocationMemory.Place(
            "回収地点", dim, pickup.getX(), pickup.getY(), pickup.getZ(), "point"));
    for (int i = 0; i < 6; i++)
      npc.locations.remember(
          new NpcLocationMemory.Place(
              "地点" + i, dim, pickup.getX(), pickup.getY(), pickup.getZ() + i, "point"));
    h.assertTrue(npc.locations.entries().size() == 8, "Exactly eight persistent memory slots");
    var output =
        TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
    npc.saveWithoutId(output);
    var restored = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.LOAD);
    restored.load(
        TagValueInput.create(
            ProblemReporter.DISCARDING, h.getLevel().registryAccess(), output.buildResult()));
    h.assertTrue(
        restored.locations.entries().equals(npc.locations.entries()),
        "All eight locations must survive entity NBT");
    var plan =
        world.bindLocations(
            new TaskPlan(
                1,
                "Pick up remotely and return to remembered chest",
                "",
                List.of(
                    new TaskPlan.Step(
                        "pickup", "minecraft:diamond", "", 2, -1, -1, "main", 1, List.of(), "回収地点"),
                    new TaskPlan.Step(
                        "deposit", "minecraft:diamond", "", 2, -1, -1, "main", 1, List.of()))));
    h.assertTrue(
        !plan.steps().get(1).location().isEmpty(), "Return destination must bind before leaving");
    var runner = new LocalTaskRunner(plan, world, npc.personality().allowedSkills);
    double[] farthest = {0};
    h.onEachTick(
        () -> {
          skills.tick();
          runner.tick(h.getLevel().getGameTime());
          farthest[0] = Math.max(farthest[0], npc.distanceToSqr(owner));
          h.assertTrue(!runner.needsReplan(), "Round trip failed: " + runner.progress());
          if (runner.complete()) {
            var home =
                (net.minecraft.world.Container)
                    h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(3, 1, 3)));
            var other =
                (net.minecraft.world.Container)
                    h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(24, 1, 5)));
            h.assertTrue(
                contents(home, Items.DIAMOND) == 2 && contents(other, Items.DIAMOND) == 0,
                "Must return to remembered original chest, not the nearest remote chest");
            h.assertTrue(farthest[0] > 16 * 16, "Must actually travel away to collect items");
            runner.close();
            npc.discard();
            h.succeed();
          }
        });
  }

  private static void hazardousStairsScenario(GameTestHelper h) {
    for (int x = 0; x < 7; x++)
      for (int z = 0; z < 7; z++) for (int y = -2; y <= 0; y++) h.setBlock(x, y, z, Blocks.STONE);
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3, 1, 2))));
    var npc = testNpc(h, owner, 3, 1, 3);
    npc.inventory.setItem(NpcInventory.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
    h.setBlock(4, 1, 3, Blocks.LAVA);
    h.setBlock(2, 0, 3, Blocks.BEDROCK);
    h.setBlock(3, 0, 2, Blocks.BEDROCK);
    h.setBlock(3, 0, 4, Blocks.BEDROCK);
    var world = new NpcLocalWorld(npc, new NpcSkills(npc, (text, target) -> {}));
    var step =
        new TaskPlan.Step(
            "collect",
            "minecraft:diamond",
            "minecraft:diamond_ore",
            1,
            -1,
            -1,
            "main",
            1,
            List.of());
    h.assertTrue(
        world.exploration(step, Set.of(), 0, new com.google.gson.JsonArray()).isEmpty(),
        "Must reject lava, unbreakable walls and unsupported drops");
    h.assertTrue(
        h.getBlockState(new BlockPos(3, 0, 3)).is(Blocks.STONE),
        "Safety query must not mutate support blocks");
    npc.discard();
    h.succeed();
  }

  private static void localPolicyScenario(GameTestHelper h) {
    for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.STONE);
    var owner = h.makeMockServerPlayerInLevel();
    owner.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3, 1, 2))));
    var npc = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    npc.initialize(owner, "tortobu");
    npc.setFollowsOwner(false);
    npc.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3, 1, 3))));
    h.getLevel().addFreshEntity(npc);
    npc.inventory.setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
    npc.inventory.setItem(NpcInventory.MAIN_HAND, new ItemStack(Items.DIRT, 2));
    h.setBlock(5, 1, 3, Blocks.COAL_ORE);
    h.setBlock(6, 1, 3, Blocks.COAL_ORE);
    h.setBlock(7, 1, 5, Blocks.CHEST);
    var skills =
        new NpcSkills(
            npc,
            (text, target) -> {
              throw new AssertionError("Local action policy must not speak");
            });
    var plan =
        new TaskPlan(
            1,
            "Collect, store, withdraw and give coal",
            "",
            List.of(
                new TaskPlan.Step(
                    "collect",
                    "minecraft:coal",
                    "minecraft:coal_ore",
                    2,
                    -1,
                    -1,
                    "main",
                    1,
                    List.of()),
                new TaskPlan.Step("deposit", "minecraft:coal", "", 2, -1, -1, "main", 1, List.of()),
                new TaskPlan.Step(
                    "withdraw", "minecraft:coal", "", 1, -1, -1, "main", 1, List.of()),
                new TaskPlan.Step(
                    "give_item", "minecraft:coal", "", 1, -1, -1, "main", 1, List.of())));
    var runner =
        new LocalTaskRunner(plan, new NpcLocalWorld(npc, skills), npc.personality().allowedSkills);
    h.onEachTick(
        () -> {
          skills.tick();
          runner.tick(h.getLevel().getGameTime());
          h.assertTrue(!runner.needsReplan(), "Local policy failed: " + runner.progress());
          if (runner.complete()) {
            var chest =
                (net.minecraft.world.level.block.entity.ChestBlockEntity)
                    h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(7, 1, 5)));
            int stored = 0, carried = 0;
            for (int i = 0; i < chest.getContainerSize(); i++)
              if (chest.getItem(i).is(Items.COAL)) stored += chest.getItem(i).getCount();
            for (int i = 0; i < NpcInventory.SIZE; i++)
              if (npc.inventory.getItem(i).is(Items.COAL))
                carried += npc.inventory.getItem(i).getCount();
            int received = 0;
            for (int i = 0; i < 36; i++)
              if (owner.getInventory().getItem(i).is(Items.COAL))
                received += owner.getInventory().getItem(i).getCount();
            h.assertTrue(
                stored == 1 && carried == 0 && received == 1,
                "Must preserve actual drops through deposit, withdrawal and handover");
            h.assertTrue(
                npc.getMainHandItem().is(Items.WOODEN_PICKAXE),
                "Policy must equip the actual mining tool");
            h.assertTrue(
                h.getBlockState(new BlockPos(5, 1, 3)).isAir()
                    && h.getBlockState(new BlockPos(6, 1, 3)).isAir(),
                "Both ores must be mined");
            runner.close();
            npc.discard();
            h.succeed();
          }
        });
  }

  private static void blockedPlanScenario(GameTestHelper h) {
    for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.STONE);
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3, 1, 2))));
    var npc = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    npc.initialize(owner, "tortobu");
    npc.setFollowsOwner(false);
    npc.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3, 1, 3))));
    h.getLevel().addFreshEntity(npc);
    npc.setInstruction("必要な道具を準備して採掘して");
    int[] plans = {0}, blockedTicks = {0};
    String reason = "鉄以上のツルハシ、または作成できる材料が必要です";
    NpcBrain fixture =
        new NpcBrain() {
          public CompletableFuture<Void> initialize() {
            return CompletableFuture.completedFuture(null);
          }

          public boolean connected() {
            return true;
          }

          public String status() {
            return "GameTest only";
          }

          public void close() {}

          public CompletableFuture<? extends Session> open(
              Personality profile,
              BiFunction<String, JsonObject, CompletionStage<JsonObject>> tools) {
            return CompletableFuture.completedFuture(
                new Session() {
                  public boolean busy() {
                    return false;
                  }

                  public boolean connected() {
                    return true;
                  }

                  public boolean canStart(boolean voice) {
                    return true;
                  }

                  public CompletableFuture<TaskPlan> plan(String text) {
                    plans[0]++;
                    return CompletableFuture.completedFuture(
                        new TaskPlan(1, "Missing prerequisite", reason, List.of()));
                  }

                  public CompletableFuture<NpcBrain.Reply> turn(String text, boolean voice) {
                    return CompletableFuture.completedFuture(new NpcBrain.Reply(
                        text.contains("VERIFIED_WORK_PAUSE") ? "ぼく、" + reason + "のでここで待つね。" : "", false));
                  }

                  public void stop() {}

                  public void close() {}
                });
          }
        };
    var mind = new NpcMind(npc, NpcService.get(h.getLevel().getServer()), fixture);
    var journalRead =
        new java.util.concurrent.atomic.AtomicReference<
            CompletableFuture<dev.avatarcraft.npc.memory.NpcJournal.Page>>();
    h.onEachTick(
        () -> {
          mind.tick();
          if (!npc.aiStatus().contains(reason) || ++blockedTicks[0] < 30) return;
          h.assertTrue(plans[0] == 1, "Blocked plan must not create repeated AI requests");
          h.assertTrue(npc.aiStatus().contains(reason), "UI must expose the real blocking reason");
          var state = com.google.gson.JsonParser.parseString(mind.saveState()).getAsJsonObject();
          h.assertTrue(
              state.get("blocked").getAsString().equals(reason),
              "Block reason must survive saving");
          var service = NpcService.get(h.getLevel().getServer());
          if (journalRead.get() == null) {
            journalRead.set(
                service.readJournal(
                    () -> service.journal.read(owner.getUUID(), npc.getUUID(), "", 30)));
            return;
          }
          if (!journalRead.get().isDone()) return;
          var entries = journalRead.get().join().entries();
          if (entries.stream().noneMatch(e -> e.kind().equals("important_notice"))
              && blockedTicks[0] < 250) {
            journalRead.set(null);
            return;
          }
          h.assertTrue(
              entries.stream().filter(e -> e.kind().equals("planning_start")).count() == 1,
              "Planning start must be journaled");
          h.assertTrue(
              entries.stream()
                      .filter(e -> e.kind().equals("planning_blocked") && e.text().equals(reason))
                      .count()
                  == 1,
              "Blocking reason must be journaled once");
          h.assertTrue(
              entries.stream()
                      .filter(e -> e.kind().equals("important_notice") && e.text().contains(reason))
                      .count()
                  == 1,
              "Blocking reason must be announced once without chat spam");
          mind.close();
          npc.discard();
          h.succeed();
        });
  }

  private static void independentSpeechScenario(GameTestHelper h) {
    for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.STONE);
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3, 1, 2))));
    var npc = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    npc.initialize(owner, "tortobu");
    npc.setFollowsOwner(false);
    var profile = npc.personality();
    profile.workSpeechSeconds = 30;
    npc.setPersonality(profile);
    npc.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3, 1, 3))));
    npc.inventory.setItem(2, new ItemStack(Items.COAL, 1));
    h.getLevel().addFreshEntity(npc);
    npc.setInstruction("40秒待って、石炭を1つ渡して / Wait 40 seconds, then give me one coal");
    int[] planning = {0}, speech = {0};
    var outstandingSpeech = new CompletableFuture<NpcBrain.Reply>();
    NpcBrain fixture =
        new NpcBrain() {
          public CompletableFuture<Void> initialize() {
            return CompletableFuture.completedFuture(null);
          }

          public boolean connected() {
            return true;
          }

          public String status() {
            return "GameTest only";
          }

          public void close() {}

          public CompletableFuture<? extends Session> open(
              Personality p, BiFunction<String, JsonObject, CompletionStage<JsonObject>> tools) {
            h.assertTrue(
                p.allowedSkills.isEmpty(), "Planner and speech must not have action tools");
            return CompletableFuture.completedFuture(
                new Session() {
                  boolean disposed, pending;

                  public boolean busy() {
                    return pending;
                  }

                  public boolean connected() {
                    return !disposed;
                  }

                  public boolean canStart(boolean voice) {
                    return !disposed && !pending;
                  }

                  public CompletableFuture<TaskPlan> plan(String text) {
                    planning[0]++;
                    return CompletableFuture.completedFuture(
                        new TaskPlan(
                            1,
                            "Wait then give coal",
                            "",
                            List.of(
                                new TaskPlan.Step("wait", "", "", 1, -1, -1, "main", 40, List.of()),
                                new TaskPlan.Step(
                                    "give_item",
                                    "minecraft:coal",
                                    "",
                                    1,
                                    -1,
                                    -1,
                                    "main",
                                    1,
                                    List.of()))));
                  }

                  public CompletableFuture<NpcBrain.Reply> turn(String prompt, boolean voice) {
                    h.assertTrue(voice, "Only the planner handles new work; turn is speech-only");
                    if (prompt.contains("VERIFIED_GOAL_COMPLETION"))
                      return CompletableFuture.completedFuture(
                          new NpcBrain.Reply("石炭を渡せたよ！ここで待ってるね。", true));
                    speech[0]++;
                    pending = true;
                    return outstandingSpeech;
                  }

                  public void stop() {
                    disposed = true;
                  }

                  public void close() {
                    disposed = true;
                  }
                });
          }
        };
    var mind = new NpcMind(npc, NpcService.get(h.getLevel().getServer()), fixture);
    h.onEachTick(
        () -> {
          mind.tick();
          if (npc.instruction().isEmpty()) {
            h.assertTrue(
                planning[0] == 1, "Routine local work must not make further planning calls");
            h.assertTrue(
                speech[0] == 1 && !outstandingSpeech.isDone(),
                "Pending speech must not delay local completion");
            int received = 0;
            for (int i = 0; i < 36; i++)
              if (owner.getInventory().getItem(i).is(Items.COAL))
                received += owner.getInventory().getItem(i).getCount();
            h.assertTrue(
                received == 1,
                "Actual handover must complete while the speech provider is stalled");
            mind.close();
            npc.discard();
            h.succeed();
          }
        });
  }

  private static void savedPolicyScenario(GameTestHelper h) {
    for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.STONE);
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3, 1, 2))));
    h.setBlock(4, 1, 3, Blocks.CHEST);
    var chest =
        (net.minecraft.world.level.block.entity.ChestBlockEntity)
            h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(4, 1, 3)));
    chest.setItem(0, new ItemStack(Items.COAL, 2));
    var source = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    source.initialize(owner, "tortobu");
    source.setFollowsOwner(false);
    source.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3, 1, 3))));
    source.inventory.setItem(2, new ItemStack(Items.COAL, 3));
    source.setInstruction("石炭を5つ収納して / Deposit five coal");
    var remaining =
        new TaskPlan(
            1,
            "Remaining three of five coal",
            "",
            List.of(
                new TaskPlan.Step(
                    "deposit", "minecraft:coal", "", 3, -1, -1, "main", 1, List.of())));
    var saved =
        object(
                "instruction",
                source.instruction(),
                "plan",
                dev.avatarcraft.avatar.AvatarArchive.GSON.toJsonTree(remaining),
                "completed",
                false,
                "replans",
                0,
                "context",
                "Two coal already stored",
                "blocked",
                "")
            .toString();
    source.mind().restoreState(saved);
    var output =
        TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
    source.saveWithoutId(output);
    var restored = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.LOAD);
    restored.load(
        TagValueInput.create(
            ProblemReporter.DISCARDING, h.getLevel().registryAccess(), output.buildResult()));
    source.mind().close();
    h.getLevel().addFreshEntity(restored);
    NpcBrain unavailable =
        new NpcBrain() {
          public CompletableFuture<Void> initialize() {
            throw new AssertionError("No network needed to resume");
          }

          public CompletableFuture<? extends Session> open(
              Personality p, BiFunction<String, JsonObject, CompletionStage<JsonObject>> tools) {
            throw new AssertionError("Saved local plan must resume without replanning");
          }

          public boolean connected() {
            return false;
          }

          public String status() {
            return "Offline";
          }

          public void close() {}
        };
    var mind = new NpcMind(restored, NpcService.get(h.getLevel().getServer()), unavailable);
    h.onEachTick(
        () -> {
          mind.tick();
          if (restored.instruction().isEmpty()) {
            int stored = 0;
            for (int i = 0; i < chest.getContainerSize(); i++)
              if (chest.getItem(i).is(Items.COAL)) stored += chest.getItem(i).getCount();
            h.assertTrue(stored == 5, "NBT restore must deposit only the three remaining coal");
            h.assertTrue(
                restored.inventory.getItem(2).isEmpty(),
                "Actual carried items must be consumed by transfer");
            mind.close();
            restored.discard();
            h.succeed();
          }
        });
  }

  private record Step(
      String name, Supplier<CompletionStage<JsonObject>> begin, Consumer<JsonObject> verify) {}

  private static final class Scenario {
    final GameTestHelper h;
    final Queue<Step> steps = new ArrayDeque<>();
    CompanionEntity npc;
    ServerPlayer owner;
    NpcSkills skills;
    Step current;
    CompletableFuture<JsonObject> pending;

    Scenario(GameTestHelper helper) {
      h = helper;
    }

    BlockPos pos(int x, int y, int z) {
      return h.absolutePos(new BlockPos(x, y, z));
    }

    JsonObject xyz(int x, int y, int z) {
      var p = pos(x, y, z);
      return object("x", p.getX(), "y", p.getY(), "z", p.getZ());
    }

    void check(boolean condition, String message) {
      h.assertTrue(condition, message);
    }

    int count(Item item) {
      int amount = 0;
      for (int i = 0; i < NpcInventory.SIZE; i++)
        if (npc.inventory.getItem(i).is(item)) amount += npc.inventory.getItem(i).getCount();
      return amount;
    }

    CompletionStage<JsonObject> now(Runnable action) {
      action.run();
      return CompletableFuture.completedFuture(object("ok", true));
    }

    void add(
        String name, Supplier<CompletionStage<JsonObject>> action, Consumer<JsonObject> verify) {
      steps.add(new Step(name, action, verify));
    }

    void success(JsonObject result) {
      check(result.get("ok").getAsBoolean(), current.name + ": " + result);
    }

    void start() {
      for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.STONE);
      owner = h.makeMockServerPlayerInLevel();
      owner.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
      owner.setPos(Vec3.atBottomCenterOf(pos(3, 1, 3)));
      npc = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
      check(npc != null, "NPC must be registered");
      npc.initialize(owner, "tortobu");
      npc.setPos(Vec3.atBottomCenterOf(pos(3, 1, 3)));
      h.getLevel().addFreshEntity(npc);
      skills =
          new NpcSkills(
              npc,
              (text, listener) ->
                  NpcService.get(h.getLevel().getServer()).say(npc, text, listener));
      check(npc.getMaxHealth() == 20, "Player health must be 20");
      check(
          npc.getAttributeValue(Attributes.ATTACK_DAMAGE) == 1,
          "Player bare-hand damage must be one");
      check(npc.inventory.getContainerSize() == 42, "36 bag, four armor, two hands");
      var stranger = h.makeMockPlayer(GameType.SURVIVAL);
      stranger.setPos(npc.position());
      check(!npc.inventory.stillValid(stranger), "Other players cannot edit inventory");
      check(npc.inventory.stillValid(owner), "Owner can edit nearby inventory");
      check(
          NpcChatRouter.receive(owner, "@tortobu こんにちは") == npc.getId()
              && npc.mind().pendingPlayerChats() == 1,
          "Nearby player chat reaches the addressed NPC");
      var menu = new CompanionMenu(1, owner.getInventory(), npc);
      check(
          menu.slots.get(0).x == 8
              && menu.slots.get(0).y == 101
              && menu.slots.get(42).y == 191
              && menu.slots.get(69).y == 249,
          "NPC inventory is above player inventory and hotbar, aligned with supplied texture");
      check(
          menu.slots.stream().allMatch(s -> s.isActive()),
          "Both inventories must be active together");
      menu.slotsVisible = false;
      check(
          menu.slots.stream().noneMatch(s -> s.isActive()), "Other sections must hide every slot");
      menu.slotsVisible = true;
      owner.getInventory().setItem(9, new ItemStack(Items.IRON_HELMET));
      menu.quickMoveStack(owner, NpcInventory.SIZE);
      check(
          npc.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET),
          "Shift transfer equips actual armor");
      npc.inventory.setItem(40, new ItemStack(Items.SHIELD));
      check(npc.getOffhandItem().is(Items.SHIELD), "Offhand slot must equip real shield");
      var output =
          TagValueOutput.createWithContext(
              ProblemReporter.DISCARDING, h.getLevel().registryAccess());
      npc.inventory.setItem(12, new ItemStack(Items.DIAMOND, 3));
      npc.saveWithoutId(output);
      var tag = output.buildResult();
      tag.putInt("foodLevel", 10);
      var restored = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.LOAD);
      restored.load(
          TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag));
      check(
          restored.ownedBy(owner)
              && restored.avatarId().equals("tortobu")
              && restored.foodLevel() == 10,
          "Owner, avatar and hunger must survive NBT save/load");
      check(
          restored.inventory.getItem(12).getCount() == 3
              && restored.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET)
              && restored.getOffhandItem().is(Items.SHIELD),
          "Carried items and all equipment survive NBT");
      restored.discard();
      npc.load(
          TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag));
      npc.inventory.setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
      npc.inventory.setItem(NpcInventory.MAIN_HAND, new ItemStack(Items.DIRT, 5));
      add(
          "equip mining tool",
          () -> skills.execute("equip", object("slot", 0, "hand", "main")),
          r -> {
            success(r);
            check(
                npc.getMainHandItem().is(Items.WOODEN_PICKAXE)
                    && npc.inventory.getItem(0).is(Items.DIRT)
                    && npc.inventory.getItem(0).getCount() == 5,
                "Swap must preserve the old main-hand stack");
          });
      add(
          "equip offhand",
          () -> {
            npc.inventory.setItem(10, new ItemStack(Items.TORCH, 12));
            return skills.execute("equip", object("slot", 10, "hand", "off"));
          },
          r -> {
            success(r);
            check(
                npc.getOffhandItem().is(Items.TORCH)
                    && npc.getOffhandItem().getCount() == 12
                    && npc.inventory.getItem(10).is(Items.SHIELD),
                "Offhand swap preserves both stacks");
          });
      add(
          "put away offhand",
          () -> skills.execute("equip", object("slot", 11, "hand", "off")),
          r -> {
            success(r);
            check(
                npc.getOffhandItem().isEmpty() && npc.inventory.getItem(11).getCount() == 12,
                "An empty bag slot unequips the hand without deletion");
          });
      add(
          "reject armor as equip source",
          () -> skills.execute("equip", object("slot", 36, "hand", "main")),
          r -> {
            check(
                !r.get("ok").getAsBoolean() && npc.getMainHandItem().is(Items.WOODEN_PICKAXE),
                "Armor cannot be swapped by bag skill");
          });
      add(
          "reject invalid hand",
          () -> skills.execute("equip", object("slot", 0, "hand", "left")),
          r -> {
            check(!r.get("ok").getAsBoolean(), "Only main and off are accepted");
          });
      h.setBlock(5, 1, 3, Blocks.BEDROCK);
      add(
          "unbreakable block",
          () -> skills.execute("dig", xyz(5, 1, 3)),
          r -> {
            check(!r.get("ok").getAsBoolean(), "Bedrock must not break");
            h.setBlock(5, 1, 3, Blocks.STONE);
          });
      add(
          "normal mining",
          () -> skills.execute("dig", xyz(5, 1, 3)),
          r -> {
            success(r);
            check(h.getLevel().getBlockState(pos(5, 1, 3)).isAir(), "Stone must actually be mined");
            check(npc.getMainHandItem().getDamageValue() > 0, "Real tool durability must decrease");
          });
      add(
          "block placement",
          () -> {
            npc.inventory.setItem(1, new ItemStack(Items.COBBLESTONE, 2));
            var args = xyz(5, 1, 4);
            args.addProperty("slot", 1);
            return skills.execute("place", args);
          },
          r -> {
            success(r);
            check(
                h.getLevel().getBlockState(pos(5, 1, 4)).is(Blocks.COBBLESTONE),
                "Real block must be placed");
            check(
                npc.inventory.getItem(1).getCount() == 1,
                "Placement must consume exactly one item");
          });
      add(
          "container transfer",
          () -> {
            h.setBlock(3, 1, 5, Blocks.CHEST);
            npc.inventory.setItem(2, new ItemStack(Items.DIRT, 6));
            var args = xyz(3, 1, 5);
            args.addProperty("slot", 2);
            args.addProperty("count", 4);
            return skills.execute("deposit", args);
          },
          r -> {
            success(r);
            var container = (Container) h.getLevel().getBlockEntity(pos(3, 1, 5));
            check(
                container.getItem(0).getCount() == 4 && npc.inventory.getItem(2).getCount() == 2,
                "Transfer must conserve item counts");
          });
      add(
          "find chest contents",
          () -> skills.execute("find_chests", object("radius", 4)),
          r -> {
            success(r);
            check(
                r.getAsJsonArray("chests").asList().stream()
                    .anyMatch(
                        e ->
                            e.getAsJsonObject().getAsJsonArray("items").asList().stream()
                                .anyMatch(
                                    i ->
                                        i.getAsJsonObject()
                                                .get("item")
                                                .getAsString()
                                                .equals("minecraft:dirt")
                                            && i.getAsJsonObject().get("count").getAsInt() == 4)),
                "Inspect actual chest slots before extracting");
          });
      add(
          "withdraw from chest",
          () -> {
            var args = xyz(3, 1, 5);
            args.addProperty("slot", 0);
            args.addProperty("count", 3);
            return skills.execute("withdraw", args);
          },
          r -> {
            success(r);
            check(
                r.get("moved").getAsInt() == 3
                    && ((Container) h.getLevel().getBlockEntity(pos(3, 1, 5))).getItem(0).getCount()
                        == 1,
                "Withdrawal consumes exactly the inserted count");
          });
      add(
          "blocked chest",
          () -> {
            h.setBlock(3, 2, 5, Blocks.STONE);
            var args = xyz(3, 1, 5);
            args.addProperty("slot", 0);
            args.addProperty("count", 1);
            return skills.execute("withdraw", args);
          },
          r -> {
            check(
                !r.get("ok").getAsBoolean()
                    && ((Container) h.getLevel().getBlockEntity(pos(3, 1, 5))).getItem(0).getCount()
                        == 1,
                "Blocked chest must not lose items");
            h.setBlock(3, 2, 5, Blocks.AIR);
          });
      add(
          "give item to owner",
          () -> {
            npc.inventory.setItem(13, new ItemStack(Items.GOLD_INGOT, 5));
            return skills.execute(
                "give_item", object("entity_id", owner.getId(), "slot", 13, "count", 2));
          },
          r -> {
            success(r);
            check(
                owner.getInventory().countItem(Items.GOLD_INGOT) == 2
                    && npc.inventory.getItem(13).getCount() == 3,
                "Giving to player conserves both inventories");
          });
      add(
          "reject distant recipient",
          () -> {
            owner.setPos(npc.position().add(6, 0, 0));
            return skills.execute(
                "give_item", object("entity_id", owner.getId(), "slot", 13, "count", 2));
          },
          r -> {
            check(
                !r.get("ok").getAsBoolean() && npc.inventory.getItem(13).getCount() == 3,
                "Out-of-range gift leaves source intact");
            owner.setPos(npc.position());
          });
      var recipient = new CompanionEntity[1];
      add(
          "give partial stack to NPC",
          () -> {
            recipient[0] = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
            recipient[0].initialize(owner, "tortobu");
            recipient[0].setFollowsOwner(false);
            recipient[0].setPos(npc.position().add(1, 0, 0));
            h.getLevel().addFreshEntity(recipient[0]);
            for (int i = 0; i < 36; i++)
              recipient[0].inventory.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            recipient[0].inventory.setItem(5, new ItemStack(Items.GOLD_INGOT, 63));
            return skills.execute(
                "give_item", object("entity_id", recipient[0].getId(), "slot", 13, "count", 3));
          },
          r -> {
            success(r);
            check(
                r.get("moved").getAsInt() == 1
                    && recipient[0].inventory.getItem(5).getCount() == 64
                    && npc.inventory.getItem(13).getCount() == 2,
                "Only accepted part is removed; overflow stays in sender bag");
          });
      add(
          "full NPC rejects gift",
          () ->
              skills.execute(
                  "give_item", object("entity_id", recipient[0].getId(), "slot", 13, "count", 2)),
          r -> {
            check(
                !r.get("ok").getAsBoolean()
                    && r.get("moved").getAsInt() == 0
                    && npc.inventory.getItem(13).getCount() == 2,
                "Full recipient must not delete or drop a gift");
            recipient[0].discard();
          });
      var savedBag = new ArrayList<ItemStack>();
      add(
          "full bag rejects chest withdrawal",
          () -> {
            for (int i = 0; i < 36; i++) {
              savedBag.add(npc.inventory.getItem(i).copy());
              npc.inventory.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            }
            var args = xyz(3, 1, 5);
            args.addProperty("slot", 0);
            args.addProperty("count", 1);
            return skills.execute("withdraw", args);
          },
          r -> {
            check(
                !r.get("ok").getAsBoolean()
                    && ((Container) h.getLevel().getBlockEntity(pos(3, 1, 5))).getItem(0).getCount()
                        == 1,
                "Full bag leaves chest contents unchanged");
            for (int i = 0; i < 36; i++) npc.inventory.setItem(i, savedBag.get(i));
          });
      add(
          "crafting",
          () -> {
            npc.inventory.setItem(3, new ItemStack(Items.OAK_LOG));
            return skills.execute("craft", object("recipe", "minecraft:oak_planks"));
          },
          r -> {
            success(r);
            check(
                count(Items.OAK_LOG) == 0 && count(Items.OAK_PLANKS) == 4,
                "Registered crafting consumes log and produces four planks");
          });
      add(
          "missing materials",
          () -> skills.execute("craft", object("recipe", "minecraft:diamond_block")),
          r ->
              check(
                  !r.get("ok").getAsBoolean() && count(Items.DIAMOND) == 3,
                  "Failed crafting must not consume any materials"));
      int[] soulstoneMaterials = new int[2];
      add(
          "craft soulstone",
          () -> {
            h.setBlock(2, 1, 3, Blocks.CRAFTING_TABLE);
            npc.inventory.setItem(4, new ItemStack(Items.IRON_INGOT, 4));
            npc.inventory.setItem(5, new ItemStack(Items.GOLD_INGOT, 5));
            soulstoneMaterials[0] = count(Items.IRON_INGOT);
            soulstoneMaterials[1] = count(Items.GOLD_INGOT);
            return skills.execute("craft", object("recipe", "bep_3davater_companion:soulstone"));
          },
          r -> {
            success(r);
            check(count(bep_3davater_companion.SOULSTONE.get()) == 1, "Soulstone recipe produces one item");
            check(
                count(Items.IRON_INGOT) == soulstoneMaterials[0] - 4
                    && count(Items.GOLD_INGOT) == soulstoneMaterials[1] - 5,
                "Soulstone consumes four iron ingots and five gold ingots");
          });
      add(
          "craft journal",
          () -> {
            npc.inventory.setItem(4, new ItemStack(Items.IRON_INGOT, 8));
            npc.inventory.setItem(5, new ItemStack(Items.BOOK));
            return skills.execute("craft", object("recipe", "bep_3davater_companion:memory_journal"));
          },
          r -> {
            success(r);
            check(count(bep_3davater_companion.MEMORY_JOURNAL.get()) == 1, "Journal recipe must be craftable");
          });
      add(
          "eat actual food",
          () -> {
            npc.inventory.setItem(9, new ItemStack(Items.APPLE, 2));
            return skills.execute("eat", object("slot", 9));
          },
          this::success);
      add(
          "cannot interrupt consumption with equip",
          () -> skills.execute("equip", object("slot", 0, "hand", "main")),
          r ->
              check(
                  !r.get("ok").getAsBoolean() && npc.isUsingItem(),
                  "Equip must not interrupt eating"));
      add(
          "finish eating",
          () -> skills.execute("wait", object("seconds", 2)),
          r -> {
            success(r);
            check(
                npc.foodLevel() == 14 && count(Items.APPLE) == 1,
                "Eating raises real hunger by four and consumes one apple");
          });
      add(
          "soulstone summons standard avatar",
          () ->
              now(
                  () -> {
                    owner.setItemInHand(
                        InteractionHand.MAIN_HAND, new ItemStack(bep_3davater_companion.SOULSTONE.get()));
                    var support = pos(8, 0, 8);
                    var hit =
                        new BlockHitResult(
                            Vec3.atCenterOf(support).add(0, .5, 0), Direction.UP, support, false);
                    var result =
                        owner
                            .getMainHandItem()
                            .useOn(new UseOnContext(owner, InteractionHand.MAIN_HAND, hit));
                    check(
                        result.consumesAction() && owner.getMainHandItem().isEmpty(),
                        "Survival summoning consumes one charm");
                    check(
                        h
                            .getLevel()
                            .getEntitiesOfClass(
                                CompanionEntity.class,
                                new AABB(
                                    Vec3.atLowerCornerOf(pos(7, 1, 7)),
                                    Vec3.atLowerCornerOf(pos(10, 4, 10))))
                            .stream()
                            .anyMatch(e -> e.avatarId().equals("tortobu") && e.ownedBy(owner)),
                        "Summoned NPC uses tortobu and owner UUID");
                  }),
          this::success);
      add(
          "persistent conversation journal",
          () -> {
            var service = NpcService.get(h.getLevel().getServer());
            service.say(npc, "検証の会話です", -1);
            return service.readJournal(
                () ->
                    object(
                        "ok",
                        service
                            .journal
                            .read(owner.getUUID(), npc.getUUID(), "", 12)
                            .entries()
                            .stream()
                            .anyMatch(
                                e -> e.kind().equals("speech") && e.text().equals("検証の会話です"))));
          },
          this::success);
      add(
          "walking navigation",
          () -> {
            h.setBlock(5, 1, 4, Blocks.AIR);
            h.setBlock(3, 1, 5, Blocks.AIR);
            return skills.execute("move_to", xyz(7, 1, 3));
          },
          r -> {
            success(r);
            check(
                npc.distanceToSqr(Vec3.atCenterOf(pos(7, 1, 3))) <= 2.3,
                "Movement must arrive using navigation");
          });
      add(
          "owner range protection",
          () ->
              now(
                  () -> {
                    owner.setPos(npc.position().add(20, 0, 0));
                    check(
                        !npc.inventory.stillValid(owner),
                        "Owner inventory closes when too far away");
                  }),
          this::success);
      add(
          "death preserves equipment",
          () ->
              now(
                  () -> {
                    var victim =
                        bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
                    victim.initialize(owner, "tortobu");
                    victim.setPos(Vec3.atBottomCenterOf(pos(9, 1, 1)));
                    var pick = new ItemStack(Items.WOODEN_PICKAXE);
                    pick.setDamageValue(5);
                    var helmet = new ItemStack(Items.IRON_HELMET);
                    helmet.setDamageValue(7);
                    var shield = new ItemStack(Items.SHIELD);
                    shield.setDamageValue(9);
                    victim.inventory.setItem(NpcInventory.MAIN_HAND, pick);
                    victim.inventory.setItem(36, helmet);
                    victim.inventory.setItem(40, shield);
                    victim.inventory.setItem(1, new ItemStack(Items.DIRT, 5));
                    h.getLevel().addFreshEntity(victim);
                    victim.hurtServer(h.getLevel(), victim.damageSources().genericKill(), 999);
                    var drops =
                        h.getLevel()
                            .getEntitiesOfClass(
                                net.minecraft.world.entity.item.ItemEntity.class,
                                victim.getBoundingBox().inflate(1.5));
                    for (var expected : new ItemStack[] {pick, helmet, shield})
                      check(
                          drops.stream()
                              .anyMatch(
                                  e ->
                                      e.getItem().is(expected.getItem())
                                          && e.getItem().getDamageValue()
                                              == expected.getDamageValue()),
                          "Non-player death must preserve equipment and durability");
                    check(
                        drops.stream()
                                .filter(e -> e.getItem().is(Items.DIRT))
                                .mapToInt(e -> e.getItem().getCount())
                                .sum()
                            == 5,
                        "Death must also drop carried items");
                  }),
          this::success);
      h.onEachTick(this::tick);
    }

    void tick() {
      try {
        skills.tick();
        if (pending != null) {
          if (!pending.isDone()) return;
          var value = pending.join();
          current.verify.accept(value);
          System.getLogger("bep_3davater_loader NPC QA")
              .log(System.Logger.Level.INFO, "PASS: " + current.name);
          pending = null;
        }
        if (steps.isEmpty()) {
          skills.cancel("QA finished");
          h.succeed();
          return;
        }
        current = steps.remove();
        pending = current.begin.get().toCompletableFuture();
      } catch (Throwable e) {
        h.fail((current == null ? "setup" : current.name) + ": " + e);
      }
    }
  }

  private NpcGameTests() {}
}

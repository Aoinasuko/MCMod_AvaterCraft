package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.*;
import dev.avatarcraft.npc.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

final class NpcRuntimeFixGameTests {
  static void register(RegisterGameTestsEvent event) {
    var env =
        event.registerEnvironment(
            bep_3davater_companion.id("runtime_fixes_qa"), new TestEnvironmentDefinition.AllOf());
    test(event, env, "immobile_walk_ends_within_21_seconds", NpcRuntimeFixGameTests::stalled);
    test(event, env, "partial_storage_switches_to_other_chest", NpcRuntimeFixGameTests::storage);
    test(event, env, "smelting_refuels_without_burning_tools", h -> smelt(h, false));
    test(event, env, "direct_smelting_converts_carried_logs", h -> smelt(h, false, true));
    test(event, env, "smelting_resumes_underfuelled_owned_batch", h -> smelt(h, true));
    test(
        event,
        env,
        "insufficient_fuel_fails_before_consuming_input",
        NpcRuntimeFixGameTests::fuelMissing);
    test(event, env, "acquisition_prepares_remaining_fuel", NpcRuntimeFixGameTests::acquireFuel);
    test(event, env, "stale_planning_failure_does_not_close_new_session", h -> sessions(h, true));
    test(event, env, "stale_planning_success_does_not_replace_new_plan", h -> sessions(h, false));
    test(
        event,
        env,
        "work_route_uses_door_detour_without_breaking_wall",
        NpcRuntimeFixGameTests::doorDetour);
  }

  private static void test(
      RegisterGameTestsEvent event,
      Holder<TestEnvironmentDefinition<?>> env,
      String name,
      Consumer<GameTestHelper> body) {
    event.registerTest(
        bep_3davater_companion.id(name),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(env, bep_3davater_companion.id("qa_empty"), 2400, 1, true)) {
          public void run(GameTestHelper h) {
            body.accept(h);
          }
        });
  }

  private static CompanionEntity npc(GameTestHelper h) {
    for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.BEDROCK);
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 1))));
    var n = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    n.initialize(owner, "tortobu");
    n.setFollowsOwner(false);
    n.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3, 1, 4))));
    h.getLevel().addFreshEntity(n);
    return n;
  }

  private static JsonObject at(GameTestHelper h, int x, int y, int z) {
    var p = h.absolutePos(new BlockPos(x, y, z));
    return object("x", p.getX(), "y", p.getY(), "z", p.getZ());
  }

  private static int count(CompanionEntity n, Item item) {
    int amount = 0;
    for (int i = 0; i < NpcInventory.SIZE; i++)
      if (n.inventory.getItem(i).is(item)) amount += n.inventory.getItem(i).getCount();
    return amount;
  }

  private static void stalled(GameTestHelper h) {
    var n = npc(h);
    n.setNoAi(true);
    var s = new NpcSkills(n, (a, b) -> {});
    var result = s.execute("move_to", at(h, 9, 1, 4)).toCompletableFuture();
    int[] ticks = {0};
    h.onEachTick(
        () -> {
          s.tick();
          ticks[0]++;
          h.assertTrue(ticks[0] < 440, "No five-minute movement wait");
          if (!result.isDone()) return;
          h.assertTrue(
              !result.join().get("ok").getAsBoolean()
                  && result.join().get("error").getAsString().contains("MOVEMENT_STALLED"),
              "Physical movement watchdog: " + result.join());
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static void storage(GameTestHelper h) {
    var n = npc(h);
    var s = new NpcSkills(n, (a, b) -> {});
    h.setBlock(4, 1, 4, Blocks.CHEST);
    h.setBlock(3, 1, 7, Blocks.CHEST);
    var first =
        (ChestBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(4, 1, 4)));
    var second =
        (ChestBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(3, 1, 7)));
    for (int i = 0; i < 27; i++) first.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
    first.setItem(26, new ItemStack(Items.SPRUCE_LOG, 32));
    n.inventory.setItem(0, new ItemStack(Items.SPRUCE_LOG, 64));
    var p = h.absolutePos(new BlockPos(4, 1, 4));
    var step =
        new TaskPlan.Step(
            "deposit",
            "minecraft:spruce_log",
            "",
            64,
            -1,
            -1,
            "main",
            1,
            List.of((double) p.getX(), (double) p.getY(), (double) p.getZ()));
    var runner =
        new LocalTaskRunner(
            new TaskPlan(1, "Store logs", "", List.of(step)),
            new NpcLocalWorld(n, s),
            n.personality().allowedSkills);
    int[] tick = {0};
    h.onEachTick(
        () -> {
          s.tick();
          runner.tick(tick[0]++);
          h.assertFalse(
              runner.needsReplan(), "Local storage must not require AI: " + runner.problem());
          if (!runner.complete()) return;
          h.assertTrue(
              count(n, Items.SPRUCE_LOG) == 0
                  && first.getItem(26).getCount() == 64
                  && second.getItem(0).getCount() == 32,
              "Conserve items across partial transfer and alternate chest");
          runner.close();
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static void smelt(GameTestHelper h, boolean resume) {
    smelt(h, resume, false);
  }

  private static void smelt(GameTestHelper h, boolean resume, boolean logs) {
    var n = npc(h);
    var s = new NpcSkills(n, (a, b) -> {});
    h.setBlock(5, 1, 4, Blocks.FURNACE);
    var p = h.absolutePos(new BlockPos(5, 1, 4));
    var f = (FurnaceBlockEntity) h.getLevel().getBlockEntity(p);
    n.inventory.setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
    if (logs) n.inventory.setItem(2, new ItemStack(Items.SPRUCE_LOG, 2));
    else {
      n.inventory.setItem(2, new ItemStack(Items.SPRUCE_PLANKS));
      n.inventory.setItem(3, new ItemStack(Items.SPRUCE_PLANKS, 2));
    }
    if (resume) {
      f.setItem(0, new ItemStack(Items.RAW_IRON, 2));
      f.setItem(2, new ItemStack(Items.IRON_INGOT));
      NpcWorkData.get(h.getLevel()).furnaces.put(NpcSmelting.key(h.getLevel(), p), n.getUUID());
    } else n.inventory.setItem(1, new ItemStack(Items.RAW_IRON, 3));
    var args = at(h, 5, 1, 4);
    args.addProperty("item", "minecraft:iron_ingot");
    args.addProperty("count", 3);
    var result = s.execute("smelt", args).toCompletableFuture();
    h.onEachTick(
        () -> {
          s.tick();
          if (!result.isDone()) return;
          h.assertTrue(
              result.join().get("ok").getAsBoolean(),
              "Native furnace completion: " + result.join());
          h.assertTrue(
              count(n, Items.IRON_INGOT) == 3 && count(n, Items.WOODEN_PICKAXE) == 1,
              "Produce actual ingots while preserving tools");
          h.assertTrue(
              !NpcWorkData.get(h.getLevel()).furnaces.containsKey(NpcSmelting.key(h.getLevel(), p)),
              "Release completed batch lease");
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static void fuelMissing(GameTestHelper h) {
    var n = npc(h);
    var s = new NpcSkills(n, (a, b) -> {});
    h.setBlock(5, 1, 4, Blocks.FURNACE);
    n.inventory.setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
    n.inventory.setItem(1, new ItemStack(Items.RAW_GOLD, 3));
    n.inventory.setItem(2, new ItemStack(Items.STICK));
    var args = at(h, 5, 1, 4);
    args.addProperty("item", "minecraft:gold_ingot");
    args.addProperty("count", 3);
    var result = s.execute("smelt", args).toCompletableFuture();
    h.onEachTick(
        () -> {
          s.tick();
          if (!result.isDone()) return;
          h.assertTrue(
              !result.join().get("ok").getAsBoolean()
                  && result.join().get("error").getAsString().contains("FUEL_INSUFFICIENT"),
              "Fail immediately with fuel deficit: " + result.join());
          h.assertTrue(
              count(n, Items.RAW_GOLD) == 3
                  && count(n, Items.WOODEN_PICKAXE) == 1
                  && count(n, Items.STICK) == 1,
              "No input or fuel consumed on preflight failure");
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static void acquireFuel(GameTestHelper h) {
    var n = npc(h);
    var s = new NpcSkills(n, (a, b) -> {});
    h.setBlock(5, 1, 4, Blocks.FURNACE);
    n.inventory.setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
    n.inventory.setItem(1, new ItemStack(Items.RAW_IRON, 3));
    n.inventory.setItem(2, new ItemStack(Items.SPRUCE_PLANKS));
    n.inventory.setItem(3, new ItemStack(Items.SPRUCE_LOG));
    var result =
        s.execute("acquire", object("item", "minecraft:iron_ingot", "count", 3))
            .toCompletableFuture();
    h.onEachTick(
        () -> {
          s.tick();
          if (!result.isDone()) return;
          h.assertTrue(
              result.join().get("ok").getAsBoolean(),
              "Prepare missing fuel, then actually smelt: " + result.join());
          h.assertTrue(
              count(n, Items.IRON_INGOT) >= 3 && count(n, Items.WOODEN_PICKAXE) == 1,
              "Verified crafted fuel and smelted output");
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static void sessions(GameTestHelper h, boolean failure) {
    var n = npc(h);
    n.setInstruction("ここで1秒待機して");
    class FixtureSession implements NpcBrain.Session {
      boolean connected = true, closed, asked;
      final CompletableFuture<TaskPlan> reply = new CompletableFuture<>();

      public boolean busy() {
        return asked && !reply.isDone();
      }

      public boolean connected() {
        return connected && !closed;
      }

      public boolean canStart(boolean voice) {
        return !closed;
      }

      public CompletableFuture<NpcBrain.Reply> turn(String p, boolean voice) {
        return new CompletableFuture<>();
      }

      public CompletableFuture<TaskPlan> plan(String p) {
        h.assertTrue(
            p.contains("Navigation:") && p.contains("usable doors"),
            "Planner must prefer walking and doors over wall shortcuts");
        asked = true;
        return reply;
      }

      public void stop() {
        closed = true;
      }

      public void close() {
        closed = true;
      }
    }
    var all = new ArrayList<FixtureSession>();
    NpcBrain brain =
        new NpcBrain() {
          public CompletableFuture<Void> initialize() {
            return CompletableFuture.completedFuture(null);
          }

          public boolean connected() {
            return true;
          }

          public String status() {
            return "Isolated session race";
          }

          public void close() {}

          public CompletableFuture<? extends NpcBrain.Session> open(
              Personality p, BiFunction<String, JsonObject, CompletionStage<JsonObject>> tools) {
            var session = new FixtureSession();
            all.add(session);
            return CompletableFuture.completedFuture(session);
          }
        };
    var mind = new NpcMind(n, NpcService.get(h.getLevel().getServer()), brain);
    int[] stage = {0}, ticks = {0};
    h.onEachTick(
        () -> {
          mind.tick();
          ticks[0]++;
          if (all.isEmpty() || !all.getFirst().asked) return;
          if (stage[0] == 0) {
            all.getFirst().connected = false;
            stage[0] = 1;
            return;
          }
          if (all.size() < 2 || !all.get(1).asked) return;
          if (stage[0] == 1) {
            if (failure)
              all.getFirst()
                  .reply
                  .completeExceptionally(new IllegalStateException("Late disconnected request"));
            else
              all.getFirst()
                  .reply
                  .complete(new TaskPlan(1, "Stale plan", "Must never apply", List.of()));
            stage[0] = 2;
            ticks[0] = 0;
            return;
          }
          if (stage[0] == 2 && ticks[0] > 30) {
            h.assertFalse(all.get(1).closed, "Late old response must not close active session");
            all.get(1)
                .reply
                .complete(
                    new TaskPlan(
                        1,
                        "Wait",
                        "",
                        List.of(
                            new TaskPlan.Step("wait", "", "", 1, -1, -1, "main", 1, List.of()))));
            stage[0] = 3;
            return;
          }
          if (stage[0] == 3 && n.instruction().isBlank()) {
            h.assertTrue(
                n.executionMemory.hasCompletedGoal(), "Only new verified plan completes goal");
            mind.close();
            n.discard();
            h.succeed();
          }
        });
  }

  private static void doorDetour(GameTestHelper h) {
    var n = npc(h);
    var s = new NpcSkills(n, (a, b) -> {});
    for (int z = 0; z < 12; z++)
      for (int y = 1; y <= 3; y++) h.setBlock(6, y, z, Blocks.COBBLESTONE);
    var door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
    h.setBlock(new BlockPos(6, 1, 8), door);
    h.setBlock(new BlockPos(6, 2, 8), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    n.inventory.setItem(41, new ItemStack(Items.IRON_PICKAXE));
    n.inventory.setItem(0, new ItemStack(Items.DIRT, 16));
    var args = at(h, 9, 1, 4);
    args.addProperty("goal", "reach");
    args.addProperty("mode", "both");
    args.addProperty("max_blocks", 32);
    var result = s.execute("work_route", args).toCompletableFuture();
    boolean[] opened = {false};
    h.onEachTick(
        () -> {
          s.tick();
          opened[0] |=
              h.getLevel()
                  .getBlockState(h.absolutePos(new BlockPos(6, 1, 8)))
                  .getValue(DoorBlock.OPEN);
          for (int z = 0; z < 12; z++)
            if (z != 8)
              for (int y = 1; y <= 3; y++)
                h.assertTrue(
                    h.getLevel()
                        .getBlockState(h.absolutePos(new BlockPos(6, y, z)))
                        .is(Blocks.COBBLESTONE),
                    "Door detour must preserve wall");
          if (!result.isDone()) return;
          h.assertTrue(
              result.join().get("ok").getAsBoolean(),
              "Walk through offset doorway: " + result.join());
          h.assertTrue(opened[0] && count(n, Items.DIRT) == 16, "Use door instead of edits");
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }
}

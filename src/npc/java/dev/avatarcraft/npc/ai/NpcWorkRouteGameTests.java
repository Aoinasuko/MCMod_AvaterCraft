package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.*;
import dev.avatarcraft.npc.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** Physical edits, survival inventory consumption and actual arrival in isolated worlds. */
final class NpcWorkRouteGameTests {
  static void register(RegisterGameTestsEvent event) {
    var env =
        event.registerEnvironment(
            bep_3davater_companion.id("work_route_qa"), new TestEnvironmentDefinition.AllOf());
    test(event, env, "logs_through_dense_leaf_shell", 4000, NpcWorkRouteGameTests::leaves);
    test(event, env, "canopy_enclosed_trunk_from_the_ground", 4000, NpcWorkRouteGameTests::canopy);
    test(event, env, "tall_tree_builds_walkable_stairs", 7000, NpcWorkRouteGameTests::tallTree);
    test(event, env, "dig_through_wall_to_owner", 4000, NpcWorkRouteGameTests::tunnel);
    test(
        event,
        env,
        "dig_route_prepares_missing_tool_from_chest",
        4000,
        NpcWorkRouteGameTests::toolPreparation);
    test(event, env, "build_bridge_to_owner", 4000, h -> bridge(h, false, false));
    test(event, env, "build_bridge_above_water", 4000, h -> bridge(h, true, false));
    test(
        event,
        env,
        "cancelled_bridge_keeps_only_completed_edits",
        4000,
        h -> bridge(h, false, true));
    test(
        event,
        env,
        "work_route_preserves_containers_and_budget",
        1000,
        NpcWorkRouteGameTests::blocked);
    test(event, env, "work_route_respects_edit_limit", 1000, NpcWorkRouteGameTests::editLimit);
    test(
        event,
        env,
        "bridge_gathers_real_missing_materials",
        5000,
        NpcWorkRouteGameTests::bridgeSupplies);
  }

  private static void editLimit(GameTestHelper h) {
    corridor(h);
    h.setBlock(8, 1, 6, Blocks.STONE);
    h.setBlock(8, 2, 6, Blocks.STONE);
    var n = npc(h, 3, 1, 6);
    n.inventory.setItem(0, new ItemStack(Items.IRON_PICKAXE));
    var s = skills(n);
    var future = route(h, n, s, "dig", 1, 1);
    h.onEachTick(
        () -> {
          s.tick();
          if (!future.isDone()) return;
          h.assertFalse(
              future.join().get("ok").getAsBoolean(), "Two edits cannot fit a one-block budget");
          h.assertTrue(
              future.join().get("dug").getAsInt() <= 1, "Actual edits must respect the limit");
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static void bridgeSupplies(GameTestHelper h) {
    ground(h);
    for (int x = 5; x <= 10; x++) for (int z = 0; z < 20; z++) h.setBlock(x, 0, z, Blocks.AIR);
    for (int x = 1; x <= 3; x++) for (int z = 8; z <= 10; z++) h.setBlock(x, 1, z, Blocks.DIRT);
    var n = npc(h, 3, 1, 6);
    n.inventory.setItem(0, new ItemStack(Items.IRON_SHOVEL));
    var s = skills(n);
    var p = h.absolutePos(new BlockPos(15, 1, 6));
    var future =
        s.execute(
                "work_route",
                object(
                    "x",
                    p.getX(),
                    "y",
                    p.getY(),
                    "z",
                    p.getZ(),
                    "goal",
                    "bridge",
                    "mode",
                    "both",
                    "max_blocks",
                    32))
            .toCompletableFuture();
    h.onEachTick(
        () -> {
          s.tick();
          if (!future.isDone()) return;
          h.assertTrue(
              future.join().get("ok").getAsBoolean(),
              "Gathered dirt must build the bridge: " + future.join());
          int bridged = 0, source = 0;
          for (int x = 5; x <= 10; x++)
            for (int z = 0; z < 20; z++)
              if (h.getLevel().getBlockState(h.absolutePos(new BlockPos(x, 0, z))).is(Blocks.DIRT))
                bridged++;
          for (int x = 1; x <= 3; x++)
            for (int z = 8; z <= 10; z++)
              if (h.getLevel().getBlockState(h.absolutePos(new BlockPos(x, 1, z))).is(Blocks.DIRT))
                source++;
          h.assertTrue(
              bridged >= 6 && source < 9, "Bridge blocks must come from actual harvested terrain");
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static void test(
      RegisterGameTestsEvent event,
      Holder<TestEnvironmentDefinition<?>> env,
      String name,
      int ticks,
      Consumer<GameTestHelper> body) {
    event.registerTest(
        bep_3davater_companion.id(name),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(env, bep_3davater_companion.id("qa_large"), ticks, 1, true)) {
          public void run(GameTestHelper h) {
            body.accept(h);
          }
        });
  }

  private static CompanionEntity npc(GameTestHelper h, int x, int y, int z) {
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, y, 1))));
    var n = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    n.initialize(owner, "tortobu");
    n.setFollowsOwner(false);
    n.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(x, y, z))));
    h.getLevel().addFreshEntity(n);
    return n;
  }

  private static void ground(GameTestHelper h) {
    for (int x = 0; x < 20; x++) for (int z = 0; z < 20; z++) h.setBlock(x, 0, z, Blocks.BEDROCK);
  }

  private static int count(CompanionEntity n, Item item) {
    int sum = 0;
    for (int i = 0; i < NpcInventory.SIZE; i++)
      if (n.inventory.getItem(i).is(item)) sum += n.inventory.getItem(i).getCount();
    return sum;
  }

  private static NpcSkills skills(CompanionEntity n) {
    return new NpcSkills(n, (s, id) -> {});
  }

  private static LocalTaskRunner collect(CompanionEntity n, NpcSkills s, int count) {
    var step =
        new TaskPlan.Step(
            "collect", "#minecraft:logs", "#minecraft:logs", count, -1, -1, "main", 10, List.of());
    return new LocalTaskRunner(
        new TaskPlan(1, "get logs", "", List.of(step)),
        new NpcLocalWorld(n, s),
        n.personality().allowedSkills);
  }

  /**
   * A natural tree: the canopy wraps the trunk above head height. Without scaffolding, a player
   * cuts the leaves in the way from the ground and reaches the logs from eye height.
   */
  private static void canopy(GameTestHelper h) {
    ground(h);
    var n = npc(h, 3, 1, 6);
    n.inventory.setItem(0, new ItemStack(Items.IRON_AXE));
    var leaf = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
    for (int x = 6; x <= 8; x++)
      for (int y = 3; y <= 7; y++)
        for (int z = 5; z <= 7; z++)
          if (x != 7 || z != 6 || y == 7) h.setBlock(new BlockPos(x, y, z), leaf);
    for (int y = 1; y <= 6; y++) h.setBlock(7, y, 6, Blocks.OAK_LOG);
    var s = skills(n);
    var runner = collect(n, s, 6);
    h.onEachTick(
        () -> {
          s.tick();
          runner.tick(h.getLevel().getGameTime());
          h.assertFalse(runner.needsReplan(), "Canopy trunk access failed: " + runner.problem());
          if (runner.complete()) {
            h.assertTrue(count(n, Items.OAK_LOG) >= 6, "Every trunk log must be collected");
            runner.close();
            s.cancel("Test done");
            n.discard();
            h.succeed();
          }
        });
  }

  private static void leaves(GameTestHelper h) {
    ground(h);
    var n = npc(h, 3, 1, 6);
    n.inventory.setItem(0, new ItemStack(Items.IRON_AXE));
    for (int x = 6; x <= 8; x++)
      for (int y = 1; y <= 4; y++)
        for (int z = 5; z <= 7; z++)
          h.setBlock(
              new BlockPos(x, y, z),
              Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
    for (int y = 1; y <= 3; y++) h.setBlock(7, y, 6, Blocks.SPRUCE_LOG);
    var s = skills(n);
    var runner = collect(n, s, 2);
    h.onEachTick(
        () -> {
          s.tick();
          runner.tick(h.getLevel().getGameTime());
          h.assertFalse(runner.needsReplan(), "Leaf access failed: " + runner.problem());
          if (runner.complete()) {
            h.assertTrue(
                count(n, Items.SPRUCE_LOG) >= 2,
                "Real logs must be collected after removing leaves");
            int leaves = 0;
            for (int x = 6; x <= 8; x++)
              for (int y = 1; y <= 4; y++)
                for (int z = 5; z <= 7; z++)
                  if (h.getLevel()
                      .getBlockState(h.absolutePos(new BlockPos(x, y, z)))
                      .is(Blocks.OAK_LEAVES)) leaves++;
            h.assertTrue(leaves < 33, "The obstructing leaf blocks must actually be removed");
            runner.close();
            s.cancel("Test done");
            n.discard();
            h.succeed();
          }
        });
  }

  private static void tallTree(GameTestHelper h) {
    ground(h);
    var n = npc(h, 7, 1, 7);
    n.inventory.setItem(0, new ItemStack(Items.IRON_AXE));
    n.inventory.setItem(1, new ItemStack(Items.DIRT, 64));
    for (int y = 1; y <= 15; y++) h.setBlock(10, y, 7, Blocks.SPRUCE_LOG);
    var s = skills(n);
    var runner = collect(n, s, 15);
    double[] highest = {n.getY()};
    h.onEachTick(
        () -> {
          s.tick();
          runner.tick(h.getLevel().getGameTime());
          highest[0] = Math.max(highest[0], n.getY());
          h.assertFalse(runner.needsReplan(), "Tall tree access failed: " + runner.problem());
          h.assertTrue(n.isAlive(), "NPC must remain alive on its work stairs");
          if (runner.complete()) {
            h.assertTrue(
                count(n, Items.SPRUCE_LOG) >= 15, "All high logs must be physically collected");
            h.assertTrue(
                count(n, Items.DIRT) < 64
                    && highest[0] > h.absolutePos(new BlockPos(0, 5, 0)).getY(),
                "Must consume real scaffolding and climb");
            var back = n.getNavigation().createPath(h.absolutePos(new BlockPos(7, 1, 7)), 0);
            h.assertTrue(
                back != null && back.canReach(),
                "Stair construction must leave a walkable way down");
            runner.close();
            s.cancel("Test done");
            n.discard();
            h.succeed();
          }
        });
  }

  private static void corridor(GameTestHelper h) {
    ground(h);
    for (int x = 0; x < 20; x++)
      for (int y = 1; y <= 3; y++) {
        h.setBlock(x, y, 5, Blocks.BEDROCK);
        h.setBlock(x, y, 7, Blocks.BEDROCK);
        h.setBlock(x, 3, 6, Blocks.BEDROCK);
      }
    for (int y = 1; y <= 2; y++) {
      h.setBlock(0, y, 6, Blocks.BEDROCK);
      h.setBlock(19, y, 6, Blocks.BEDROCK);
    }
  }

  private static CompletableFuture<JsonObject> route(
      GameTestHelper h, CompanionEntity n, NpcSkills s, String mode, int budget, int y) {
    var p = h.absolutePos(new BlockPos(15, y, 6));
    ((ServerPlayer) h.getLevel().getServer().getPlayerList().getPlayer(n.ownerId()))
        .setPos(Vec3.atBottomCenterOf(p));
    return s.execute(
            "work_route",
            object(
                "x",
                p.getX(),
                "y",
                p.getY(),
                "z",
                p.getZ(),
                "goal",
                mode.equals("build") ? "bridge" : "reach",
                "mode",
                mode,
                "max_blocks",
                budget))
        .toCompletableFuture();
  }

  private static void tunnel(GameTestHelper h) {
    corridor(h);
    h.setBlock(8, 1, 6, Blocks.STONE);
    h.setBlock(8, 2, 6, Blocks.STONE);
    var n = npc(h, 3, 1, 6);
    n.inventory.setItem(0, new ItemStack(Items.IRON_PICKAXE));
    var s = skills(n);
    var future = route(h, n, s, "dig", 16, 1);
    h.onEachTick(
        () -> {
          s.tick();
          if (!future.isDone()) return;
          h.assertTrue(
              future.join().get("ok").getAsBoolean(),
              "Digging route must arrive: " + future.join());
          h.assertTrue(future.join().get("dug").getAsInt() >= 2, "Both wall cells must be removed");
          h.assertTrue(
              n.getX() > h.absolutePos(new BlockPos(8, 1, 6)).getX(),
              "NPC must physically cross its tunnel");
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static void toolPreparation(GameTestHelper h) {
    corridor(h);
    h.setBlock(8, 1, 6, Blocks.STONE);
    h.setBlock(8, 2, 6, Blocks.STONE);
    h.setBlock(3, 1, 5, Blocks.CHEST);
    h.setBlock(3, 2, 5, Blocks.AIR);
    var chest =
        (ChestBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(3, 1, 5)));
    chest.setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
    var n = npc(h, 3, 1, 6);
    var s = skills(n);
    var future = route(h, n, s, "dig", 16, 1);
    h.onEachTick(
        () -> {
          s.tick();
          if (!future.isDone()) return;
          h.assertTrue(
              future.join().get("ok").getAsBoolean(),
              "Must prepare a real carried tool before tunnelling: " + future.join());
          h.assertTrue(
              count(n, Items.WOODEN_PICKAXE) == 1 && chest.getItem(0).isEmpty(),
              "Actual chest tool must be transferred without duplication");
          h.assertTrue(future.join().get("dug").getAsInt() >= 2, "Tool must really break the wall");
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static void bridge(GameTestHelper h, boolean water, boolean cancel) {
    ground(h);
    int y = water ? 3 : 1;
    for (int x = 0; x < 20; x++)
      for (int z = 0; z < 20; z++) {
        if (water) {
          h.setBlock(x, 1, z, Blocks.BEDROCK);
          h.setBlock(x, 2, z, Blocks.BEDROCK);
        }
        if (x >= 5 && x <= 10) {
          h.setBlock(x, y - 1, z, Blocks.AIR);
          if (water) h.setBlock(x, 1, z, Blocks.WATER);
        }
      }
    var n = npc(h, 3, y, 6);
    n.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 32));
    var s = skills(n);
    var future = route(h, n, s, "build", 32, y);
    long[] stopped = {-1};
    int[] atStop = {-1};
    h.onEachTick(
        () -> {
          s.tick();
          h.assertTrue(
              n.isAlive() && n.getY() >= h.absolutePos(new BlockPos(0, y - 1, 0)).getY(),
              "Never step into the unfinished gap");
          int placed = 32 - count(n, Items.COBBLESTONE);
          if (cancel && stopped[0] < 0 && placed > 0) {
            s.cancel("Owner cancelled bridge");
            stopped[0] = h.getLevel().getGameTime();
            atStop[0] = placed;
          }
          if (cancel) {
            if (stopped[0] >= 0 && h.getLevel().getGameTime() - stopped[0] > 50) {
              h.assertTrue(
                  future.isDone() && !future.join().get("ok").getAsBoolean(),
                  "Cancel must stop the route");
              h.assertTrue(
                  placed == atStop[0], "No further material may be consumed after cancellation");
              n.discard();
              h.succeed();
            }
            return;
          }
          if (!future.isDone()) return;
          h.assertTrue(
              future.join().get("ok").getAsBoolean(),
              "Bridge must reach the owner: " + future.join());
          h.assertTrue(
              placed >= 6 && future.join().get("placed").getAsInt() == placed,
              "Every bridge block must consume one carried block");
          h.assertTrue(
              n.getX() > h.absolutePos(new BlockPos(10, y, 6)).getX(),
              "Must actually cross the constructed bridge");
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static void blocked(GameTestHelper h) {
    corridor(h);
    h.setBlock(8, 1, 6, Blocks.CHEST);
    h.setBlock(8, 2, 6, Blocks.BEDROCK);
    var p = h.absolutePos(new BlockPos(8, 1, 6));
    ((ChestBlockEntity) h.getLevel().getBlockEntity(p)).setItem(0, new ItemStack(Items.DIAMOND, 3));
    var n = npc(h, 3, 1, 6);
    n.inventory.setItem(0, new ItemStack(Items.IRON_PICKAXE));
    var s = skills(n);
    var future = route(h, n, s, "dig", 1, 1);
    h.onEachTick(
        () -> {
          s.tick();
          if (!future.isDone()) return;
          h.assertFalse(
              future.join().get("ok").getAsBoolean(),
              "A container/bedrock wall must not be demolished");
          h.assertTrue(
              h.getLevel().getBlockEntity(p) instanceof ChestBlockEntity chest
                  && chest.getItem(0).getCount() == 3,
              "Container and items must survive");
          h.assertTrue(future.join().get("dug").getAsInt() == 0, "No unrelated edits allowed");
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }
}

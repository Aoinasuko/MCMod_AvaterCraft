package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.JsonObject;
import dev.avatarcraft.npc.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

final class NpcSurfaceGameTests {
  static void register(RegisterGameTestsEvent event) {
    var env =
        event.registerEnvironment(
            bep_3davater_companion.id("surface_and_gate_qa"), new TestEnvironmentDefinition.AllOf());
    test(
        event,
        env,
        "half_step_stair_top_slab_round_trip_without_jumping",
        h -> surface(h, false, false));
    test(
        event,
        env,
        "snow_layers_precise_arrival_uses_collision_height",
        h -> surface(h, true, false));
    test(event, env, "partial_floor_rejects_low_ceiling", h -> surface(h, false, true));
    test(
        event,
        env,
        "half_slab_with_partial_ceiling_has_real_headroom",
        NpcSurfaceGameTests::headroom);
    test(event, env, "fence_gates_and_half_steps_on_same_route", h -> gates(h, false, false, true));
    test(event, env, "fence_gates_open_cross_and_close", h -> gates(h, false, false));
    test(event, env, "fence_gate_initially_open_is_preserved", h -> gates(h, true, false));
    test(event, env, "fence_gate_respects_owner_build_restriction", h -> gates(h, false, true));
  }

  private static void test(
      RegisterGameTestsEvent event,
      Holder<TestEnvironmentDefinition<?>> env,
      String name,
      Consumer<GameTestHelper> run) {
    event.registerTest(
        bep_3davater_companion.id(name),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(env, bep_3davater_companion.id("qa_navigation"), 2400, 1, true)) {
          public void run(GameTestHelper h) {
            run.accept(h);
          }
        });
  }

  private static CompanionEntity fixture(GameTestHelper h) {
    for (int x = 0; x < 24; x++) for (int z = 4; z <= 8; z++) h.setBlock(x, 0, z, Blocks.BEDROCK);
    for (int x = 0; x < 24; x++)
      for (int y = 1; y <= 6; y++)
        for (int z : new int[] {5, 7}) h.setBlock(x, y, z, Blocks.BEDROCK);
    for (int x = 0; x < 24; x++) h.setBlock(x, 6, 6, Blocks.BEDROCK);
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 4))));
    var n = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    n.initialize(owner, "tortobu");
    n.setFollowsOwner(false);
    n.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3, 1, 6))));
    h.getLevel().addFreshEntity(n);
    return n;
  }

  private static JsonObject at(GameTestHelper h, int x, double y, int z) {
    var p = h.absolutePos(new BlockPos(x, 0, z));
    return object("x", p.getX(), "y", p.getY() + y, "z", p.getZ());
  }

  private static void surface(GameTestHelper h, boolean snow, boolean low) {
    var n = fixture(h);
    var s = new NpcSkills(n, (a, b) -> {});
    if (snow)
      for (int x = 4; x <= 10; x++)
        h.setBlock(
            new BlockPos(x, 1, 6),
            Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, x - 2));
    else {
      for (int x = 4; x <= 7; x++)
        h.setBlock(new BlockPos(x, 1, 6), Blocks.STONE_SLAB.defaultBlockState());
      if (low)
        for (int x = 4; x <= 10; x++) {
          h.setBlock(new BlockPos(x, 1, 6), Blocks.STONE_SLAB.defaultBlockState());
          h.setBlock(x, 3, 6, Blocks.BEDROCK);
        }
      else {
        for (int x = 8; x <= 22; x++) h.setBlock(x, 1, 6, Blocks.STONE);
        h.setBlock(
            new BlockPos(8, 1, 6),
            Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
        h.setBlock(
            new BlockPos(10, 1, 6),
            Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        for (int x = 12; x <= 15; x++)
          h.setBlock(new BlockPos(x, 2, 6), Blocks.STONE_SLAB.defaultBlockState());
        for (int x = 16; x <= 22; x++) h.setBlock(x, 2, 6, Blocks.STONE);
      }
    }
    var pending = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<JsonObject>>();
    double finalY = snow ? 1.875 : low ? 1.5 : 2.5;
    pending.set(
        s.execute("move_precisely", at(h, snow ? 10 : low ? 9 : 14, finalY, 6))
            .toCompletableFuture());
    int[] stage = {0};
    double[] maxJump = {0};
    Vec3[] last = {n.position()};
    h.onEachTick(
        () -> {
          s.tick();
          maxJump[0] = Math.max(maxJump[0], n.getDeltaMovement().y);
          h.assertTrue(
              n.position().distanceToSqr(last[0]) < 4, "Use physical motion on partial floors");
          last[0] = n.position();
          if (!pending.get().isDone()) return;
          var result = pending.get().join();
          if (low) {
            h.assertFalse(result.get("ok").getAsBoolean(), "Reject corridor below entity headroom");
            s.cancel("Test done");
            n.discard();
            h.succeed();
            return;
          }
          h.assertTrue(
              result.get("ok").getAsBoolean(),
              "Partial floor movement: " + result + " at " + n.position());
          if (stage[0] == 0) {
            h.assertTrue(
                Math.abs(n.getY() - h.absolutePos(BlockPos.ZERO).getY() - finalY) < .2,
                "Precise arrival uses actual slab/snow top");
            stage[0] = 1;
            pending.set(s.execute("move_precisely", at(h, 3, 1, 6)).toCompletableFuture());
            return;
          }
          h.assertTrue(
              maxJump[0] < .2,
              "Walkable increments must use stepping instead of jumping: " + maxJump[0]);
          h.assertTrue(
              n.distanceToSqr(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3, 1, 6)))) < 1,
              "Walk back down to original position");
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static void headroom(GameTestHelper h) {
    var n = fixture(h);
    var s = new NpcSkills(n, (a, b) -> {});
    for (int x = 3; x <= 12; x++) {
      if (x >= 4) h.setBlock(new BlockPos(x, 1, 6), Blocks.STONE_SLAB.defaultBlockState());
      h.setBlock(
          new BlockPos(x, 3, 6),
          Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
    }
    var result = s.execute("move_precisely", at(h, 11, 1.5, 6)).toCompletableFuture();
    h.onEachTick(
        () -> {
          s.tick();
          if (!result.isDone()) return;
          h.assertTrue(
              result.join().get("ok").getAsBoolean(),
              "Actual 2-block headroom on half floor: " + result.join());
          h.assertTrue(
              Math.abs(n.getY() - h.absolutePos(BlockPos.ZERO).getY() - 1.5) < .2,
              "Stand on slab under partial roof");
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static void gates(GameTestHelper h, boolean initiallyOpen, boolean restricted) {
    gates(h, initiallyOpen, restricted, false);
  }

  private static void gates(
      GameTestHelper h, boolean initiallyOpen, boolean restricted, boolean mixed) {
    var n = fixture(h);
    var s = new NpcSkills(n, (a, b) -> {});
    for (int x = 0; x < 24; x++) h.setBlock(x, mixed ? 4 : 3, 6, Blocks.BEDROCK);
    var state =
        Blocks.OAK_FENCE_GATE
            .defaultBlockState()
            .setValue(FenceGateBlock.FACING, Direction.EAST)
            .setValue(FenceGateBlock.OPEN, initiallyOpen);
    h.setBlock(new BlockPos(9, 1, 6), state);
    h.setBlock(new BlockPos(14, 1, 6), state);
    if (mixed)
      for (int x : new int[] {4, 5, 6, 16, 17, 18})
        h.setBlock(new BlockPos(x, 1, 6), Blocks.STONE_SLAB.defaultBlockState());
    if (restricted)
      h.getLevel()
          .getServer()
          .getPlayerList()
          .getPlayer(n.ownerId())
          .gameMode
          .changeGameModeForPlayer(GameType.ADVENTURE);
    var result = s.execute("move_to", at(h, 20, 1, 6)).toCompletableFuture();
    boolean[] opened = {false, false};
    int[] finished = {0};
    h.onEachTick(
        () -> {
          s.tick();
          for (int i = 0; i < 2; i++)
            opened[i] |=
                h.getLevel()
                    .getBlockState(h.absolutePos(new BlockPos(i == 0 ? 9 : 14, 1, 6)))
                    .getValue(FenceGateBlock.OPEN);
          if (!result.isDone()) return;
          if (restricted) {
            h.assertFalse(
                result.join().get("ok").getAsBoolean() || opened[0] || opened[1],
                "Owner restriction prevents opening gates");
            s.cancel("Test done");
            n.discard();
            h.succeed();
            return;
          }
          h.assertTrue(result.join().get("ok").getAsBoolean(), "Gate route: " + result.join());
          if (++finished[0] < 60) return;
          h.assertTrue(opened[0] && opened[1], "Open and actually cross both gates");
          for (int x : new int[] {9, 14})
            h.assertTrue(
                h.getLevel()
                        .getBlockState(h.absolutePos(new BlockPos(x, 1, 6)))
                        .getValue(FenceGateBlock.OPEN)
                    == initiallyOpen,
                "Close gates opened by NPC; preserve pre-opened gates");
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }
}

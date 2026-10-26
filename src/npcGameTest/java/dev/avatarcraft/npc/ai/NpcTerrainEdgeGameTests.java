package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import dev.avatarcraft.npc.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** Edges a player simply walks off: short drops are routes, long ones are not. */
final class NpcTerrainEdgeGameTests {
  static void register(RegisterGameTestsEvent event) {
    var env =
        event.registerEnvironment(
            bep_3davater_companion.id("terrain_edge_qa"), new TestEnvironmentDefinition.AllOf());
    test(event, env, "drop_three_blocks_off_a_ledge", h -> ledge(h, 3));
    test(event, env, "refuse_four_block_drop", h -> ledge(h, 4));
    test(event, env, "climb_a_ladder_up_a_wall", NpcTerrainEdgeGameTests::ladder);
    test(event, env, "wade_through_a_flooded_low_tunnel", NpcTerrainEdgeGameTests::flooded);
    test(event, env, "route_keeps_off_a_wall", NpcTerrainEdgeGameTests::wall);
    test(event, env, "diagonal_step_across_a_corner_gap", NpcTerrainEdgeGameTests::corner);
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
            new TestData<>(env, bep_3davater_companion.id("qa_navigation"), 1200, 1, true)) {
          public void run(GameTestHelper h) {
            body.accept(h);
          }
        });
  }

  /** A raised platform with no stairs; the goal is on the ground beyond its edge. */
  private static void ledge(GameTestHelper h, int height) {
    for (int x = 0; x < 30; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.BEDROCK);
    for (int x = 0; x < 12; x++)
      for (int z = 0; z < 12; z++)
        for (int y = 1; y <= height; y++) h.setBlock(x, y, z, Blocks.STONE);
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, height + 1, 1))));
    var n = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    n.initialize(owner, "tortobu");
    n.setFollowsOwner(false);
    n.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(5, height + 1, 6))));
    h.getLevel().addFreshEntity(n);
    var skills = new NpcSkills(n, (s, id) -> {});
    var target = h.absolutePos(new BlockPos(24, 1, 6));
    var result =
        skills
            .execute("move_to", object("x", target.getX(), "y", target.getY(), "z", target.getZ()))
            .toCompletableFuture();
    h.onEachTick(
        () -> {
          skills.tick();
          if (!result.isDone()) return;
          var out = result.join();
          if (height <= 3) {
            h.assertTrue(out.get("ok").getAsBoolean(), "A three-block drop is a route: " + out);
            h.assertTrue(n.getHealth() >= n.getMaxHealth(), "The drop must not hurt: " + n.getHealth());
          } else {
            h.assertFalse(out.get("ok").getAsBoolean(), "A four-block drop is not a route: " + out);
            h.assertTrue(n.getY() > h.absolutePos(new BlockPos(0, height, 0)).getY(), "NPC stays up");
          }
          skills.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  /** The only way onto the platform is a ladder on its wall. */
  private static void ladder(GameTestHelper h) {
    for (int x = 0; x < 20; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.BEDROCK);
    for (int x = 10; x < 16; x++)
      for (int z = 0; z < 12; z++) for (int y = 1; y <= 4; y++) h.setBlock(x, y, z, Blocks.STONE);
    for (int y = 1; y <= 4; y++)
      h.setBlock(
          new BlockPos(9, y, 6),
          Blocks.LADDER
              .defaultBlockState()
              .setValue(net.minecraft.world.level.block.LadderBlock.FACING, Direction.WEST));
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 1))));
    var n = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    n.initialize(owner, "tortobu");
    n.setFollowsOwner(false);
    n.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3, 1, 6))));
    h.getLevel().addFreshEntity(n);
    var skills = new NpcSkills(n, (s, id) -> {});
    var target = h.absolutePos(new BlockPos(13, 5, 6));
    var result =
        skills
            .execute("move_to", object("x", target.getX(), "y", target.getY(), "z", target.getZ()))
            .toCompletableFuture();
    h.onEachTick(
        () -> {
          skills.tick();
          if (!result.isDone()) return;
          var out = result.join();
          h.assertTrue(out.get("ok").getAsBoolean(), "The ladder is a route: " + out);
          h.assertTrue(n.getY() >= target.getY() - .01, "NPC must be on the platform: " + n.position());
          skills.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  /**
   * A one-block-deep flooded stretch under a low ceiling: too low to swim at the surface, but a
   * player wades through with the head in air.
   */
  private static void flooded(GameTestHelper h) {
    for (int x = 0; x < 26; x++) {
      for (int y = -1; y <= 3; y++) {
        h.setBlock(x, y, 4, Blocks.STONE);
        h.setBlock(x, y, 6, Blocks.STONE);
      }
      h.setBlock(x, 0, 5, Blocks.BEDROCK);
      h.setBlock(x, 3, 5, Blocks.STONE);
    }
    for (int x = 10; x <= 14; x++) {
      h.setBlock(x, -1, 5, Blocks.STONE);
      h.setBlock(x, 0, 5, Blocks.WATER);
      // Full height where the floor steps down and up; low only over the middle.
      if (x >= 11 && x <= 13) h.setBlock(x, 2, 5, Blocks.STONE);
    }
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 5))));
    var n = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    n.initialize(owner, "tortobu");
    n.setFollowsOwner(false);
    n.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3, 1, 5))));
    h.getLevel().addFreshEntity(n);
    var skills = new NpcSkills(n, (s, id) -> {});
    var target = h.absolutePos(new BlockPos(22, 1, 5));
    var result =
        skills
            .execute("move_to", object("x", target.getX(), "y", target.getY(), "z", target.getZ()))
            .toCompletableFuture();
    h.onEachTick(
        () -> {
          skills.tick();
          h.assertTrue(n.getAirSupply() > 0, "Wading keeps the head in air");
          if (!result.isDone()) return;
          var out = result.join();
          h.assertTrue(out.get("ok").getAsBoolean(), "The flooded tunnel is a route: " + out);
          skills.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static CompanionEntity spawn(GameTestHelper h, BlockPos at) {
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 1))));
    var n = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    n.initialize(owner, "tortobu");
    n.setFollowsOwner(false);
    n.setPos(Vec3.atBottomCenterOf(h.absolutePos(at)));
    h.getLevel().addFreshEntity(n);
    return n;
  }

  /** Start and goal touch a long wall; the route between them should not scrape along it. */
  private static void wall(GameTestHelper h) {
    for (int x = 0; x < 44; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.BEDROCK);
    for (int x = 0; x < 44; x++) for (int y = 1; y <= 2; y++) h.setBlock(x, y, 4, Blocks.STONE);
    var n = spawn(h, new BlockPos(4, 1, 5));
    var skills = new NpcSkills(n, (s, id) -> {});
    var target = h.absolutePos(new BlockPos(38, 1, 5));
    var result =
        skills
            .execute("move_to", object("x", target.getX(), "y", target.getY(), "z", target.getZ()))
            .toCompletableFuture();
    double face = h.absoluteVec(new Vec3(0, 0, 5)).z;
    double[] closest = {Double.MAX_VALUE};
    h.onEachTick(
        () -> {
          skills.tick();
          double x = n.getX() - h.absoluteVec(Vec3.ZERO).x;
          if (x > 14 && x < 28) closest[0] = Math.min(closest[0], Math.abs(n.getZ() - face));
          if (!result.isDone()) return;
          var out = result.join();
          h.assertTrue(out.get("ok").getAsBoolean(), "Must arrive: " + out);
          h.assertTrue(closest[0] >= .9, "Mid-route the body should keep off the wall: " + closest[0]);
          skills.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  /** Two raised platforms meet only at a corner; a player steps across diagonally. */
  private static void corner(GameTestHelper h) {
    for (int x = 0; x <= 4; x++) for (int z = 0; z <= 4; z++) h.setBlock(x, 5, z, Blocks.BEDROCK);
    for (int x = 5; x <= 9; x++) for (int z = 5; z <= 9; z++) h.setBlock(x, 5, z, Blocks.BEDROCK);
    var n = spawn(h, new BlockPos(1, 6, 1));
    var skills = new NpcSkills(n, (s, id) -> {});
    var target = h.absolutePos(new BlockPos(8, 6, 8));
    var result =
        skills
            .execute("move_to", object("x", target.getX(), "y", target.getY(), "z", target.getZ()))
            .toCompletableFuture();
    h.onEachTick(
        () -> {
          skills.tick();
          if (!result.isDone()) return;
          var out = result.join();
          h.assertTrue(out.get("ok").getAsBoolean(), "The corner is a route: " + out);
          h.assertTrue(n.getY() > h.absolutePos(new BlockPos(0, 5, 0)).getY(), "NPC must not fall");
          skills.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }
}

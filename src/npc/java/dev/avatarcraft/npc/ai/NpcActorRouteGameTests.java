package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import dev.avatarcraft.npc.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** Creatures move, so walking routes must not treat where they stood as solid terrain. */
final class NpcActorRouteGameTests {
  static void register(RegisterGameTestsEvent event) {
    var env =
        event.registerEnvironment(
            bep_3davater_companion.id("actor_route_qa"), new TestEnvironmentDefinition.AllOf());
    test(event, env, "walk_to_cell_a_mob_just_left", NpcActorRouteGameTests::vacated);
    test(event, env, "mobs_crossing_route_do_not_force_replans", NpcActorRouteGameTests::crossing);
    test(event, env, "stationary_mob_on_line_is_walked_around", NpcActorRouteGameTests::stationary);
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
            new TestData<>(env, bep_3davater_companion.id("qa_navigation"), 2400, 1, true)) {
          public void run(GameTestHelper h) {
            body.accept(h);
          }
        });
  }

  private static CompanionEntity fixture(GameTestHelper h) {
    for (int x = 0; x < 60; x++) for (int z = 0; z < 16; z++) h.setBlock(x, 0, z, Blocks.BEDROCK);
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 1))));
    var n = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    n.initialize(owner, "tortobu");
    n.setFollowsOwner(false);
    n.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(4, 1, 8))));
    h.getLevel().addFreshEntity(n);
    return n;
  }

  private static Cow cow(GameTestHelper h, double x, double z) {
    var cow = EntityTypes.COW.create(h.getLevel(), EntitySpawnReason.COMMAND);
    cow.setNoAi(true);
    var p = h.absoluteVec(new Vec3(x, 1, z));
    cow.setPos(p.x, p.y, p.z);
    h.getLevel().addFreshEntity(cow);
    return cow;
  }

  private static java.util.concurrent.CompletableFuture<com.google.gson.JsonObject> move(
      NpcSkills skills, BlockPos target) {
    return skills
        .execute("move_to", object("x", target.getX(), "y", target.getY(), "z", target.getZ()))
        .toCompletableFuture();
  }

  /** An animal standing in the only gap at search time must not make the far side unreachable. */
  private static void vacated(GameTestHelper h) {
    var n = fixture(h);
    // Enclose the floor so the single gap in the divider is the only way across.
    for (int y = 1; y <= 3; y++) {
      for (int x = 0; x < 60; x++) {
        h.setBlock(x, y, 0, Blocks.STONE);
        h.setBlock(x, y, 15, Blocks.STONE);
      }
      for (int z = 0; z < 16; z++) {
        h.setBlock(0, y, z, Blocks.STONE);
        h.setBlock(59, y, z, Blocks.STONE);
        if (z != 8) h.setBlock(20, y, z, Blocks.STONE);
      }
    }
    var target = h.absolutePos(new BlockPos(30, 1, 8));
    var grazing = cow(h, 20.5, 8.5);
    var skills = new NpcSkills(n, (s, id) -> {});
    var result = move(skills, target);
    int[] ticks = {0};
    h.onEachTick(
        () -> {
          // The animal wanders off shortly after the route was requested.
          if (++ticks[0] == 40) grazing.discard();
          skills.tick();
          if (!result.isDone()) return;
          var out = result.join();
          h.assertTrue(out.get("ok").getAsBoolean(), "Vacated destination must be reached: " + out);
          h.assertTrue(
              n.position().distanceToSqr(Vec3.atBottomCenterOf(target)) < 1, "Must stand there");
          skills.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  /** Animals walking across the route must not invalidate the path again and again. */
  private static void crossing(GameTestHelper h) {
    var n = fixture(h);
    var target = h.absolutePos(new BlockPos(54, 1, 8));
    var cows = new ArrayList<Cow>();
    for (int x = 12; x <= 48; x += 6) cows.add(cow(h, x + .5, 8.5));
    var skills = new NpcSkills(n, (s, id) -> {});
    var result = move(skills, target);
    int[] ticks = {0};
    h.onEachTick(
        () -> {
          ticks[0]++;
          // Each animal paces across the route line at a walking speed.
          for (int i = 0; i < cows.size(); i++) {
            var c = cows.get(i);
            double z = 8.5 + 3.5 * Math.sin((ticks[0] + i * 7) / 12.);
            var p = h.absoluteVec(new Vec3(12.5 + 6 * i, 1, z));
            c.setPos(p.x, p.y, p.z);
          }
          skills.tick();
          if (!result.isDone()) return;
          var out = result.join();
          h.assertTrue(out.get("ok").getAsBoolean(), "Must arrive past moving animals: " + out);
          h.assertTrue(
              out.get("pathReplans").getAsInt() <= 2,
              "Passing animals must not force repeated re-planning: " + out);
          skills.cancel("Test done");
          cows.forEach(Cow::discard);
          n.discard();
          h.succeed();
        });
  }

  /** A standing animal is still avoided when an open detour exists. */
  private static void stationary(GameTestHelper h) {
    var n = fixture(h);
    var target = h.absolutePos(new BlockPos(40, 1, 8));
    var standing = cow(h, 22.5, 8.5);
    var skills = new NpcSkills(n, (s, id) -> {});
    var result = move(skills, target);
    double[] closest = {Double.MAX_VALUE};
    h.onEachTick(
        () -> {
          skills.tick();
          closest[0] = Math.min(closest[0], n.distanceTo(standing));
          if (!result.isDone()) return;
          var out = result.join();
          h.assertTrue(out.get("ok").getAsBoolean(), "Must arrive: " + out);
          h.assertTrue(closest[0] > .9, "Route must pass beside, not through, the animal: " + closest[0]);
          skills.cancel("Test done");
          standing.discard();
          n.discard();
          h.succeed();
        });
  }
}

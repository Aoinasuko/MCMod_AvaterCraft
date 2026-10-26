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

/** Destinations given a block off, owners off the ground, and side-steps only when blocked. */
final class NpcArrivalGameTests {
  static void register(RegisterGameTestsEvent event) {
    var env =
        event.registerEnvironment(
            bep_3davater_companion.id("arrival_qa"), new TestEnvironmentDefinition.AllOf());
    test(event, env, "move_to_solid_block_arrives_beside_it", h -> offset(h, true));
    test(event, env, "move_to_one_above_floor_arrives_on_floor", h -> offset(h, false));
    test(event, env, "follow_owner_above_the_ground", NpcArrivalGameTests::airborneOwner);
    test(event, env, "animals_beside_turning_route_do_not_cause_sidesteps", NpcArrivalGameTests::pen);
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
    for (int x = 0; x < 40; x++) for (int z = 0; z < 20; z++) h.setBlock(x, 0, z, Blocks.BEDROCK);
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 1))));
    var n = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    n.initialize(owner, "tortobu");
    n.setFollowsOwner(false);
    n.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(4, 1, 4))));
    h.getLevel().addFreshEntity(n);
    return n;
  }

  private static void expect(GameTestHelper h, CompanionEntity n, NpcSkills skills,
      java.util.concurrent.CompletableFuture<com.google.gson.JsonObject> result,
      java.util.function.Consumer<com.google.gson.JsonObject> verify) {
    h.onEachTick(
        () -> {
          skills.tick();
          if (!result.isDone()) return;
          verify.accept(result.join());
          skills.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  /** A planner often names the block itself, or the air cell above the floor. */
  private static void offset(GameTestHelper h, boolean solid) {
    var n = fixture(h);
    var named = new BlockPos(20, solid ? 1 : 2, 4);
    if (solid) {
      h.setBlock(named, Blocks.STONE);
      h.setBlock(named.above(), Blocks.STONE);
    }
    var target = h.absolutePos(named);
    var skills = new NpcSkills(n, (s, id) -> {});
    var result =
        skills
            .execute("move_to", object("x", target.getX(), "y", target.getY(), "z", target.getZ()))
            .toCompletableFuture();
    expect(h, n, skills, result, out -> {
      h.assertTrue(out.get("ok").getAsBoolean(), "Must arrive near the named cell: " + out);
      h.assertTrue(
          n.position().distanceToSqr(Vec3.atBottomCenterOf(target)) <= 2.5,
          "Must stand next to the named cell: " + n.position());
    });
  }

  /** The owner's block position is above the floor while jumping or standing on a ledge edge. */
  private static void airborneOwner(GameTestHelper h) {
    var n = fixture(h);
    var owner = h.getLevel().getServer().getPlayerList().getPlayer(n.ownerId());
    var at = h.absoluteVec(new Vec3(30.5, 2.2, 10.5));
    owner.setPos(at.x, at.y, at.z);
    var skills = new NpcSkills(n, (s, id) -> {});
    skills.execute("follow", object("entity_id", owner.getId(), "seconds", 30));
    h.onEachTick(
        () -> {
          owner.setPos(at.x, at.y, at.z);
          skills.tick();
          if (n.distanceToSqr(owner) < 3.5 * 3.5) {
            skills.cancel("Test done");
            n.discard();
            h.succeed();
          }
        });
  }

  /**
   * The destination is behind a wall, so the route first runs along it. Animals penned on the far
   * side of the wall are on the straight line to the goal but never in the way.
   */
  private static void pen(GameTestHelper h) {
    var n = fixture(h);
    for (int z = 0; z < 16; z++) for (int y = 1; y <= 3; y++) h.setBlock(10, y, z, Blocks.STONE);
    var cows = new ArrayList<Cow>();
    for (int z = 6; z <= 14; z += 2) {
      var cow = EntityTypes.COW.create(h.getLevel(), EntitySpawnReason.COMMAND);
      cow.setNoAi(true);
      var p = h.absoluteVec(new Vec3(11.0, 1, z + .5));
      cow.setPos(p.x, p.y, p.z);
      h.getLevel().addFreshEntity(cow);
      cows.add(cow);
    }
    var target = h.absolutePos(new BlockPos(16, 1, 6));
    var skills = new NpcSkills(n, (s, id) -> {});
    var result =
        skills
            .execute("move_to", object("x", target.getX(), "y", target.getY(), "z", target.getZ()))
            .toCompletableFuture();
    expect(h, n, skills, result, out -> {
      cows.forEach(Cow::discard);
      h.assertTrue(out.get("ok").getAsBoolean(), "Must arrive: " + out);
      h.assertFalse(out.has("actorDetours"), "Animals off the route must not cause side-steps: " + out);
    });
  }
}

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

/** Measures steady ground speed against vanilla player walking and sprinting. */
final class NpcSpeedGameTests {
  // Vanilla player on normal blocks: 0.1 / 0.13 movement speed at full input.
  static final double PLAYER_WALK = 4.317, PLAYER_SPRINT = 5.612;

  static void register(RegisterGameTestsEvent event) {
    var env =
        event.registerEnvironment(
            bep_3davater_companion.id("speed_qa"), new TestEnvironmentDefinition.AllOf());
    test(event, env, "ground_sprint_speed_matches_player", h -> measure(h, Mode.OPEN));
    test(event, env, "ground_walk_speed_matches_player", h -> measure(h, Mode.HUNGRY));
    test(event, env, "sprint_continues_beside_a_wall", h -> measure(h, Mode.WALL));
    test(event, env, "sprint_continues_over_a_step", h -> measure(h, Mode.STEP));
    test(event, env, "diagonal_sprint_keeps_pace", h -> measure(h, Mode.DIAGONAL));
  }

  /** OPEN/WALL/STEP sprint; HUNGRY walks because food 6 or less forbids sprinting, as for players. */
  enum Mode {
    OPEN,
    WALL,
    STEP,
    DIAGONAL,
    HUNGRY
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
            new TestData<>(env, bep_3davater_companion.id("qa_navigation"), 3000, 1, true)) {
          public void run(GameTestHelper h) {
            body.accept(h);
          }
        });
  }

  private static void measure(GameTestHelper h, Mode mode) {
    int depth = mode == Mode.DIAGONAL ? 64 : 12;
    for (int x = 0; x < 80; x++) for (int z = 0; z < depth; z++) h.setBlock(x, 0, z, Blocks.BEDROCK);
    if (mode == Mode.WALL)
      for (int x = 0; x < 80; x++) for (int y = 1; y <= 2; y++) h.setBlock(x, y, 6, Blocks.STONE);
    if (mode == Mode.STEP)
      for (int x = 40; x < 80; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 1, z, Blocks.BEDROCK);
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(2, 1, 1))));
    var n = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    n.initialize(owner, "tortobu");
    n.setFollowsOwner(false);
    n.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(4, 1, 5))));
    h.getLevel().addFreshEntity(n);
    if (mode == Mode.HUNGRY) n.vitals().restore(6, 0, 0, 0);
    var skills = new NpcSkills(n, (s, id) -> {});
    var target = h.absolutePos(new BlockPos(mode == Mode.DIAGONAL ? 64 : 74, mode == Mode.STEP ? 2 : 1, mode == Mode.DIAGONAL ? 60 : 5));
    var result =
        skills
            .execute("move_to", object("x", target.getX(), "y", target.getY(), "z", target.getZ()))
            .toCompletableFuture();
    double[] sum = {0};
    int[] ticks = {0, 0};
    Vec3[] last = {n.position()};
    h.onEachTick(
        () -> {
          skills.tick();
          var now = n.position();
          double step = Math.hypot(now.x - last[0].x, now.z - last[0].z);
          last[0] = now;
          // Skip acceleration and the final approach; sample only the cruising section.
          if (now.x > h.absolutePos(new BlockPos(16, 1, 5)).getX()
              && now.x < h.absolutePos(new BlockPos(64, 1, 5)).getX()) {
            sum[0] += step;
            ticks[0]++;
            if (n.isSprinting()) ticks[1]++;
          }
          if (!result.isDone()) return;
          double speed = ticks[0] == 0 ? 0 : sum[0] / ticks[0] * 20;
          boolean walk = mode == Mode.HUNGRY;
          double reference = walk ? PLAYER_WALK : PLAYER_SPRINT;
          String report =
              String.format(
                  "NPC_SPEED_QA %s: %.3f blocks/s (player %.3f, ratio %.2f), sprinting %d/%d ticks",
                  mode, speed, reference, speed / reference, ticks[1], ticks[0]);
          System.getLogger("bep_3davater_loader NPC QA").log(System.Logger.Level.INFO, report);
          h.assertTrue(result.join().get("ok").getAsBoolean(), "Must arrive: " + result.join());
          h.assertTrue(
              walk ? ticks[1] == 0 : ticks[1] * 10 >= ticks[0] * 9,
              (walk ? "Hungry NPC must not sprint: " : "Must keep sprinting: ") + report);
          // Climbing the step costs a little speed, as it does for a player.
          double tolerance = mode == Mode.STEP ? .15 : .1;
          h.assertTrue(
              Math.abs(speed / reference - 1) <= tolerance, "Speed must match the player: " + report);
          skills.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }
}

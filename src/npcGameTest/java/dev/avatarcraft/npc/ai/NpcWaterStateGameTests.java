package dev.avatarcraft.npc.ai;

import dev.avatarcraft.npc.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** What the NPC knows about the water around it, and acting on a closed surface. */
final class NpcWaterStateGameTests {
  static void register(RegisterGameTestsEvent event) {
    var env =
        event.registerEnvironment(
            bep_3davater_companion.id("water_state_qa"), new TestEnvironmentDefinition.AllOf());
    test(event, env, "water_state_reports_depth_and_open_surface", NpcWaterStateGameTests::deep);
    test(event, env, "water_state_leaves_under_ice_before_air_runs_low", NpcWaterStateGameTests::ice);
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
            new TestData<>(env, bep_3davater_companion.id("qa_navigation"), 600, 1, true)) {
          public void run(GameTestHelper h) {
            body.accept(h);
          }
        });
  }

  /** A stone basin from y=-10 to 0, filled with water; dry floor around it at y=0. */
  private static void basin(GameTestHelper h, int bottom) {
    for (int x = 0; x < 16; x++)
      for (int z = 0; z < 16; z++) {
        h.setBlock(x, bottom - 1, z, Blocks.STONE);
        boolean pool = x >= 4 && x <= 11 && z >= 4 && z <= 11;
        for (int y = bottom; y <= 0; y++) h.setBlock(x, y, z, pool ? Blocks.WATER : Blocks.STONE);
      }
  }

  private static CompanionEntity spawn(GameTestHelper h, Vec3 at) {
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 1))));
    var n = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    n.initialize(owner, "tortobu");
    n.setFollowsOwner(false);
    var p = h.absoluteVec(at);
    n.setPos(p.x, p.y, p.z);
    h.getLevel().addFreshEntity(n);
    return n;
  }

  private static void deep(GameTestHelper h) {
    basin(h, -9);
    var n = spawn(h, new Vec3(7.5, -6, 7.5));
    var skills = new NpcSkills(n, (s, id) -> {});
    int[] ticks = {0};
    h.onEachTick(
        () -> {
          if (++ticks[0] < 3) return;
          var water = skills.observe().getAsJsonObject("water");
          h.assertTrue(water != null, "In water the observation carries the water state");
          h.assertTrue(water.get("eye_submerged").getAsBoolean(), "Eyes are under: " + water);
          h.assertTrue(water.get("depth_below_feet").getAsInt() >= 2, "Depth below is measured: " + water);
          h.assertTrue(water.get("to_surface").getAsDouble() > 3, "Way up is measured: " + water);
          h.assertTrue(water.get("surface_open_air").getAsBoolean(), "Open sky above: " + water);
          h.assertTrue(water.get("nearest_air").isJsonArray(), "Nearest air is known: " + water);
          n.discard();
          h.succeed();
        });
  }

  /** Under an ice lid with one hole, the NPC heads for the hole while it still has air. */
  private static void ice(GameTestHelper h) {
    basin(h, -2);
    for (int x = 4; x <= 11; x++)
      for (int z = 4; z <= 11; z++) if (x != 11 || z != 11) h.setBlock(x, 1, z, Blocks.ICE);
    var n = spawn(h, new Vec3(6.5, -2, 6.5));
    int[] ticks = {0};
    int[] started = {-1};
    h.onEachTick(
        () -> {
          ticks[0]++;
          if (started[0] < 0 && n.waterSafety.active()) started[0] = ticks[0];
          if (ticks[0] < 20) return;
          h.assertTrue(started[0] >= 0 && started[0] < 40,
              "The escape must start at once, not after the air runs low: " + started[0]);
          if (n.isEyeInFluid(net.minecraft.tags.FluidTags.WATER) && ticks[0] < 400) return;
          h.assertTrue(n.isAlive(), "NPC must reach the hole alive");
          n.discard();
          h.succeed();
        });
  }
}

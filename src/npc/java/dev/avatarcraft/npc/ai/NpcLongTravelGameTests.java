package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.JsonObject;
import dev.avatarcraft.npc.*;
import java.util.concurrent.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

final class NpcLongTravelGameTests {
  static void register(RegisterGameTestsEvent event) {
    var env =
        event.registerEnvironment(
            NpcMod.id("long_travel_qa"), new TestEnvironmentDefinition.AllOf());
    for (boolean stairs : new boolean[] {false, true})
      event.registerTest(
          NpcMod.id(
              stairs ? "reserved_return_161_blocks_up_walked_stairs" : "reserved_walk_512_blocks"),
          new FunctionGameTestInstance(
              BuiltinTestFunctions.ALWAYS_PASS,
              new TestData<>(env, NpcMod.id("qa_longtravel"), 6000, 1, true)) {
            public void run(GameTestHelper h) {
              travel(h, stairs);
            }
          });
  }

  private static JsonObject at(GameTestHelper h, int x, int y, int z) {
    var p = h.absolutePos(new BlockPos(x, y, z));
    return object("x", p.getX(), "y", p.getY(), "z", p.getZ());
  }

  private static void travel(GameTestHelper h, boolean stairs) {
    int y = stairs ? 73 : 1;
    for (int x = 0; x <= 528; x++) for (int z = 3; z <= 7; z++) h.setBlock(x, 0, z, Blocks.BEDROCK);
    if (stairs)
      for (int i = 0; i <= 72; i++)
        for (int z = 3; z <= 7; z++) h.setBlock(4 + i, 72 - i, z, Blocks.STONE);
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 1))));
    var n = NpcMod.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    n.initialize(owner, "tortobu");
    n.setFollowsOwner(false);
    n.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(4, y, 5))));
    h.getLevel().addFreshEntity(n);
    n.inventory.setItem(0, new ItemStack(Items.DIRT, 32));
    var s = new NpcSkills(n, (a, b) -> {});
    var pending = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<JsonObject>>();
    pending.set(s.execute("move_to", at(h, stairs ? 76 : 516, 1, 5)).toCompletableFuture());
    int[] stage = {0};
    Vec3[] last = {n.position()};
    h.onEachTick(
        () -> {
          s.tick();
          h.assertTrue(n.position().distanceToSqr(last[0]) < 4, "Physical movement only");
          last[0] = n.position();
          if (!pending.get().isDone()) return;
          var result = pending.get().join();
          h.assertTrue(result.get("ok").getAsBoolean(), "Long travel leg: " + result);
          if (stairs && stage[0] == 0) {
            stage[0] = 1;
            pending.set(s.execute("move_to", at(h, 148, 1, 5)).toCompletableFuture());
            return;
          }
          if (stairs && stage[0] == 1) {
            stage[0] = 2;
            var args = at(h, 4, 73, 5);
            args.addProperty("goal", "reach");
            args.addProperty("mode", "both");
            args.addProperty("max_blocks", 32);
            pending.set(s.execute("work_route", args).toCompletableFuture());
            return;
          }
          h.assertTrue(
              result.getAsJsonArray("arrived").size() == (stairs ? 2 : 5),
              "Every reserved waypoint must arrive");
          var destination = h.absolutePos(new BlockPos(stairs ? 4 : 516, stairs ? 73 : 1, 5));
          h.assertTrue(
              n.distanceToSqr(Vec3.atBottomCenterOf(destination)) < 1
                  && n.inventory.getItem(0).getCount() == 32,
              "Actual final arrival without edits");
          if (stairs)
            for (int i = 0; i <= 72; i++)
              h.assertTrue(
                  h.getLevel()
                      .getBlockState(h.absolutePos(new BlockPos(4 + i, 72 - i, 5)))
                      .is(Blocks.STONE),
                  "Keep return stairs intact");
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }
}

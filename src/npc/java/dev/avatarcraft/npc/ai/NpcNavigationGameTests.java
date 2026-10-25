package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import dev.avatarcraft.npc.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

final class NpcNavigationGameTests {
  static void register(RegisterGameTestsEvent event) {
    var env =
        event.registerEnvironment(
            NpcMod.id("navigation_128_qa"), new TestEnvironmentDefinition.AllOf());
    test(event, env, "walk_140_blocks_with_two_reserved_legs", NpcNavigationGameTests::reserved);
    test(event, env, "walk_128_blocks_without_edits", h -> travel(h, false, false));
    test(event, env, "wide_detour_precedes_work_route_edits", h -> travel(h, true, false));
    test(event, env, "cached_walking_route_replans_changed_terrain", h -> travel(h, false, true));
    test(
        event, env, "navigation_rejects_beyond_512_without_edits", NpcNavigationGameTests::outside);
    test(event, env, "follow_owner_128_blocks_without_teleport", NpcNavigationGameTests::follow);
  }

  private static void test(
      RegisterGameTestsEvent event,
      Holder<TestEnvironmentDefinition<?>> env,
      String name,
      Consumer<GameTestHelper> body) {
    event.registerTest(
        NpcMod.id(name),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(env, NpcMod.id("qa_navigation"), 6000, 1, true)) {
          public void run(GameTestHelper h) {
            body.accept(h);
          }
        });
  }

  private static CompanionEntity fixture(GameTestHelper h) {
    for (int x = 0; x < 152; x++) for (int z = 0; z < 80; z++) h.setBlock(x, 0, z, Blocks.BEDROCK);
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3, 1, 3))));
    var n = NpcMod.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    n.initialize(owner, "tortobu");
    n.setFollowsOwner(false);
    n.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(8, 1, 40))));
    h.getLevel().addFreshEntity(n);
    n.inventory.setItem(0, new ItemStack(Items.DIRT, 32));
    n.inventory.setItem(1, new ItemStack(Items.IRON_PICKAXE));
    return n;
  }

  private static int dirt(CompanionEntity n) {
    int sum = 0;
    for (int i = 0; i < NpcInventory.SIZE; i++)
      if (n.inventory.getItem(i).is(Items.DIRT)) sum += n.inventory.getItem(i).getCount();
    return sum;
  }

  private static void travel(GameTestHelper h, boolean detour, boolean changing) {
    var n = fixture(h);
    var skills = new NpcSkills(n, (s, id) -> {});
    int endpoint = detour || changing ? 48 : 136;
    if (detour)
      for (int z = 8; z <= 72; z++) for (int y = 1; y <= 3; y++) h.setBlock(32, y, z, Blocks.STONE);
    var target = h.absolutePos(new BlockPos(endpoint, 1, 40));
    var args = object("x", target.getX(), "y", target.getY(), "z", target.getZ());
    if (detour) {
      args.addProperty("goal", "reach");
      args.addProperty("mode", "both");
      args.addProperty("max_blocks", 96);
    }
    var result = skills.execute(detour ? "work_route" : "move_to", args).toCompletableFuture();
    boolean[] placed = {false};
    double[] offLine = {0};
    h.onEachTick(
        () -> {
          skills.tick();
          offLine[0] =
              Math.max(
                  offLine[0],
                  Math.abs(n.getZ() - h.absolutePos(new BlockPos(0, 0, 40)).getZ() - .5));
          if (changing && !placed[0] && n.getX() > h.absolutePos(new BlockPos(22, 1, 40)).getX()) {
            placed[0] = true;
            for (int z = 35; z <= 45; z++)
              for (int y = 1; y <= 2; y++) h.setBlock(32, y, z, Blocks.STONE);
          }
          if (!result.isDone()) return;
          var out = result.join();
          h.assertTrue(out.get("ok").getAsBoolean(), "Walking route must arrive: " + out);
          h.assertTrue(
              n.position().distanceToSqr(Vec3.atBottomCenterOf(target)) < 3,
              "Must physically reach the destination");
          h.assertTrue(dirt(n) == 32, "An existing detour must not consume scaffolding");
          if (detour) {
            h.assertTrue(
                offLine[0] > 32, "Must walk around the wide wall outside the old corridor");
            h.assertTrue(
                out.get("placed").getAsInt() == 0 && out.get("dug").getAsInt() == 0,
                "No edits on walkable detour");
          }
          if (changing)
            h.assertTrue(
                placed[0] && out.get("pathReplans").getAsInt() > 0,
                "Changed terrain must invalidate the cached route");
          if (!detour && !changing)
            h.assertTrue(
                out.get("pathSearchNodes").getAsInt() < 3000,
                "Flat 128-block travel must use directed search");
          skills.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static void follow(GameTestHelper h) {
    var n = fixture(h);
    var owner = h.getLevel().getServer().getPlayerList().getPlayer(n.ownerId());
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(136, 1, 40))));
    var skills = new NpcSkills(n, (s, id) -> {});
    skills.execute("follow", object("entity_id", owner.getId(), "seconds", 60));
    double[] last = {n.getX()};
    h.onEachTick(
        () -> {
          skills.tick();
          h.assertTrue(
              Math.abs(n.getX() - last[0]) < 2,
              "Following must use physical movement, not teleport");
          last[0] = n.getX();
          if (n.distanceToSqr(owner) < 9) {
            h.assertTrue(dirt(n) == 32, "Following must not change the terrain");
            skills.cancel("Test done");
            n.discard();
            h.succeed();
          }
        });
  }

  private static void reserved(GameTestHelper h) {
    var n = fixture(h);
    var skills = new NpcSkills(n, (s, id) -> {});
    var p = h.absolutePos(new BlockPos(148, 1, 40));
    var result =
        skills
            .execute("move_to", object("x", p.getX(), "y", p.getY(), "z", p.getZ()))
            .toCompletableFuture();
    double[] last = {n.getX()};
    h.onEachTick(
        () -> {
          skills.tick();
          h.assertTrue(Math.abs(n.getX() - last[0]) < 2, "Reserved travel must physically walk");
          last[0] = n.getX();
          if (!result.isDone()) return;
          h.assertTrue(result.join().get("ok").getAsBoolean(), "Reserved travel: " + result.join());
          h.assertTrue(
              result.join().getAsJsonArray("arrived").size() == 2,
              "Verify each intermediate arrival");
          h.assertTrue(
              n.distanceToSqr(Vec3.atBottomCenterOf(p)) < 1 && dirt(n) == 32,
              "Reach actual final destination without edits");
          skills.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static void outside(GameTestHelper h) {
    var n = fixture(h);
    var skills = new NpcSkills(n, (s, id) -> {});
    var p = h.absolutePos(new BlockPos(521, 1, 40));
    var result =
        skills
            .execute("move_to", object("x", p.getX(), "y", p.getY(), "z", p.getZ()))
            .toCompletableFuture();
    h.onEachTick(
        () -> {
          skills.tick();
          if (!result.isDone()) return;
          h.assertFalse(
              result.join().get("ok").getAsBoolean(), "513-block destination must be rejected");
          h.assertTrue(dirt(n) == 32, "Rejecting a path must not consume blocks");
          skills.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }
}

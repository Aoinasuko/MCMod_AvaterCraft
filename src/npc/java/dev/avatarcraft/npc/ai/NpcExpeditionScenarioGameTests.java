package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.JsonObject;
import dev.avatarcraft.npc.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.Container;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** Physical end-to-end missions with fixed plans: no live provider or invented tool results. */
final class NpcExpeditionScenarioGameTests {
  static void register(RegisterGameTestsEvent event) {
    var env =
        event.registerEnvironment(
            bep_3davater_companion.id("expedition_scenarios_qa"), new TestEnvironmentDefinition.AllOf());
    for (String scenario :
        List.of(
            "surface_to_underground_161_blocks",
            "base_village_round_trip_764_blocks",
            "remote_farm_harvest_replant_return_store"))
      event.registerTest(
          bep_3davater_companion.id(scenario),
          new FunctionGameTestInstance(
              BuiltinTestFunctions.ALWAYS_PASS,
              new TestData<>(env, bep_3davater_companion.id("qa_longtravel"), 10000, 1, true)) {
            public void run(GameTestHelper h) {
              if (scenario.startsWith("surface")) descend(h);
              else expedition(h, scenario.startsWith("remote"));
            }
          });
  }

  private static JsonObject at(GameTestHelper h, int x, int y, int z) {
    var p = h.absolutePos(new BlockPos(x, y, z));
    return object("x", p.getX(), "y", p.getY(), "z", p.getZ());
  }

  private static CompanionEntity fixture(GameTestHelper h) {
    for (int x = 0; x <= 528; x++) for (int z = 0; z < 16; z++) h.setBlock(x, 0, z, Blocks.BEDROCK);
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 1))));
    var n = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    n.initialize(owner, "tortobu");
    n.setFollowsOwner(false);
    n.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(4, 1, 5))));
    h.getLevel().addFreshEntity(n);
    n.inventory.setItem(0, new ItemStack(Items.DIRT, 32));
    n.inventory.setItem(8, new ItemStack(Items.BREAD, 16));
    return n;
  }

  private static void descend(GameTestHelper h) {
    var n = fixture(h);
    for (int i = 0; i <= 72; i++)
      for (int z = 3; z <= 7; z++) h.setBlock(4 + i, 72 - i, z, Blocks.STONE);
    n.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(4, 73, 5))));
    var s = new NpcSkills(n, (a, b) -> {});
    var destination = h.absolutePos(new BlockPos(148, 1, 5));
    var result = s.execute("move_to", at(h, 148, 1, 5)).toCompletableFuture();
    Vec3[] last = {n.position()};
    h.onEachTick(
        () -> {
          s.tick();
          h.assertTrue(n.position().distanceToSqr(last[0]) < 4, "No teleport on initial descent");
          last[0] = n.position();
          if (!result.isDone()) return;
          h.assertTrue(
              result.join().get("ok").getAsBoolean(),
              "First descent without a remembered route: " + result.join());
          h.assertTrue(
              result.join().getAsJsonArray("arrived").size() == 2
                  && n.distanceToSqr(Vec3.atBottomCenterOf(destination)) < 1,
              "Actual underground arrival through two reserved legs");
          h.assertTrue(
              n.inventory.getItem(0).getCount() == 32, "Use stairs without placing blocks");
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static TaskPlan.Step move(GameTestHelper h, int x, int y, int z) {
    var p = h.absolutePos(new BlockPos(x, y, z));
    return new TaskPlan.Step(
        "move_to",
        "",
        "",
        1,
        -1,
        -1,
        "main",
        1,
        List.of((double) p.getX(), (double) p.getY(), (double) p.getZ()));
  }

  private static TaskPlan.Step atomic(String tool, JsonObject args) {
    return new TaskPlan.Step(
        "tool", "bep_3davater_companion:" + tool, "", 1, -1, -1, "main", 1, List.of(), "", args.toString());
  }

  private static int carried(CompanionEntity n, Item item) {
    int total = 0;
    for (int i = 0; i < NpcInventory.SIZE; i++)
      if (n.inventory.getItem(i).is(item)) total += n.inventory.getItem(i).getCount();
    return total;
  }

  private static void expedition(GameTestHelper h, boolean farm) {
    var n = fixture(h);
    var s = new NpcSkills(n, (a, b) -> {});
    var steps = new ArrayList<TaskPlan.Step>();
    h.setBlock(3, 1, 7, Blocks.CHEST);
    Entity guest;
    if (farm) {
      guest = null;
      n.inventory.setItem(9, new ItemStack(Items.WHEAT_SEEDS, 32));
      // A contained irrigated field: water is supported by the base rather than draining away.
      for (int x = 382; x <= 392; x++)
        for (int z = 1; z <= 9; z++) h.setBlock(x, 1, z, Blocks.BEDROCK);
      for (int x = 385; x <= 388; x++)
        for (int z : new int[] {3, 7}) {
          h.setBlock(
              new BlockPos(x, 1, z),
              Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7));
          h.setBlock(
              new BlockPos(x, 2, z), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
          h.setBlock(x, 1, z == 3 ? 2 : 8, Blocks.WATER);
        }
      h.setBlock(
          new BlockPos(390, 1, 3),
          Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7));
      h.setBlock(new BlockPos(390, 2, 3), Blocks.WHEAT.defaultBlockState());
      steps.add(move(h, 384, 2, 5));
      steps.add(
          new TaskPlan.Step(
              "collect", "minecraft:wheat", "minecraft:wheat", 8, -1, -1, "main", 1, List.of()));
      steps.add(move(h, 386, 2, 5));
      for (int x = 385; x <= 388; x++)
        for (int z : new int[] {3, 7}) {
          var args = at(h, x, 1, z);
          args.addProperty("slot", 9);
          args.addProperty("face", "up");
          steps.add(atomic("use_on_block", args));
          steps.add(atomic("equip", object("slot", 9, "hand", "main")));
        }
      steps.add(move(h, 4, 1, 5));
      var p = h.absolutePos(new BlockPos(3, 1, 7));
      steps.add(
          new TaskPlan.Step(
              "deposit",
              "minecraft:wheat",
              "",
              8,
              -1,
              -1,
              "main",
              1,
              List.of((double) p.getX(), (double) p.getY(), (double) p.getZ())));
    } else {
      for (int x = 380; x <= 392; x++)
        for (int z = 2; z <= 10; z++)
          for (int y = 1; y <= 4; y++)
            if (x == 380 || x == 392 || z == 2 || z == 10 || y == 4)
              h.setBlock(x, y, z, Blocks.OAK_PLANKS);
      var door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
      h.setBlock(new BlockPos(380, 1, 6), door);
      h.setBlock(new BlockPos(380, 2, 6), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
      var villager = EntityTypes.VILLAGER.create(h.getLevel(), EntitySpawnReason.COMMAND);
      villager.setNoAi(true);
      villager.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(389, 1, 8))));
      h.getLevel().addFreshEntity(villager);
      guest = villager;
      steps.add(move(h, 386, 1, 6));
      steps.add(new TaskPlan.Step("wait", "", "", 1, -1, -1, "main", 2, List.of()));
      steps.add(move(h, 4, 1, 5));
      steps.add(new TaskPlan.Step("wait", "", "", 1, -1, -1, "main", 2, List.of()));
    }
    var runner =
        new LocalTaskRunner(
            new TaskPlan(
                1,
                farm
                    ? "Harvest entire remote farm, replant and store"
                    : "Visit village and return to base",
                "",
                steps),
            new NpcLocalWorld(n, s),
            n.personality().allowedSkills);
    double[] farthest = {0}, walked = {0};
    Vec3[] last = {n.position()};
    boolean[] doorOpened = {false};
    int[] planted = {0}, previousSeeds = {-1};
    h.onEachTick(
        () -> {
          s.tick();
          runner.tick(h.getLevel().getGameTime());
          double movement = n.position().distanceTo(last[0]);
          h.assertTrue(movement < 2, "Round trip must physically walk");
          walked[0] += movement;
          last[0] = n.position();
          farthest[0] =
              Math.max(
                  farthest[0],
                  n.distanceToSqr(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(4, 1, 5)))));
          if (!farm)
            doorOpened[0] |=
                h.getLevel()
                    .getBlockState(h.absolutePos(new BlockPos(380, 1, 6)))
                    .getValue(DoorBlock.OPEN);
          if (farm) {
            int seeds = carried(n, Items.WHEAT_SEEDS);
            if (previousSeeds[0] >= 0 && seeds < previousSeeds[0])
              planted[0] += previousSeeds[0] - seeds;
            previousSeeds[0] = seeds;
          }
          h.assertFalse(
              runner.needsReplan(), "Complete entire mission locally: " + runner.progress());
          if (farm
              && runner.progress().get("step").getAsInt() == 1
              && n.distanceToSqr(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(386, 2, 5))))
                  > 24 * 24) {
            var drops =
                h.getLevel()
                    .getEntitiesOfClass(
                        net.minecraft.world.entity.item.ItemEntity.class,
                        new net.minecraft.world.phys.AABB(
                            Vec3.atCenterOf(h.absolutePos(new BlockPos(380, -8, 0))),
                            Vec3.atCenterOf(h.absolutePos(new BlockPos(400, 8, 15)))),
                        e -> e.isAlive());
            h.assertTrue(
                false,
                "Farm scope escaped: "
                    + runner.progress()
                    + " inventory="
                    + s.observe().get("inventory")
                    + " drops="
                    + drops.stream().map(e -> e.getItem() + " at " + e.position()).toList());
          }
          if (!runner.complete()) return;
          h.assertTrue(
              farthest[0] > 375 * 375 && walked[0] > 750, "Must visit distant location and return");
          h.assertTrue(
              n.distanceToSqr(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(4, 1, 5)))) < 9,
              "Return to base");
          h.assertTrue(
              n.inventory.getItem(0).is(Items.DIRT) && n.inventory.getItem(0).getCount() == 32,
              "No unnecessary block placement");
          if (farm) {
            for (int x = 385; x <= 388; x++)
              for (int z : new int[] {3, 7}) {
                var crop = h.getLevel().getBlockState(h.absolutePos(new BlockPos(x, 2, z)));
                h.assertTrue(
                    crop.is(Blocks.WHEAT) && crop.getValue(CropBlock.AGE) < 7,
                    "Every harvested plot is replanted");
              }
            h.assertTrue(
                h.getLevel().getBlockState(h.absolutePos(new BlockPos(390, 2, 3))).is(Blocks.WHEAT),
                "Preserve immature crop");
            var c = (Container) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(3, 1, 7)));
            int stored = 0;
            for (int i = 0; i < c.getContainerSize(); i++)
              if (c.getItem(i).is(Items.WHEAT)) stored += c.getItem(i).getCount();
            h.assertTrue(
                stored == 8 && carried(n, Items.WHEAT) == 0,
                "All eight harvested wheat must enter base chest");
            h.assertTrue(planted[0] == 8, "Consume one real seed for each replanted plot");
          } else {
            h.assertTrue(doorOpened[0], "Reach village through its door");
            for (int x = 380; x <= 392; x++)
              for (int z = 2; z <= 10; z++)
                for (int y = 1; y <= 4; y++)
                  if ((x == 380 || x == 392 || z == 2 || z == 10 || y == 4)
                      && !(x == 380 && z == 6 && y <= 2))
                    h.assertTrue(
                        h.getLevel()
                            .getBlockState(h.absolutePos(new BlockPos(x, y, z)))
                            .is(Blocks.OAK_PLANKS),
                        "Preserve village buildings");
            guest.discard();
          }
          runner.close();
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }
}

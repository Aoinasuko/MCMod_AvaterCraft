package dev.avatarcraft.npc;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** Native viewer counters, block states and shulker animation; no forced visual-only events. */
public final class NpcInteractionGameTests {
  public static void register(RegisterGameTestsEvent event) {
    var env =
        event.registerEnvironment(
            NpcMod.id("interaction_qa"), new TestEnvironmentDefinition.AllOf());
    test(event, env, "double_chest_shared_openers", false, NpcInteractionGameTests::sharedChest);
    test(event, env, "barrel_transfer_opens_then_closes", false, NpcInteractionGameTests::barrel);
    test(
        event,
        env,
        "shulker_interaction_uses_real_animation",
        false,
        NpcInteractionGameTests::shulker);
    test(
        event,
        env,
        "ender_chest_animation_without_owner_inventory_access",
        false,
        NpcInteractionGameTests::enderChest);
    test(
        event,
        env,
        "cancelled_container_transfer_is_atomic",
        false,
        NpcInteractionGameTests::cancel);
    test(
        event,
        env,
        "blocked_lid_and_observation_stay_closed",
        false,
        NpcInteractionGameTests::blocked);
    test(
        event,
        env,
        "removed_open_container_cancels_transfer",
        false,
        NpcInteractionGameTests::removed);
    test(event, env, "distant_work_movement_sprints", true, NpcInteractionGameTests::travel);
    test(event, env, "wooden_door_path_opens_crosses_closes", false, h -> door(h, false, false));
    test(event, env, "iron_door_is_not_opened_by_hand", false, h -> door(h, true, false));
    test(event, env, "already_open_door_is_preserved", false, h -> door(h, false, true));
    test(
        event,
        env,
        "generic_logs_collect_spruce_and_deposit",
        false,
        NpcInteractionGameTests::spruce);
    test(
        event,
        env,
        "mature_crop_collection_and_real_seed_planting",
        false,
        NpcInteractionGameTests::farm);
    test(
        event,
        env,
        "generic_acquire_logs_and_planks_from_spruce",
        false,
        NpcInteractionGameTests::woodFamilies);
  }

  private static void woodFamilies(GameTestHelper h) {
    var owner = owner(h, 12);
    var n = npc(h, owner, 3, 3);
    n.inventory.setItem(0, new ItemStack(Items.IRON_AXE));
    n.inventory.setItem(1, new ItemStack(Items.BIRCH_LOG));
    h.setBlock(5, 1, 3, Blocks.SPRUCE_LOG);
    h.setBlock(5, 2, 3, Blocks.SPRUCE_LOG);
    var skills = skills(n);
    @SuppressWarnings("unchecked")
    CompletableFuture<com.google.gson.JsonObject>[] future =
        new CompletableFuture[] {
          skills
              .execute("acquire", object("item", "#minecraft:logs", "count", 3))
              .toCompletableFuture()
        };
    boolean[] planks = {false};
    h.onEachTick(
        () -> {
          skills.tick();
          if (!future[0].isDone()) return;
          h.assertTrue(
              future[0].join().get("ok").getAsBoolean(),
              "Tag acquisition must use actual nearby species: " + future[0].join());
          if (!planks[0]) {
            planks[0] = true;
            future[0] =
                skills
                    .execute("acquire", object("item", "#minecraft:planks", "count", 8))
                    .toCompletableFuture();
          } else {
            int total = 0;
            for (int i = 0; i < NpcInventory.SIZE; i++)
              if (n.inventory.getItem(i).is(net.minecraft.tags.ItemTags.PLANKS))
                total += n.inventory.getItem(i).getCount();
            h.assertTrue(
                total >= 8, "Generic planks must combine birch and spruce without requiring oak");
            skills.cancel("Test done");
            n.discard();
            h.succeed();
          }
        });
  }

  private static void farm(GameTestHelper h) {
    var owner = owner(h, 12);
    var n = npc(h, owner, 3, 3);
    n.inventory.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 2));
    h.setBlock(
        new BlockPos(5, 0, 3),
        Blocks.FARMLAND
            .defaultBlockState()
            .setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.MOISTURE, 7));
    h.setBlock(
        new BlockPos(6, 0, 3),
        Blocks.FARMLAND
            .defaultBlockState()
            .setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.MOISTURE, 7));
    h.setBlock(new BlockPos(5, 1, 3), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
    h.setBlock(new BlockPos(6, 1, 3), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 2));
    var s =
        new dev.avatarcraft.npc.ai.TaskPlan.Step(
            "collect", "minecraft:wheat", "minecraft:wheat", 1, -1, -1, "main", 10, List.of());
    var skills = skills(n);
    var world = new dev.avatarcraft.npc.ai.NpcLocalWorld(n, skills);
    var runner =
        new dev.avatarcraft.npc.ai.LocalTaskRunner(
            new dev.avatarcraft.npc.ai.TaskPlan(1, "harvest mature wheat", "", List.of(s)),
            world,
            n.personality().allowedSkills);
    var young =
        new com.google.gson.Gson()
            .toJsonTree(
                List.of(
                    h.absolutePos(new BlockPos(6, 1, 3)).getX() + .5,
                    h.absolutePos(new BlockPos(6, 1, 3)).getY() + .5,
                    h.absolutePos(new BlockPos(6, 1, 3)).getZ() + .5))
            .getAsJsonArray();
    h.assertFalse(world.harvestable(s, young), "Immature crops must be excluded before ranking");
    @SuppressWarnings("unchecked")
    CompletableFuture<com.google.gson.JsonObject>[] planted = new CompletableFuture[1];
    h.onEachTick(
        () -> {
          skills.tick();
          if (planted[0] == null) {
            runner.tick(h.getLevel().getGameTime());
            h.assertFalse(
                runner.needsReplan(), "Mature crop must be harvestable: " + runner.problem());
            if (runner.complete()) {
              var p = h.absolutePos(new BlockPos(5, 0, 3));
              planted[0] =
                  skills
                      .execute(
                          "use_on_block",
                          object(
                              "x", p.getX(), "y", p.getY(), "z", p.getZ(), "slot", 0, "face", "up"))
                      .toCompletableFuture();
            }
          } else if (planted[0].isDone()) {
            h.assertTrue(
                planted[0].join().get("ok").getAsBoolean(),
                "Replant must use a real carried seed: " + planted[0].join());
            h.assertTrue(
                h.getLevel().getBlockState(h.absolutePos(new BlockPos(5, 1, 3))).is(Blocks.WHEAT),
                "Harvested tile must be replanted");
            h.assertTrue(
                h.getLevel().getBlockState(h.absolutePos(new BlockPos(6, 1, 3))).is(Blocks.WHEAT),
                "Immature neighbor must survive");
            runner.close();
            skills.cancel("Test done");
            h.succeed();
          }
        });
  }

  private static void door(GameTestHelper h, boolean iron, boolean initiallyOpen) {
    var owner = owner(h, 12);
    var n = npc(h, owner, 2, 5);
    // Enclosed corridor: going over/around the door cannot pass this test.
    for (int x = 0; x < 12; x++)
      for (int y = 1; y <= 3; y++) {
        h.setBlock(x, y, 4, Blocks.STONE);
        h.setBlock(x, y, 6, Blocks.STONE);
        h.setBlock(x, 3, 5, Blocks.STONE);
      }
    for (int y = 1; y <= 2; y++) {
      h.setBlock(0, y, 5, Blocks.STONE);
      h.setBlock(11, y, 5, Blocks.STONE);
    }
    var p = h.absolutePos(new BlockPos(5, 1, 5));
    var state =
        (iron ? Blocks.IRON_DOOR : Blocks.SPRUCE_DOOR)
            .defaultBlockState()
            .setValue(DoorBlock.FACING, Direction.EAST)
            .setValue(DoorBlock.OPEN, initiallyOpen);
    h.getLevel().setBlockAndUpdate(p, state);
    h.getLevel()
        .setBlockAndUpdate(
            p.above(),
            state.setValue(
                DoorBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
    if (!iron && !initiallyOpen) {
      h.getLevel().setBlockAndUpdate(p.east(2), state);
      h.getLevel()
          .setBlockAndUpdate(
              p.east(2).above(),
              state.setValue(
                  DoorBlock.HALF,
                  net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
    }
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(9, 1, 8))));
    var skills = skills(n);
    var end = h.absolutePos(new BlockPos(9, 1, 5));
    var future =
        skills
            .execute("move_to", object("x", end.getX(), "y", end.getY(), "z", end.getZ()))
            .toCompletableFuture();
    boolean[] opened = {initiallyOpen};
    boolean[] secondOpened = {false};
    long start = h.getLevel().getGameTime();
    h.onEachTick(
        () -> {
          skills.tick();
          if (h.getLevel().getBlockState(p).getValue(DoorBlock.OPEN)) opened[0] = true;
          if (!iron
              && !initiallyOpen
              && h.getLevel().getBlockState(p.east(2)).getValue(DoorBlock.OPEN))
            secondOpened[0] = true;
          if (iron) {
            h.assertFalse(opened[0], "Iron door must not be opened by hand");
            if (h.getLevel().getGameTime() - start > 100) {
              h.assertTrue(n.getX() < p.getX() + 1, "Iron door cannot be bypassed");
              skills.cancel("Test done");
              h.succeed();
            }
          } else if (future.isDone() && h.getLevel().getGameTime() - start > 100) {
            h.assertTrue(
                future.join().get("ok").getAsBoolean(),
                "Door route must complete: " + future.join());
            h.assertTrue(
                opened[0] && n.getX() > p.getX() + 1, "NPC must actually cross an opened door");
            h.assertTrue(
                h.getLevel().getBlockState(p).getValue(DoorBlock.OPEN) == initiallyOpen,
                "Restore initially closed door; preserve initially open door");
            if (!initiallyOpen)
              h.assertTrue(
                  secondOpened[0]
                      && !h.getLevel().getBlockState(p.east(2)).getValue(DoorBlock.OPEN),
                  "The same NPC must open, cross and close a second door on the route");
            skills.cancel("Test done");
            h.succeed();
          }
        });
  }

  private static void spruce(GameTestHelper h) {
    var owner = owner(h, 12);
    var n = npc(h, owner, 3, 3);
    n.inventory.setItem(0, new ItemStack(Items.IRON_AXE));
    n.inventory.setItem(1, new ItemStack(Items.BIRCH_LOG));
    h.setBlock(5, 1, 3, Blocks.SPRUCE_LOG);
    h.setBlock(5, 2, 3, Blocks.SPRUCE_LOG);
    h.setBlock(3, 1, 5, Blocks.CHEST);
    var p = h.absolutePos(new BlockPos(3, 1, 5));
    var s =
        new dev.avatarcraft.npc.ai.TaskPlan.Step(
            "collect", "#minecraft:logs", "#minecraft:logs", 3, -1, -1, "main", 10, List.of());
    var d =
        new dev.avatarcraft.npc.ai.TaskPlan.Step(
            "deposit",
            "#minecraft:logs",
            "",
            3,
            -1,
            -1,
            "main",
            10,
            List.of((double) p.getX(), (double) p.getY(), (double) p.getZ()));
    var skills = skills(n);
    var runner =
        new dev.avatarcraft.npc.ai.LocalTaskRunner(
            new dev.avatarcraft.npc.ai.TaskPlan(1, "collect logs", "", List.of(s, d)),
            new dev.avatarcraft.npc.ai.NpcLocalWorld(n, skills),
            n.personality().allowedSkills);
    h.onEachTick(
        () -> {
          skills.tick();
          runner.tick(h.getLevel().getGameTime());
          h.assertFalse(runner.needsReplan(), "Spruce must count as logs: " + runner.problem());
          if (runner.complete()) {
            var chest = (ChestBlockEntity) h.getLevel().getBlockEntity(p);
            int spruce = 0, birch = 0;
            for (int i = 0; i < chest.getContainerSize(); i++) {
              var v = chest.getItem(i);
              if (v.is(Items.SPRUCE_LOG)) spruce += v.getCount();
              if (v.is(Items.BIRCH_LOG)) birch += v.getCount();
            }
            h.assertTrue(
                spruce == 2 && birch == 1, "Actual mixed-species logs must reach the same chest");
            runner.close();
            skills.cancel("Test done");
            h.succeed();
          }
        });
  }

  private static void test(
      RegisterGameTestsEvent event,
      Holder<TestEnvironmentDefinition<?>> env,
      String name,
      boolean large,
      Consumer<GameTestHelper> body) {
    event.registerTest(
        NpcMod.id(name),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(env, NpcMod.id(large ? "qa_large" : "qa_empty"), 500, 1, true)) {
          public void run(GameTestHelper h) {
            body.accept(h);
          }
        });
  }

  private static ServerPlayer owner(GameTestHelper h, int size) {
    for (int x = 0; x < size; x++) for (int z = 0; z < size; z++) h.setBlock(x, 0, z, Blocks.STONE);
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(8, 1, 8))));
    return owner;
  }

  private static CompanionEntity npc(GameTestHelper h, ServerPlayer owner, int x, int z) {
    var n = NpcMod.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    n.initialize(owner, "tortobu");
    n.setFollowsOwner(false);
    n.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(x, 1, z))));
    h.getLevel().addFreshEntity(n);
    return n;
  }

  private static dev.avatarcraft.npc.ai.NpcSkills skills(CompanionEntity npc) {
    return new dev.avatarcraft.npc.ai.NpcSkills(npc, (s, id) -> {});
  }

  private static void sharedChest(GameTestHelper h) {
    var owner = owner(h, 12);
    var one = npc(h, owner, 3, 3);
    var two = npc(h, owner, 3, 4);
    var p = h.absolutePos(new BlockPos(5, 1, 3));
    var right = p.relative(Direction.WEST);
    h.getLevel()
        .setBlockAndUpdate(
            p,
            Blocks.CHEST
                .defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.SOUTH)
                .setValue(ChestBlock.TYPE, ChestType.LEFT));
    h.getLevel()
        .setBlockAndUpdate(
            right,
            Blocks.CHEST
                .defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.SOUTH)
                .setValue(ChestBlock.TYPE, ChestType.RIGHT));
    var combined =
        ChestBlock.getContainer(
            (ChestBlock) Blocks.CHEST, h.getLevel().getBlockState(p), h.getLevel(), p, false);
    h.assertTrue(combined instanceof CompoundContainer, "Fixture must be a real double chest");
    owner.setPos(Vec3.atBottomCenterOf(p.south(2)));
    owner.containerMenu = ChestMenu.sixRows(77, owner.getInventory(), combined);
    one.inventory.setItem(0, new ItemStack(Items.GOLD_INGOT));
    two.inventory.setItem(0, new ItemStack(Items.IRON_INGOT));
    var a = skills(one);
    var b = skills(two);
    var first =
        a.execute(
                "deposit",
                object("x", p.getX(), "y", p.getY(), "z", p.getZ(), "slot", 0, "count", 1))
            .toCompletableFuture();
    var second =
        b.execute(
                "deposit",
                object("x", p.getX(), "y", p.getY(), "z", p.getZ(), "slot", 0, "count", 1))
            .toCompletableFuture();
    long start = h.getLevel().getGameTime();
    h.onEachTick(
        () -> {
          if (h.getLevel().getGameTime() - start < 14) {
            h.assertTrue(
                ChestBlockEntity.getOpenCount(h.getLevel(), p) == 3
                    && ChestBlockEntity.getOpenCount(h.getLevel(), right) == 3,
                "Both halves must keep player and two NPC viewers across native rechecks");
            return;
          }
          if (!first.isDone() || !second.isDone()) return;
          h.assertTrue(
              first.join().get("moved").getAsInt() == 1
                  && second.join().get("moved").getAsInt() == 1,
              "Both real transfers must commit once");
          h.assertTrue(
              ChestBlockEntity.getOpenCount(h.getLevel(), p) == 1
                  && ChestBlockEntity.getOpenCount(h.getLevel(), right) == 1,
              "NPCs must not close another player's chest");
          owner.containerMenu.removed(owner);
          owner.containerMenu = owner.inventoryMenu;
          h.assertTrue(
              ChestBlockEntity.getOpenCount(h.getLevel(), p) == 0
                  && ChestBlockEntity.getOpenCount(h.getLevel(), right) == 0,
              "Last viewer must close both halves");
          a.cancel("done");
          b.cancel("done");
          one.discard();
          two.discard();
          h.succeed();
        });
  }

  private static void barrel(GameTestHelper h) {
    var owner = owner(h, 12);
    var n = npc(h, owner, 3, 3);
    h.setBlock(5, 1, 3, Blocks.BARREL);
    var p = h.absolutePos(new BlockPos(5, 1, 3));
    n.inventory.setItem(0, new ItemStack(Items.GOLD_INGOT, 2));
    var s = skills(n);
    var result =
        s.execute(
                "container_put",
                object(
                    "x",
                    p.getX(),
                    "y",
                    p.getY(),
                    "z",
                    p.getZ(),
                    "slot",
                    0,
                    "container_slot",
                    0,
                    "count",
                    2))
            .toCompletableFuture();
    long start = h.getLevel().getGameTime();
    h.onEachTick(
        () -> {
          if (!result.isDone()) {
            h.assertTrue(
                h.getLevel().getBlockState(p).getValue(BarrelBlock.OPEN),
                "Barrel must remain visibly open during use");
            h.assertTrue(
                n.inventory.getItem(0).getCount() == 2,
                "Transfer must wait until opening has been visible");
            return;
          }
          h.assertTrue(
              h.getLevel().getGameTime() - start >= 15
                  && result.join().get("moved").getAsInt() == 2,
              "Real transfer follows native opening");
          h.assertTrue(
              !h.getLevel().getBlockState(p).getValue(BarrelBlock.OPEN),
              "Barrel must close after operation");
          s.cancel("done");
          n.discard();
          h.succeed();
        });
  }

  private static void shulker(GameTestHelper h) {
    var owner = owner(h, 12);
    var n = npc(h, owner, 3, 3);
    h.setBlock(5, 1, 3, Blocks.SHULKER_BOX);
    var p = h.absolutePos(new BlockPos(5, 1, 3));
    var box = (ShulkerBoxBlockEntity) h.getLevel().getBlockEntity(p);
    var s = skills(n);
    var result =
        s.execute(
                "interact_block", object("x", p.getX(), "y", p.getY(), "z", p.getZ(), "face", "up"))
            .toCompletableFuture();
    boolean[] animated = {false};
    long start = h.getLevel().getGameTime();
    h.onEachTick(
        () -> {
          animated[0] |= box.getProgress(1) > 0;
          if (h.getLevel().getGameTime() - start < 30) return;
          h.assertTrue(
              result.isDone() && result.join().get("ok").getAsBoolean() && animated[0],
              "Vanilla shulker progress must animate after actual right-click");
          h.assertTrue(box.isClosed() && box.getProgress(1) == 0, "Shulker must finish closing");
          s.cancel("done");
          n.discard();
          h.succeed();
        });
  }

  private static void cancel(GameTestHelper h) {
    var owner = owner(h, 12);
    var n = npc(h, owner, 3, 3);
    h.setBlock(5, 1, 3, Blocks.CHEST);
    var p = h.absolutePos(new BlockPos(5, 1, 3));
    n.inventory.setItem(0, new ItemStack(Items.DIAMOND, 3));
    var s = skills(n);
    var result =
        s.execute(
                "deposit",
                object("x", p.getX(), "y", p.getY(), "z", p.getZ(), "slot", 0, "count", 3))
            .toCompletableFuture();
    long start = h.getLevel().getGameTime();
    boolean[] cancelled = {false};
    h.onEachTick(
        () -> {
          if (h.getLevel().getGameTime() - start < 6) return;
          if (!cancelled[0]) {
            n.setPos(n.position().add(20, 0, 0));
            ((ChestBlockEntity) h.getLevel().getBlockEntity(p)).recheckOpen();
            s.cancel("owner stopped");
            n.setPos(Vec3.atBottomCenterOf(p.west(2)));
            cancelled[0] = true;
          }
          if (h.getLevel().getGameTime() - start < 22) return;
          h.assertTrue(
              result.isDone() && !result.join().get("ok").getAsBoolean(),
              "Cancellation must fail pending operation");
          h.assertTrue(
              n.inventory.getItem(0).getCount() == 3
                  && ((Container) h.getLevel().getBlockEntity(p)).isEmpty(),
              "Cancellation must not commit or duplicate items");
          h.assertTrue(
              ChestBlockEntity.getOpenCount(h.getLevel(), p) == 0,
              "Cancelled viewer must close cleanly across rechecks");
          n.discard();
          h.succeed();
        });
  }

  private static void enderChest(GameTestHelper h) {
    var owner = owner(h, 12);
    var n = npc(h, owner, 3, 3);
    h.setBlock(5, 1, 3, Blocks.ENDER_CHEST);
    var p = h.absolutePos(new BlockPos(5, 1, 3));
    var chest = (EnderChestBlockEntity) h.getLevel().getBlockEntity(p);
    var s = skills(n);
    owner.getEnderChestInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
    var result =
        s.execute(
                "interact_block", object("x", p.getX(), "y", p.getY(), "z", p.getZ(), "face", "up"))
            .toCompletableFuture();
    long start = h.getLevel().getGameTime();
    boolean[] animated = {false};
    h.onEachTick(
        () -> {
          // This is the exact vanilla lid interpolation used by the client renderer.
          EnderChestBlockEntity.lidAnimateTick(
              h.getLevel(), p, h.getLevel().getBlockState(p), chest);
          animated[0] |= chest.getOpenNess(1) > 0;
          if (h.getLevel().getGameTime() - start < 32) return;
          h.assertTrue(
              result.isDone()
                  && result.join().get("ok").getAsBoolean()
                  && animated[0]
                  && chest.getOpenNess(1) == 0,
              "Native ender chest events must open and close the lid");
          h.assertTrue(
              owner.getEnderChestInventory().getItem(0).getCount() == 3,
              "Animation must never open or modify the owner's private ender inventory");
          s.cancel("done");
          n.discard();
          h.succeed();
        });
  }

  private static void blocked(GameTestHelper h) {
    var owner = owner(h, 12);
    var n = npc(h, owner, 3, 3);
    h.setBlock(5, 1, 3, Blocks.CHEST);
    h.setBlock(5, 2, 3, Blocks.STONE);
    var p = h.absolutePos(new BlockPos(5, 1, 3));
    n.inventory.setItem(0, new ItemStack(Items.GOLD_INGOT));
    var s = skills(n);
    var result =
        s.execute(
                "deposit",
                object("x", p.getX(), "y", p.getY(), "z", p.getZ(), "slot", 0, "count", 1))
            .toCompletableFuture();
    var inspect =
        s.execute("inspect_block", object("x", p.getX(), "y", p.getY(), "z", p.getZ()))
            .toCompletableFuture();
    h.onEachTick(
        () -> {
          if (!result.isDone() || !inspect.isDone()) return;
          h.assertTrue(
              !result.join().get("ok").getAsBoolean()
                  && ChestBlockEntity.getOpenCount(h.getLevel(), p) == 0,
              "Blocked lid and observation must not open chest");
          s.cancel("done");
          n.discard();
          h.succeed();
        });
  }

  private static void removed(GameTestHelper h) {
    var owner = owner(h, 12);
    var n = npc(h, owner, 3, 3);
    h.setBlock(5, 1, 3, Blocks.BARREL);
    var p = h.absolutePos(new BlockPos(5, 1, 3));
    n.inventory.setItem(0, new ItemStack(Items.DIAMOND));
    var s = skills(n);
    var result =
        s.execute(
                "container_put",
                object(
                    "x",
                    p.getX(),
                    "y",
                    p.getY(),
                    "z",
                    p.getZ(),
                    "slot",
                    0,
                    "container_slot",
                    0,
                    "count",
                    1))
            .toCompletableFuture();
    h.getLevel().removeBlock(p, false);
    h.onEachTick(
        () -> {
          if (!result.isDone()) return;
          h.assertTrue(
              !result.join().get("ok").getAsBoolean() && n.inventory.getItem(0).is(Items.DIAMOND),
              "Removed container must cancel before moving inventory");
          s.cancel("done");
          n.discard();
          h.succeed();
        });
  }

  private static void travel(GameTestHelper h) {
    var owner = owner(h, 40);
    var n = npc(h, owner, 3, 3);
    var s = skills(n);
    var p = h.absolutePos(new BlockPos(30, 1, 3));
    var result =
        s.execute("move_to", object("x", p.getX(), "y", p.getY(), "z", p.getZ()))
            .toCompletableFuture();
    boolean[] sprinted = {false}, walked = {false};
    h.onEachTick(
        () -> {
          s.tick();
          double distance = n.distanceToSqr(Vec3.atBottomCenterOf(p));
          sprinted[0] |= distance > 64 && n.isSprinting();
          walked[0] |= distance < 16 && !n.isSprinting();
          if (!result.isDone()) return;
          h.assertTrue(
              result.join().get("ok").getAsBoolean() && sprinted[0] && walked[0],
              "Distant work travel must sprint, then walk near destination and arrive");
          h.assertTrue(!n.isSprinting(), "Arrival must clear sprint state");
          s.cancel("done");
          n.discard();
          h.succeed();
        });
  }
}

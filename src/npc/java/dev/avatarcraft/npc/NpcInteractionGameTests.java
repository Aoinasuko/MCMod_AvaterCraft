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

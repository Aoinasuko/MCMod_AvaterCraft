package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.*;
import dev.avatarcraft.npc.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

final class NpcRecoveryGameTests {
  static void register(RegisterGameTestsEvent event) {
    var env =
        event.registerEnvironment(NpcMod.id("recovery_qa"), new TestEnvironmentDefinition.AllOf());
    test(
        event,
        env,
        "chest_target_resolves_adjacent_cell_and_transfers",
        false,
        NpcRecoveryGameTests::chest);
    test(
        event,
        env,
        "scaffolding_ignores_unreachable_dirt_drop",
        false,
        NpcRecoveryGameTests::materials);
    test(
        event,
        env,
        "failed_move_shared_between_child_executors",
        false,
        NpcRecoveryGameTests::failedMove);
    test(
        event,
        env,
        "grounded_overhanging_start_reaches_stable_path",
        false,
        NpcRecoveryGameTests::overhang);
    test(
        event,
        env,
        "search_results_shared_with_next_phase_and_peer",
        false,
        NpcRecoveryGameTests::searches);
    test(event, env, "read_both_faces_of_visible_sign_types", false, NpcRecoveryGameTests::signs);
    var deepEnv =
        event.registerEnvironment(
            NpcMod.id("recovery_return_qa"), new TestEnvironmentDefinition.AllOf());
    test(
        event,
        deepEnv,
        "return_72_blocks_up_without_edits",
        true,
        NpcRecoveryGameTests::returnStairs);
    test(
        event,
        env,
        "external_repair_reservations_then_verified_completion",
        false,
        NpcRecoveryGameTests::repair);
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
            new TestData<>(env, NpcMod.id(large ? "qa_return" : "qa_empty"), 6000, 1, true)) {
          public void run(GameTestHelper h) {
            body.accept(h);
          }
        });
  }

  private static CompanionEntity npc(GameTestHelper h) {
    for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.BEDROCK);
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 1))));
    var n = NpcMod.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    n.initialize(owner, "tortobu");
    n.setFollowsOwner(false);
    n.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3, 1, 4))));
    h.getLevel().addFreshEntity(n);
    return n;
  }

  private static JsonObject at(GameTestHelper h, int x, int y, int z) {
    var p = h.absolutePos(new BlockPos(x, y, z));
    return object("x", p.getX(), "y", p.getY(), "z", p.getZ());
  }

  private static int count(CompanionEntity n, Item item) {
    int total = 0;
    for (int i = 0; i < NpcInventory.SIZE; i++)
      if (n.inventory.getItem(i).is(item)) total += n.inventory.getItem(i).getCount();
    return total;
  }

  private static void chest(GameTestHelper h) {
    var n = npc(h);
    var s = new NpcSkills(n, (text, id) -> {});
    h.setBlock(10, 1, 4, Blocks.CHEST);
    n.inventory.setItem(0, new ItemStack(Items.SPRUCE_LOG, 4));
    var destination = at(h, 10, 1, 4);
    var move = s.execute("move_to", destination).toCompletableFuture();
    var transfer = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<JsonObject>>();
    h.onEachTick(
        () -> {
          s.tick();
          if (!move.isDone()) return;
          h.assertTrue(move.join().get("ok").getAsBoolean(), "Chest approach: " + move.join());
          h.assertFalse(
              n.blockPosition().equals(h.absolutePos(new BlockPos(10, 1, 4))),
              "Must stand beside chest");
          if (transfer.get() == null) {
            var args = destination.deepCopy();
            args.addProperty("slot", 0);
            args.addProperty("count", 4);
            transfer.set(s.execute("deposit", args).toCompletableFuture());
            return;
          }
          if (!transfer.get().isDone()) return;
          h.assertTrue(
              transfer.get().join().get("ok").getAsBoolean(),
              "Actual chest transfer: " + transfer.get().join());
          h.assertTrue(
              transfer.get().join().get("moved").getAsInt() == 4 && count(n, Items.SPRUCE_LOG) == 0,
              "Actual carried logs must transfer");
          var box =
              (ChestBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(10, 1, 4)));
          h.assertTrue(
              box.getItem(0).is(Items.SPRUCE_LOG) && box.getItem(0).getCount() == 4,
              "Verify chest content");
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static void materials(GameTestHelper h) {
    var n = npc(h);
    var s = new NpcSkills(n, (text, id) -> {});
    n.inventory.setItem(0, new ItemStack(Items.IRON_SHOVEL));
    h.setBlock(5, 1, 4, Blocks.DIRT);
    h.setBlock(5, 1, 5, Blocks.DIRT);
    var p = h.absolutePos(new BlockPos(6, 9, 4));
    var floating =
        new ItemEntity(
            h.getLevel(), p.getX() + .5, p.getY(), p.getZ() + .5, new ItemStack(Items.DIRT, 8));
    floating.setNoGravity(true);
    h.getLevel().addFreshEntity(floating);
    var acquire =
        s.execute("acquire", object("item", "minecraft:dirt", "count", 2)).toCompletableFuture();
    h.onEachTick(
        () -> {
          s.tick();
          if (!acquire.isDone()) return;
          h.assertTrue(
              acquire.join().get("ok").getAsBoolean(),
              "Must mine alternate dirt: " + acquire.join());
          h.assertTrue(
              count(n, Items.DIRT) >= 2 && floating.isAlive(),
              "Use reachable terrain instead of floating drop");
          h.assertTrue(
              h.getLevel().getBlockState(h.absolutePos(new BlockPos(6, 8, 4))).isAir(),
              "No scaffold-for-scaffold cycle");
          s.cancel("Test done");
          floating.discard();
          n.discard();
          h.succeed();
        });
  }

  private static void overhang(GameTestHelper h) {
    var n = npc(h);
    for (int z = 4; z <= 9; z++) h.setBlock(4, 1, z, Blocks.STONE);
    n.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(4, 2, 4))).add(.58, 0, 0));
    var s = new NpcSkills(n, (text, id) -> {});
    var future = s.execute("move_to", at(h, 4, 2, 9)).toCompletableFuture();
    boolean[] observedOverhang = {false};
    h.onEachTick(
        () -> {
          observedOverhang[0] |=
              n.onGround() && h.getLevel().getBlockState(n.blockPosition().below()).isAir();
          s.tick();
          if (!future.isDone()) return;
          h.assertTrue(
              future.join().get("ok").getAsBoolean(),
              "Walk off grounded overhang: " + future.join());
          h.assertTrue(observedOverhang[0], "Exercise actual grounded centre-over-air start");
          h.assertTrue(
              n.distanceToSqr(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(4, 2, 9)))) < 2,
              "Actual arrival on stable ledge");
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static void failedMove(GameTestHelper h) {
    var n = npc(h);
    var s = new NpcSkills(n, (text, id) -> {});
    var target = at(h, 6, 9, 4);
    var first = s.execute("move_precisely", target).toCompletableFuture();
    var second = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<JsonObject>>();
    h.onEachTick(
        () -> {
          s.tick();
          if (!first.isDone()) return;
          h.assertFalse(first.join().get("ok").getAsBoolean(), "Unsupported destination must fail");
          if (second.get() == null) {
            second.set(
                new NpcSkills(n, (t, id) -> {}).execute("move_to", target).toCompletableFuture());
            return;
          }
          if (!second.get().isDone()) return;
          h.assertTrue(
              second.get().join().get("error").getAsString().contains("TARGET_COOLDOWN"),
              "New child must not repeat previous path search");
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static void searches(GameTestHelper h) {
    var n = npc(h);
    h.setBlock(5, 1, 4, Blocks.SPRUCE_LOG);
    var s = new NpcSkills(n, (text, id) -> {});
    var found =
        s.execute("find_blocks", object("block", "minecraft:spruce_log", "radius", 16))
            .toCompletableFuture();
    var peer = NpcMod.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    var owner = h.getLevel().getServer().getPlayerList().getPlayer(n.ownerId());
    peer.initialize(owner, "tortobu");
    peer.setFollowsOwner(false);
    peer.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3, 1, 8))));
    h.getLevel().addFreshEntity(peer);
    h.onEachTick(
        () -> {
          if (!found.isDone()) return;
          var next = new NpcLocalWorld(n, new NpcSkills(n, (t, id) -> {})).planningObservation();
          var memory = next.getAsJsonObject("execution_memory").getAsJsonArray("searches");
          h.assertTrue(
              !memory.isEmpty() && memory.toString().contains("spruce_log"),
              "Next phase retains actual search results");
          var other =
              new NpcLocalWorld(peer, new NpcSkills(peer, (t, id) -> {})).planningObservation();
          h.assertTrue(
              other.get("peer_findings").toString().contains("spruce_log"),
              "Same owner peer receives discoveries");
          n.discard();
          peer.discard();
          h.succeed();
        });
  }

  private static void signs(GameTestHelper h) {
    var n = npc(h);
    var s = new NpcSkills(n, (text, id) -> {});
    h.setBlock(5, 1, 3, Blocks.OAK_SIGN);
    h.setBlock(3, 1, 8, Blocks.STONE);
    h.setBlock(3, 1, 7, Blocks.OAK_WALL_SIGN);
    h.setBlock(7, 3, 7, Blocks.STONE);
    h.setBlock(7, 2, 7, Blocks.OAK_HANGING_SIGN);
    for (var p : List.of(new BlockPos(5, 1, 3), new BlockPos(3, 1, 7), new BlockPos(7, 2, 7))) {
      var sign = (SignBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(p));
      sign.setText(
          sign.getText(SignTextSlot.FRONT)
              .asMutable()
              .setLine(0, Component.literal("Wood storage"))
              .asImmutable(),
          SignTextSlot.FRONT);
      sign.setText(
          sign.getText(SignTextSlot.BACK)
              .asMutable()
              .setLine(0, Component.literal("原木の収納先"))
              .asImmutable(),
          SignTextSlot.BACK);
    }
    var result = s.execute("read_signs", object("radius", 12)).toCompletableFuture();
    h.onEachTick(
        () -> {
          if (!result.isDone()) return;
          var text = result.join();
          h.assertTrue(text.get("ok").getAsBoolean(), "Sign reading must succeed");
          h.assertTrue(
              text.getAsJsonArray("signs").size() == 3,
              "Read standing/wall/hanging signs: " + text);
          for (var entry : text.getAsJsonArray("signs")) {
            var sign = entry.getAsJsonObject();
            h.assertTrue(
                sign.getAsJsonArray("front").get(0).getAsString().equals("Wood storage")
                    && sign.getAsJsonArray("back").get(0).getAsString().equals("原木の収納先"),
                "Both real faces must be preserved");
            h.assertTrue(
                sign.get("untrustedWorldText").getAsBoolean(),
                "Text must remain environmental data");
          }
          n.discard();
          h.succeed();
        });
  }

  private static void returnStairs(GameTestHelper h) {
    var n = npc(h);
    for (int i = 0; i <= 72; i++)
      for (int z = 19; z <= 21; z++) h.setBlock(4 + i, 72 - i, z, Blocks.STONE);
    var start = h.absolutePos(new BlockPos(4, 73, 20));
    n.setPos(Vec3.atBottomCenterOf(start));
    var s = new NpcSkills(n, (text, id) -> {});
    var invalid = new ArrayList<Integer>();
    for (int i = 0; i <= 72; i++)
      if (!NpcWalkingRoute.validPoints(n, List.of(h.absolutePos(new BlockPos(4 + i, 73 - i, 20)))))
        invalid.add(i);
    h.assertTrue(invalid.isEmpty(), "Stair cells must be walkable: " + invalid);
    var down = s.execute("move_to", at(h, 76, 1, 20)).toCompletableFuture();
    var up = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<JsonObject>>();
    h.onEachTick(
        () -> {
          s.tick();
          if (!down.isDone()) return;
          h.assertTrue(
              down.join().get("ok").getAsBoolean(),
              "Walk actual descending stairs: "
                  + down.join()
                  + " actor="
                  + n.position()
                  + " start="
                  + start
                  + " floor="
                  + h.getLevel().getBlockState(start.below())
                  + " to="
                  + at(h, 76, 1, 20));
          if (up.get() == null) {
            var args = at(h, 4, 73, 20);
            args.addProperty("goal", "reach");
            args.addProperty("mode", "both");
            args.addProperty("max_blocks", 32);
            up.set(s.execute("work_route", args).toCompletableFuture());
            return;
          }
          if (!up.get().isDone()) return;
          h.assertTrue(
              up.get().join().get("ok").getAsBoolean(),
              "Return beyond old +20 limit: " + up.get().join());
          h.assertTrue(
              n.distanceToSqr(Vec3.atBottomCenterOf(start)) < 2, "Actual arrival at surface");
          for (int i = 0; i <= 72; i++)
            h.assertTrue(
                h.getLevel()
                    .getBlockState(h.absolutePos(new BlockPos(4 + i, 72 - i, 20)))
                    .is(Blocks.STONE),
                "Return must preserve stairs");
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static TaskPlan.Step tool(String name, JsonObject args) {
    return new TaskPlan.Step(
        "tool", "avatarcraftnpc:" + name, "", 1, -1, -1, "main", 1, List.of(), "", args.toString());
  }

  private static void repair(GameTestHelper h) {
    var n = npc(h);
    h.setBlock(10, 1, 4, Blocks.CHEST);
    n.inventory.setItem(0, new ItemStack(Items.SPRUCE_LOG, 4));
    n.setInstruction("原木4個をチェストへ収納して待機する");
    int[] plans = {0};
    var lateSpeech = new CompletableFuture<NpcBrain.Reply>();
    NpcBrain brain =
        new NpcBrain() {
          public CompletableFuture<Void> initialize() {
            return CompletableFuture.completedFuture(null);
          }

          public boolean connected() {
            return true;
          }

          public String status() {
            return "Isolated recovery fixture";
          }

          public void close() {}

          public CompletableFuture<? extends Session> open(
              Personality p, BiFunction<String, JsonObject, CompletionStage<JsonObject>> tools) {
            return CompletableFuture.completedFuture(
                new Session() {
                  boolean closed;

                  public boolean busy() {
                    return false;
                  }

                  public boolean connected() {
                    return !closed;
                  }

                  public boolean canStart(boolean voice) {
                    return !closed;
                  }

                  public CompletableFuture<Reply> turn(String prompt, boolean speechOnly) {
                    if (prompt.contains("VERIFIED_GOAL_COMPLETION")) {
                      h.assertTrue(p.allowedSkills.isEmpty(), "Completion narrator cannot act");
                      return CompletableFuture.completedFuture(
                          new Reply("原木はちゃんとしまえたよ！ここで待ってるね。", true));
                    }
                    return lateSpeech;
                  }

                  public CompletableFuture<TaskPlan> plan(String prompt) {
                    int call = ++plans[0];
                    if (call <= 2) {
                      var steps = new ArrayList<TaskPlan.Step>();
                      if (call == 1)
                        steps.add(
                            new TaskPlan.Step("wait", "", "", 1, -1, -1, "main", 46, List.of()));
                      steps.add(tool("move_precisely", at(h, 5, 9, 4)));
                      return CompletableFuture.completedFuture(
                          new TaskPlan(1, "Attempt", "", steps));
                    }
                    if (call == 3) {
                      h.assertTrue(
                          prompt.contains("RECOVERY_RESERVATION")
                              && prompt.contains("failedTargets"),
                          "External AI gets bounded repair request and failures");
                      return CompletableFuture.completedFuture(
                          new TaskPlan(
                              1,
                              "Three repair slots",
                              "",
                              List.of(
                                  tool("read_signs", object("radius", 8)),
                                  waitStep(),
                                  tool("move_to", at(h, 10, 1, 4)))));
                    }
                    h.assertTrue(
                        call == 4 && prompt.contains("verifiedRecovery"),
                        "Resume original goal with actual repaired progress");
                    var p = h.absolutePos(new BlockPos(10, 1, 4));
                    return CompletableFuture.completedFuture(
                        new TaskPlan(
                            1,
                            "Store four logs",
                            "",
                            List.of(
                                new TaskPlan.Step(
                                    "deposit",
                                    "minecraft:spruce_log",
                                    "",
                                    4,
                                    -1,
                                    -1,
                                    "main",
                                    1,
                                    List.of(
                                        (double) p.getX(),
                                        (double) p.getY(),
                                        (double) p.getZ())))));
                  }

                  public void stop() {
                    closed = true;
                  }

                  public void close() {
                    closed = true;
                  }
                });
          }
        };
    var mind = new NpcMind(n, NpcService.get(h.getLevel().getServer()), brain);
    var read =
        new java.util.concurrent.atomic.AtomicReference<
            CompletableFuture<dev.avatarcraft.npc.memory.NpcJournal.Page>>();
    int[] finishedTicks = {0};
    h.onEachTick(
        () -> {
          mind.tick();
          if (!n.instruction().isEmpty()) return;
          h.assertTrue(
              plans[0] == 4 && count(n, Items.SPRUCE_LOG) == 0,
              "Original storage mission must actually finish");
          h.assertTrue(
              n.executionMemory.hasCompletedGoal() && !n.followsOwner(),
              "Confirmed success returns to standby");
          lateSpeech.complete(new NpcBrain.Reply("動作が完了したかどうかは未確認です", false));
          if (++finishedTicks[0] < 100) return;
          var service = NpcService.get(h.getLevel().getServer());
          if (read.get() == null) {
            read.set(
                service.readJournal(() -> service.journal.read(n.ownerId(), n.getUUID(), "", 30)));
            return;
          }
          if (!read.get().isDone()) return;
          var entries = read.get().join().entries();
          h.assertTrue(
              entries.stream().filter(e -> e.kind().equals("goal_complete")).count() == 1,
              "One verified completion event");
          h.assertTrue(
              entries.stream()
                      .filter(
                          e ->
                              e.kind().equals("important_notice")
                                  && e.text().equals("原木はちゃんとしまえたよ！ここで待ってるね。"))
                      .count()
                  == 1,
              "One completion notification");
          h.assertFalse(
              entries.stream().anyMatch(e -> e.kind().equals("speech") && e.text().contains("未確認")),
              "Discard stale generated uncertainty");
          mind.close();
          n.discard();
          h.succeed();
        });
  }

  private static TaskPlan.Step waitStep() {
    return new TaskPlan.Step("wait", "", "", 1, -1, -1, "main", 1, List.of());
  }
}

package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.avatarcraft.npc.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

final class NpcAnchorGameTests {
  static void register(RegisterGameTestsEvent e) {
    if (Boolean.getBoolean("avatarcraft.anchorLive")) {
      test(e, "anchor_live_ai_named_storage", h -> storage(h, false, true));
      return;
    }
    test(e, "anchor_shared_persistent_rename_remove", NpcAnchorGameTests::shared);
    test(e, "anchor_editor_rejects_forged_and_remote_saves", NpcAnchorGameTests::editing);
    test(e, "anchor_and_journal_real_recipes", NpcAnchorGameTests::recipes);
    test(e, "anchor_named_storage_uses_only_nearby_chests", h -> storage(h, false, false));
    test(e, "anchor_full_storage_never_falls_back_to_home", h -> storage(h, true, false));
    test(e, "anchor_withdraw_from_boundary_chest", NpcAnchorGameTests::withdraw);
    test(e, "anchor_mining_drops_item_and_removes_location", NpcAnchorGameTests::mining);
  }

  private static void test(RegisterGameTestsEvent e, String name, Consumer<GameTestHelper> run) {
    var env = e.registerEnvironment(bep_3davater_companion.id(name), new TestEnvironmentDefinition.AllOf());
    e.registerTest(
        bep_3davater_companion.id(name),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(
                env,
                bep_3davater_companion.id("qa_complex"),
                Boolean.getBoolean("avatarcraft.anchorLive") ? 200000 : 7200,
                1,
                true)) {
          public void run(GameTestHelper h) {
            for (int x = 0; x < 64; x++)
              for (int z = 0; z < 16; z++) h.setBlock(x, 0, z, Blocks.BEDROCK);
            run.accept(h);
          }
        });
  }

  private static BlockPos p(GameTestHelper h, int x, int z) {
    return h.absolutePos(new BlockPos(x, 1, z));
  }

  private static ServerPlayer owner(GameTestHelper h) {
    var player = h.makeMockServerPlayerInLevel();
    player.setPos(Vec3.atBottomCenterOf(p(h, 2, 3)));
    return player;
  }

  private static CompanionEntity npc(GameTestHelper h, ServerPlayer owner) {
    var n = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    n.initialize(owner, "tortobu");
    n.setFollowsOwner(false);
    n.setPos(Vec3.atBottomCenterOf(p(h, 3, 5)));
    h.getLevel().addFreshEntity(n);
    return n;
  }

  private static AvatarAnchorBlockEntity anchor(GameTestHelper h, int x, int z, String id) {
    h.setBlock(x, 1, z, bep_3davater_companion.AVATAR_ANCHOR.get());
    var b = (AvatarAnchorBlockEntity) h.getLevel().getBlockEntity(p(h, x, z));
    b.setAnchorId(id);
    return b;
  }

  private static Container chest(GameTestHelper h, int x, int z) {
    h.setBlock(x, 1, z, Blocks.CHEST);
    return (Container) h.getLevel().getBlockEntity(p(h, x, z));
  }

  private static int count(Container c, Item item) {
    int n = 0;
    for (int i = 0; i < c.getContainerSize(); i++)
      if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
    return n;
  }

  private static void shared(GameTestHelper h) {
    var a = anchor(h, 6, 5, "共有拠点");
    var n1 = npc(h, owner(h));
    var n2 = npc(h, owner(h));
    for (int i = 0; i < 8; i++)
      n1.locations.remember(
          new NpcLocationMemory.Place("memory" + i, "minecraft:overworld", i, 64, 0, "point"));
    var first = new NpcLocalWorld(n1, new NpcSkills(n1, (s, id) -> {})).sharedLocation("共有拠点");
    var second = new NpcLocalWorld(n2, new NpcSkills(n2, (s, id) -> {})).sharedLocation("共有拠点");
    h.assertTrue(
        first != null && first.equals(second) && !n1.ownerId().equals(n2.ownerId()),
        "Different owners share anchor");
    h.assertTrue(n1.locations.entries().size() == 8, "Anchor does not use personal memory slots");
    var index = NpcAnchors.get(h.getLevel().getServer());
    var encoded = NpcAnchors.TYPE.codec().encodeStart(JsonOps.INSTANCE, index).getOrThrow();
    h.assertTrue(
        NpcAnchors.TYPE.codec().parse(JsonOps.INSTANCE, encoded).getOrThrow().index.get("共有拠点")
            != null,
        "World index survives codec save/load");
    var restored = new AvatarAnchorBlockEntity(a.getBlockPos(), a.getBlockState());
    restored.loadCustomOnly(
        net.minecraft.world.level.storage.TagValueInput.create(
            net.minecraft.util.ProblemReporter.DISCARDING,
            h.getLevel().registryAccess(),
            a.saveWithoutMetadata(h.getLevel().registryAccess())));
    h.assertTrue(restored.anchorId().equals("共有拠点"), "Block entity retains ID");
    a.setAnchorId("新しい拠点");
    h.assertTrue(
        index.index.get("共有拠点") == null && index.resolve(h.getLevel().getServer(), "新しい拠点") != null,
        "Rename invalidates old name");
    h.setBlock(6, 1, 5, Blocks.AIR);
    h.assertTrue(index.index.get("新しい拠点") == null, "Removal clears world index");
    n1.discard();
    n2.discard();
    h.succeed();
  }

  private static void editing(GameTestHelper h) {
    var player = owner(h);
    var a = anchor(h, 4, 4, "");
    var opened = AnchorEditing.open(player, a.getBlockPos());
    var data = object("token", opened.get("token"), "id", "  拠点の倉庫  ");
    var other = owner(h);
    h.assertTrue(
        !AnchorEditing.save(other, data).get("ok").getAsBoolean(),
        "Forged other player's session rejected");
    player.setPos(Vec3.atBottomCenterOf(p(h, 30, 4)));
    h.assertTrue(!AnchorEditing.save(player, data).get("ok").getAsBoolean(), "Far edit rejected");
    player.setPos(Vec3.atBottomCenterOf(p(h, 2, 3)));
    h.assertTrue(
        AnchorEditing.save(player, data).get("ok").getAsBoolean() && a.anchorId().equals("拠点の倉庫"),
        "Server save normalizes ID");
    var b = anchor(h, 7, 4, "別の拠点");
    data.addProperty("id", "別の拠点");
    h.assertTrue(
        !AnchorEditing.save(player, data).get("ok").getAsBoolean() && a.anchorId().equals("拠点の倉庫"),
        "Duplicate cannot steal ID or erase current ID");
    h.setBlock(4, 1, 4, Blocks.AIR);
    anchor(h, 4, 4, "");
    h.assertTrue(
        !AnchorEditing.save(player, data).get("ok").getAsBoolean(),
        "Replaced block rejects old editor");
    h.setBlock(4, 1, 4, Blocks.AIR);
    h.setBlock(7, 1, 4, Blocks.AIR);
    h.succeed();
  }

  private static void recipes(GameTestHelper h) {
    var n = npc(h, owner(h));
    h.setBlock(4, 1, 5, Blocks.CRAFTING_TABLE);
    n.inventory.setItem(0, new ItemStack(Items.IRON_INGOT, 16));
    n.inventory.setItem(1, new ItemStack(Items.WARPED_PLANKS));
    n.inventory.setItem(2, new ItemStack(Items.BOOK));
    var s = new NpcSkills(n, (t, id) -> {});
    h.assertTrue(
        s.execute("craft", object("recipe", "bep_3davater_companion:avatar_anchor"))
            .toCompletableFuture()
            .join()
            .get("ok")
            .getAsBoolean(),
        "Anchor crafts from any planks plus 8 iron");
    h.assertTrue(
        count(n.inventory, Items.IRON_INGOT) == 8
            && count(n.inventory, bep_3davater_companion.ANCHOR_ITEM.get()) == 1,
        "Anchor consumed exactly 8 iron");
    h.assertTrue(
        s.execute("craft", object("recipe", "bep_3davater_companion:memory_journal"))
            .toCompletableFuture()
            .join()
            .get("ok")
            .getAsBoolean(),
        "Journal crafts from central book surrounded by iron");
    h.assertTrue(
        count(n.inventory, Items.IRON_INGOT) == 0
            && count(n.inventory, Items.BOOK) == 0
            && count(n.inventory, bep_3davater_companion.MEMORY_JOURNAL.get()) == 1,
        "Journal consumed 8 iron and one book");
    n.discard();
    h.succeed();
  }

  private static void storage(GameTestHelper h, boolean full, boolean live) {
    var n = npc(h, owner(h));
    String id = live ? "拠点の倉庫" : full ? "満杯の倉庫" : "移送先の倉庫";
    var a = anchor(h, 40, 5, id);
    var home = chest(h, 5, 8);
    var near = chest(h, 41, 5);
    var target = chest(h, 48, 5);
    var outside = chest(h, 60, 5);
    for (int i = 0; i < 27; i++) near.setItem(i, new ItemStack(Items.DIRT, 64));
    if (full) for (int i = 0; i < 27; i++) target.setItem(i, new ItemStack(Items.DIRT, 64));
    n.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 8));
    n.inventory.setItem(1, new ItemStack(Items.IRON_INGOT, 3));
    n.inventory.setItem(41, new ItemStack(Items.GOLD_INGOT, 2));
    var s = new NpcSkills(n, (text, who) -> {});
    var steps = new ArrayList<TaskPlan.Step>();
    for (var item : List.of(Items.COBBLESTONE, Items.IRON_INGOT, Items.GOLD_INGOT))
      steps.add(
          new TaskPlan.Step(
              "deposit",
              NpcRecipes.id(item),
              "",
              item == Items.COBBLESTONE ? 8 : item == Items.IRON_INGOT ? 3 : 2,
              -1,
              -1,
              "main",
              10,
              List.of(),
              id));
    var local =
        live
            ? null
            : new LocalTaskRunner(
                new TaskPlan(1, "Named anchor storage", "", steps),
                new NpcLocalWorld(n, s),
                n.personality().allowedSkills);
    NpcComplexLiveBrain brain;
    if (live) {
      // Uses the same real OAuth provider seam as the complex end-to-end suite.
      System.setProperty("avatarcraft.complexLive", "true");
      brain =
          new NpcComplexLiveBrain(
              "anchor-storage",
              () ->
                  "Use the actual shared_anchors observation. Store all carried items at the"
                      + " requested anchor. No fixture TaskPlan is supplied.");
      n.setInstruction("拠点の倉庫のチェストに持っているアイテムをすべて収納して");
      n.gameTestMind(new NpcMind(n, NpcService.get(h.getLevel().getServer()), brain));
    } else brain = null;
    final boolean[] finished = {false};
    final int[] ticks = {0};
    long start = System.nanoTime();
    h.onEachTick(
        () -> {
          if (finished[0]) return;
          if (live && brain.waiting()) {
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
            return;
          }
          ticks[0]++;
          if (!live) {
            s.tick();
            local.tick(h.getLevel().getGameTime());
          }
          boolean stored =
              count(target, Items.COBBLESTONE) == 8
                  && count(target, Items.IRON_INGOT) == 3
                  && count(target, Items.GOLD_INGOT) == 2;
          boolean done =
              full
                  ? local.needsReplan()
                  : stored && (live ? n.instruction().isBlank() : local.complete());
          if (done || ticks[0] >= 6500 || System.nanoTime() - start > TimeUnit.MINUTES.toNanos(5)) {
            boolean valid =
                done
                    && count(home, Items.COBBLESTONE) == 0
                    && count(home, Items.IRON_INGOT) == 0
                    && count(outside, Items.COBBLESTONE) == 0;
            if (full)
              valid &=
                  count(n.inventory, Items.COBBLESTONE) == 8
                      && count(n.inventory, Items.IRON_INGOT) == 3;
            if (live) {
              try {
                var evidence =
                    object(
                        "success",
                        valid,
                        "requests",
                        brain.requests,
                        "plans",
                        new Gson().toJsonTree(brain.plans),
                        "errors",
                        new Gson().toJsonTree(brain.errors),
                        "state",
                        n.mind().saveState(),
                        "targetItems",
                        object(
                            "cobblestone",
                            count(target, Items.COBBLESTONE),
                            "iron",
                            count(target, Items.IRON_INGOT),
                            "gold",
                            count(target, Items.GOLD_INGOT)));
                java.nio.file.Files.writeString(
                    java.nio.file.Path.of("../build/anchor-live-result.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(evidence));
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            }
            finished[0] = true;
            if (local != null) local.close();
            s.cancel("Test done");
            if (brain != null) brain.close();
            n.discard();
            h.setBlock(40, 1, 5, Blocks.AIR);
            h.assertTrue(
                valid,
                "Named storage must reach anchor, preserve destination boundary and complete: "
                    + (local == null ? "live" : local.problem()));
            h.succeed();
          }
        });
  }

  private static void withdraw(GameTestHelper h) {
    var n = npc(h, owner(h));
    anchor(h, 40, 5, "取り出しの倉庫");
    var home = chest(h, 5, 8);
    var target = chest(h, 48, 5);
    home.setItem(0, new ItemStack(Items.IRON_INGOT, 9));
    target.setItem(0, new ItemStack(Items.IRON_INGOT, 9));
    var skills = new NpcSkills(n, (text, who) -> {});
    var step =
        new TaskPlan.Step(
            "withdraw", "minecraft:iron_ingot", "", 5, -1, -1, "main", 10, List.of(), "取り出しの倉庫");
    var runner =
        new LocalTaskRunner(
            new TaskPlan(1, "Named withdrawal", "", List.of(step)),
            new NpcLocalWorld(n, skills),
            n.personality().allowedSkills);
    h.onEachTick(
        () -> {
          skills.tick();
          runner.tick(h.getLevel().getGameTime());
          if (runner.complete() || runner.needsReplan()) {
            h.assertTrue(
                runner.complete()
                    && count(n.inventory, Items.IRON_INGOT) == 5
                    && count(target, Items.IRON_INGOT) == 4
                    && count(home, Items.IRON_INGOT) == 9,
                "Reach the chest eight blocks from the anchor without returning to it: "
                    + runner.problem());
            runner.close();
            skills.cancel("Test done");
            n.discard();
            h.setBlock(40, 1, 5, Blocks.AIR);
            h.succeed();
          }
        });
  }

  private static void mining(GameTestHelper h) {
    var n = npc(h, owner(h));
    n.inventory.setItem(41, new ItemStack(Items.IRON_PICKAXE));
    var a = anchor(h, 6, 5, "採掘で撤去");
    var s = new NpcSkills(n, (text, who) -> {});
    var p = a.getBlockPos();
    var result =
        s.execute("dig", object("x", p.getX(), "y", p.getY(), "z", p.getZ())).toCompletableFuture();
    h.onEachTick(
        () -> {
          s.tick();
          if (result.isDone()) {
            boolean dropped =
                count(n.inventory, bep_3davater_companion.ANCHOR_ITEM.get()) == 1
                    || h
                            .getLevel()
                            .getEntitiesOfClass(
                                net.minecraft.world.entity.item.ItemEntity.class,
                                new net.minecraft.world.phys.AABB(p).inflate(2),
                                e -> e.getItem().is(bep_3davater_companion.ANCHOR_ITEM.get()))
                            .stream()
                            .mapToInt(e -> e.getItem().getCount())
                            .sum()
                        == 1;
            h.assertTrue(
                result.join().get("ok").getAsBoolean()
                    && dropped
                    && NpcAnchors.get(h.getLevel().getServer()).index.get("採掘で撤去") == null,
                "Actual mining drops anchor and removes ID: " + result.join());
            s.cancel("Test done");
            n.discard();
            h.succeed();
          }
        });
  }
}

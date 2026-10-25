package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.*;
import dev.avatarcraft.npc.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

final class NpcAreaWorkGameTests {
  static void register(RegisterGameTestsEvent event) {
    if (Boolean.getBoolean("avatarcraft.areaLive")) {
      test(event, "area_live_ai_tidy_excavation", NpcAreaWorkGameTests::live);
      return;
    }
    test(event, "area_dig_cuboid_preserves_floor_and_border", NpcAreaWorkGameTests::dig);
    test(event, "area_fill_rectangle_and_idempotent_retry", NpcAreaWorkGameTests::fill);
    test(event, "area_fill_water_uses_real_blocks", NpcAreaWorkGameTests::water);
    test(event, "area_fill_volume_from_main_hand", NpcAreaWorkGameTests::fillVolume);
    test(event, "area_never_mines_its_only_support", NpcAreaWorkGameTests::support);
    test(event, "area_till_then_plant_rectangle", NpcAreaWorkGameTests::farm);
    test(event, "area_plant_preserves_existing_crops", NpcAreaWorkGameTests::existingCrops);
    test(
        event,
        "area_preserves_containers_and_reports_remaining",
        NpcAreaWorkGameTests::protectedBlocks);
    test(event, "area_material_shortage_reports_partial_work", NpcAreaWorkGameTests::shortage);
    test(event, "area_cancel_stops_future_edits", NpcAreaWorkGameTests::cancel);
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
                Boolean.getBoolean("avatarcraft.areaLive") ? 200000 : 12000,
                1,
                true)) {
          public void run(GameTestHelper h) {
            for (int x = 0; x < 22; x++)
              for (int z = 0; z < 15; z++) h.setBlock(x, 0, z, Blocks.BEDROCK);
            run.accept(h);
          }
        });
  }

  private static BlockPos p(GameTestHelper h, int x, int y, int z) {
    return h.absolutePos(new BlockPos(x, y, z));
  }

  private static CompanionEntity npc(GameTestHelper h) {
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(p(h, 1, 1, 3)));
    var n = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    n.initialize(owner, "tortobu");
    n.setFollowsOwner(false);
    n.setPos(Vec3.atBottomCenterOf(p(h, 3, 1, 5)));
    h.getLevel().addFreshEntity(n);
    return n;
  }

  private static NpcSkills skills(CompanionEntity n) {
    return new NpcSkills(n, (s, id) -> {});
  }

  private static JsonObject args(
      GameTestHelper h,
      String op,
      int x1,
      int y1,
      int z1,
      int x2,
      int y2,
      int z2,
      String block,
      String item) {
    var a = p(h, x1, y1, z1);
    var b = p(h, x2, y2, z2);
    return object(
        "order",
        op.equals("dig") ? "top_down" : "bottom_up",
        "x1",
        a.getX(),
        "y1",
        a.getY(),
        "z1",
        a.getZ(),
        "x2",
        b.getX(),
        "y2",
        b.getY(),
        "z2",
        b.getZ(),
        "steps_json",
        ToolProgram.example(op, block, item).toString());
  }

  private static int count(CompanionEntity n, Item item) {
    int count = 0;
    for (int i = 0; i < 42; i++)
      if (n.inventory.getItem(i).is(item)) count += n.inventory.getItem(i).getCount();
    return count;
  }

  private static void await(
      GameTestHelper h,
      CompanionEntity n,
      NpcSkills skills,
      CompletableFuture<JsonObject> result,
      Consumer<JsonObject> check) {
    h.onEachTick(
        () -> {
          skills.tick();
          if (!result.isDone()) return;
          check.accept(result.join());
          skills.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static void dig(GameTestHelper h) {
    for (int x = 6; x <= 10; x++)
      for (int y = 1; y <= 3; y++) for (int z = 5; z <= 7; z++) h.setBlock(x, y, z, Blocks.STONE);
    h.setBlock(11, 1, 5, Blocks.STONE);
    h.setBlock(7, 2, 6, Blocks.DIORITE);
    var n = npc(h);
    n.inventory.setItem(0, new ItemStack(Items.IRON_PICKAXE));
    var s = skills(n);
    var f =
        s.execute(
                "area_work",
                args(h, "dig", 10, 3, 7, 6, 1, 5, "#minecraft:base_stone_overworld", ""))
            .toCompletableFuture();
    await(
        h,
        n,
        s,
        f,
        r -> {
          h.assertTrue(r.get("ok").getAsBoolean(), "Tidy dig must finish: " + r);
          for (int x = 6; x <= 10; x++)
            for (int y = 1; y <= 3; y++)
              for (int z = 5; z <= 7; z++)
                h.assertTrue(
                    h.getLevel().getBlockState(p(h, x, y, z)).isAir(),
                    "No pillars or ceiling cells left in cuboid");
          h.assertTrue(
              r.get("worked").getAsInt() == 45 && r.get("remaining").getAsInt() == 0,
              "Every requested cell was cleared");
          h.assertTrue(
              h.getLevel().getBlockState(p(h, 11, 1, 5)).is(Blocks.STONE)
                  && h.getLevel().getBlockState(p(h, 7, 0, 6)).is(Blocks.BEDROCK),
              "Border and floor remain untouched");
          h.assertTrue(
              n.inventory.getItem(0).getDamageValue() > 0
                  || n.getMainHandItem().getDamageValue() > 0,
              "Actual pickaxe durability consumed");
        });
  }

  private static void fill(GameTestHelper h) {
    h.setBlock(7, 1, 6, Blocks.DIRT);
    var n = npc(h);
    n.inventory.setItem(0, new ItemStack(Items.DIRT, 30));
    var s = skills(n);
    var a = args(h, "fill", 5, 1, 4, 9, 1, 8, "", "minecraft:dirt");
    var current =
        new java.util.concurrent.atomic.AtomicReference<>(
            s.execute("area_work", a).toCompletableFuture());
    boolean[] retried = {false};
    h.onEachTick(
        () -> {
          s.tick();
          if (!current.get().isDone()) return;
          var r = current.get().join();
          h.assertTrue(r.get("ok").getAsBoolean(), "Flat fill must finish: " + r);
          for (int x = 5; x <= 9; x++)
            for (int z = 4; z <= 8; z++)
              h.assertTrue(
                  h.getLevel().getBlockState(p(h, x, 1, z)).is(Blocks.DIRT),
                  "No holes in filled rectangle");
          h.assertTrue(
              count(n, Items.DIRT) == 6,
              "Exactly 24 real items consumed; one preexisting block retained");
          if (!retried[0]) {
            retried[0] = true;
            current.set(s.execute("area_work", a).toCompletableFuture());
            return;
          }
          h.assertTrue(r.get("changes").getAsInt() == 0, "Retry does not place twice");
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static void water(GameTestHelper h) {
    for (int x = 5; x <= 8; x++)
      for (int z = 4; z <= 7; z++)
        h.setBlock(x, 1, z, x == 5 || x == 8 || z == 4 || z == 7 ? Blocks.GLASS : Blocks.WATER);
    var n = npc(h);
    n.inventory.setItem(0, new ItemStack(Items.STONE, 4));
    var s = skills(n);
    await(
        h,
        n,
        s,
        s.execute("area_work", args(h, "fill", 6, 1, 5, 7, 1, 6, "", "minecraft:stone"))
            .toCompletableFuture(),
        r -> {
          h.assertTrue(r.get("ok").getAsBoolean(), "Water reclamation must finish: " + r);
          for (int x = 6; x <= 7; x++)
            for (int z = 5; z <= 6; z++)
              h.assertTrue(
                  h.getLevel().getBlockState(p(h, x, 1, z)).is(Blocks.STONE),
                  "Water replaced with actual carried blocks");
          h.assertTrue(count(n, Items.STONE) == 0, "Four blocks consumed");
        });
  }

  private static void fillVolume(GameTestHelper h) {
    var n = npc(h);
    n.inventory.setItem(41, new ItemStack(Items.STONE, 8));
    var s = skills(n);
    await(
        h,
        n,
        s,
        s.execute("area_work", args(h, "fill", 6, 1, 5, 7, 2, 6, "", "minecraft:stone"))
            .toCompletableFuture(),
        r -> {
          h.assertTrue(
              r.get("ok").getAsBoolean() && r.get("worked").getAsInt() == 8,
              "Solid volume fills bottom-up using main hand supplies: " + r);
          for (int x = 6; x <= 7; x++)
            for (int y = 1; y <= 2; y++)
              for (int z = 5; z <= 6; z++)
                h.assertTrue(
                    h.getLevel().getBlockState(p(h, x, y, z)).is(Blocks.STONE),
                    "No hidden air pockets in fill volume");
          h.assertTrue(count(n, Items.STONE) == 0, "Eight real blocks consumed");
        });
  }

  private static void support(GameTestHelper h) {
    for (int x = 0; x < 22; x++)
      for (int z = 0; z < 15; z++) for (int y = -4; y <= 0; y++) h.setBlock(x, y, z, Blocks.AIR);
    h.setBlock(3, 0, 5, Blocks.STONE);
    var n = npc(h);
    n.inventory.setItem(0, new ItemStack(Items.IRON_PICKAXE));
    var s = skills(n);
    await(
        h,
        n,
        s,
        s.execute("area_work", args(h, "dig", 3, 0, 5, 3, 0, 5, "", "")).toCompletableFuture(),
        r -> {
          h.assertTrue(
              !r.get("ok").getAsBoolean()
                  && r.get("remaining").getAsInt() == 1
                  && h.getLevel().getBlockState(p(h, 3, 0, 5)).is(Blocks.STONE),
              "NPC does not mine its only support: "
                  + r
                  + " position="
                  + n.position()
                  + " target="
                  + h.getLevel().getBlockState(p(h, 3, 0, 5)));
        });
  }

  private static void farm(GameTestHelper h) {
    for (int x = 5; x <= 9; x++) for (int z = 4; z <= 6; z++) h.setBlock(x, 0, z, Blocks.DIRT);
    h.setBlock(7, 0, 3, Blocks.WATER);
    var n = npc(h);
    n.inventory.setItem(0, new ItemStack(Items.IRON_HOE));
    n.inventory.setItem(1, new ItemStack(Items.WHEAT_SEEDS, 15));
    var s = skills(n);
    var current =
        new java.util.concurrent.atomic.AtomicReference<>(
            s.execute("area_work", args(h, "till", 5, 0, 4, 9, 0, 6, "", ""))
                .toCompletableFuture());
    boolean[] planted = {false};
    h.onEachTick(
        () -> {
          s.tick();
          if (!current.get().isDone()) return;
          var r = current.get().join();
          h.assertTrue(r.get("ok").getAsBoolean(), "Farm rectangle must finish: " + r);
          for (int x = 5; x <= 9; x++)
            for (int z = 4; z <= 6; z++)
              h.assertTrue(
                  h.getLevel().getBlockState(p(h, x, 0, z)).is(Blocks.FARMLAND), "All soil tilled");
          if (!planted[0]) {
            planted[0] = true;
            current.set(
                s.execute(
                        "area_work",
                        args(h, "plant", 5, 0, 4, 9, 0, 6, "", "minecraft:wheat_seeds"))
                    .toCompletableFuture());
            return;
          }
          for (int x = 5; x <= 9; x++)
            for (int z = 4; z <= 6; z++)
              h.assertTrue(
                  h.getLevel().getBlockState(p(h, x, 1, z)).is(Blocks.WHEAT),
                  "Every rectangle cell replanted");
          h.assertTrue(count(n, Items.WHEAT_SEEDS) == 0, "Exactly fifteen seeds consumed");
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static void existingCrops(GameTestHelper h) {
    for (int x = 5; x <= 7; x++) for (int z = 4; z <= 6; z++) h.setBlock(x, 0, z, Blocks.FARMLAND);
    h.setBlock(6, 0, 3, Blocks.WATER);
    h.setBlock(5, 1, 4, Blocks.WHEAT);
    h.setBlock(7, 1, 6, Blocks.CARROTS);
    var n = npc(h);
    n.inventory.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 9));
    var s = skills(n);
    await(
        h,
        n,
        s,
        s.execute("area_work", args(h, "plant", 5, 0, 4, 7, 0, 6, "", "minecraft:wheat_seeds"))
            .toCompletableFuture(),
        r -> {
          h.assertTrue(
              !r.get("ok").getAsBoolean() && r.get("remaining").getAsInt() == 1,
              "Other crop remains a reported unresolved cell: " + r);
          h.assertTrue(
              h.getLevel().getBlockState(p(h, 7, 1, 6)).is(Blocks.CARROTS)
                  && h.getLevel().getBlockState(p(h, 5, 1, 4)).is(Blocks.WHEAT),
              "Existing crops preserved");
          h.assertTrue(count(n, Items.WHEAT_SEEDS) == 2, "Only seven empty cells planted");
        });
  }

  private static void protectedBlocks(GameTestHelper h) {
    h.setBlock(5, 1, 5, Blocks.STONE);
    h.setBlock(6, 1, 5, Blocks.CHEST);
    h.setBlock(7, 1, 5, bep_3davater_companion.AVATAR_ANCHOR.get());
    h.setBlock(8, 1, 5, Blocks.BEDROCK);
    var n = npc(h);
    n.inventory.setItem(0, new ItemStack(Items.IRON_PICKAXE));
    var s = skills(n);
    await(
        h,
        n,
        s,
        s.execute("area_work", args(h, "dig", 5, 1, 5, 8, 1, 5, "", "")).toCompletableFuture(),
        r -> {
          h.assertTrue(
              !r.get("ok").getAsBoolean() && r.get("remaining").getAsInt() == 3,
              "Protection prevents complete claim: " + r);
          h.assertTrue(
              h.getLevel().getBlockState(p(h, 5, 1, 5)).isAir()
                  && h.getLevel().getBlockState(p(h, 6, 1, 5)).is(Blocks.CHEST)
                  && h.getLevel().getBlockState(p(h, 7, 1, 5)).is(bep_3davater_companion.AVATAR_ANCHOR.get())
                  && h.getLevel().getBlockState(p(h, 8, 1, 5)).is(Blocks.BEDROCK),
              "Protected blocks intact");
        });
  }

  private static void shortage(GameTestHelper h) {
    var n = npc(h);
    n.inventory.setItem(0, new ItemStack(Items.DIRT, 2));
    var s = skills(n);
    var a = args(h, "fill", 5, 1, 5, 7, 1, 6, "", "minecraft:dirt");
    var current =
        new java.util.concurrent.atomic.AtomicReference<>(
            s.execute("area_work", a).toCompletableFuture());
    boolean[] supplied = {false};
    h.onEachTick(
        () -> {
          s.tick();
          if (!current.get().isDone()) return;
          var r = current.get().join();
          if (!supplied[0]) {
            h.assertTrue(
                !r.get("ok").getAsBoolean()
                    && r.get("worked").getAsInt() == 2
                    && r.get("remaining").getAsInt() == 4
                    && r.get("error").getAsString().contains("MATERIAL_MISSING"),
                "Real material shortage preserves partial count: " + r);
            h.assertTrue(count(n, Items.DIRT) == 0, "Never invent missing materials");
            n.inventory.setItem(0, new ItemStack(Items.DIRT, 4));
            supplied[0] = true;
            current.set(s.execute("area_work", a).toCompletableFuture());
            return;
          }
          h.assertTrue(
              r.get("ok").getAsBoolean()
                  && r.get("worked").getAsInt() == 4
                  && count(n, Items.DIRT) == 0,
              "Resupply immediately retries exactly the four unfinished cells: " + r);
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static void cancel(GameTestHelper h) {
    for (int x = 5; x <= 9; x++) h.setBlock(x, 1, 5, Blocks.STONE);
    var n = npc(h);
    n.inventory.setItem(0, new ItemStack(Items.IRON_PICKAXE));
    var s = skills(n);
    var f = s.execute("area_work", args(h, "dig", 5, 1, 5, 9, 1, 5, "", "")).toCompletableFuture();
    long[] stopped = {-1};
    int[] blocks = {0};
    h.onEachTick(
        () -> {
          s.tick();
          int left = 0;
          for (int x = 5; x <= 9; x++)
            if (h.getLevel().getBlockState(p(h, x, 1, 5)).is(Blocks.STONE)) left++;
          if (stopped[0] < 0 && left < 5) {
            s.cancel("Owner cancelled area work");
            stopped[0] = h.getLevel().getGameTime();
            blocks[0] = left;
          }
          if (stopped[0] < 0 || h.getLevel().getGameTime() - stopped[0] < 40) return;
          h.assertTrue(
              f.isDone() && !f.join().get("ok").getAsBoolean() && left == blocks[0] && left > 0,
              "Cancelled area performs no further edits");
          n.discard();
          h.succeed();
        });
  }

  private static void live(GameTestHelper h) {
    for (int x = 6; x <= 8; x++)
      for (int y = 1; y <= 2; y++) for (int z = 5; z <= 7; z++) h.setBlock(x, y, z, Blocks.STONE);
    h.setBlock(9, 1, 5, Blocks.STONE);
    var n = npc(h);
    n.inventory.setItem(0, new ItemStack(Items.IRON_PICKAXE));
    var from = p(h, 6, 1, 5);
    var to = p(h, 8, 2, 7);
    System.setProperty("avatarcraft.complexLive", "true");
    var brain =
        new NpcComplexLiveBrain(
            "area-excavation",
            () ->
                "No fixture plan is supplied. The owner's exact box is authoritative; use the"
                    + " ordered area tool and preserve everything outside it.");
    n.setInstruction(
        "座標("
            + from.getX()
            + ","
            + from.getY()
            + ","
            + from.getZ()
            + ")から("
            + to.getX()
            + ","
            + to.getY()
            + ","
            + to.getZ()
            + ")までの直方体の石を全部きれいに掘って。両端を含みます。床と範囲外は壊さないで。終わったら待機して。");
    n.gameTestMind(new NpcMind(n, NpcService.get(h.getLevel().getServer()), brain));
    long started = System.nanoTime();
    h.onEachTick(
        () -> {
          if (brain.waiting)
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
          boolean clear = true;
          for (int x = 6; x <= 8; x++)
            for (int y = 1; y <= 2; y++)
              for (int z = 5; z <= 7; z++)
                clear &= h.getLevel().getBlockState(p(h, x, y, z)).isAir();
          boolean done = clear && n.instruction().isBlank();
          if (!done && System.nanoTime() - started < TimeUnit.MINUTES.toNanos(5)) return;
          boolean used =
              brain.plans.stream()
                  .flatMap(plan -> plan.steps().stream())
                  .anyMatch(s -> s.target().equals("bep_3davater_companion:area_work"));
          boolean success =
              done
                  && used
                  && h.getLevel().getBlockState(p(h, 9, 1, 5)).is(Blocks.STONE)
                  && h.getLevel().getBlockState(p(h, 7, 0, 6)).is(Blocks.BEDROCK);
          try {
            java.nio.file.Files.writeString(
                java.nio.file.Path.of("../build/area-live-result.json"),
                new GsonBuilder()
                    .setPrettyPrinting()
                    .create()
                    .toJson(
                        object(
                            "success",
                            success,
                            "requests",
                            brain.requests,
                            "plans",
                            new Gson().toJsonTree(brain.plans),
                            "errors",
                            new Gson().toJsonTree(brain.errors),
                            "state",
                            n.mind().saveState())));
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
          brain.close();
          n.discard();
          h.assertTrue(success, "Live AI plan must clear exact cuboid then stand by");
          h.succeed();
        });
  }
}

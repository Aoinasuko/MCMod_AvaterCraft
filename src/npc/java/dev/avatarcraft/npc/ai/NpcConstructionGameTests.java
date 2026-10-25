package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.*;
import dev.avatarcraft.npc.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

final class NpcConstructionGameTests {
  static void registerLive(RegisterGameTestsEvent event) {
    test(event, "construction_live_ai_design_to_completion", NpcConstructionGameTests::live);
  }

  static void register(RegisterGameTestsEvent event) {
    test(event, "construction_design_supplies_and_real_build", NpcConstructionGameTests::build);
    test(event, "construction_automatic_site_and_range_rejection", NpcConstructionGameTests::site);
    test(event, "construction_requires_inventory_before_build", NpcConstructionGameTests::missing);
    test(event, "area_mixed_tool_dependencies_till_then_plant", NpcConstructionGameTests::mixed);
  }

  private static void test(RegisterGameTestsEvent e, String name, Consumer<GameTestHelper> body) {
    var env = e.registerEnvironment(NpcMod.id(name), new TestEnvironmentDefinition.AllOf());
    e.registerTest(
        NpcMod.id(name),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(
                env,
                NpcMod.id("qa_complex"),
                Boolean.getBoolean("avatarcraft.areaLive") ? 200000 : 16000,
                1,
                true)) {
          public void run(GameTestHelper h) {
            for (int x = 0; x < 24; x++)
              for (int z = 0; z < 15; z++) h.setBlock(x, 0, z, Blocks.BEDROCK);
            body.accept(h);
          }
        });
  }

  private static BlockPos p(GameTestHelper h, int x, int y, int z) {
    return h.absolutePos(new BlockPos(x, y, z));
  }

  private static CompanionEntity npc(GameTestHelper h) {
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(p(h, 1, 1, 3)));
    var n = NpcMod.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    n.initialize(owner, "tortobu");
    n.setFollowsOwner(false);
    n.setPos(Vec3.atBottomCenterOf(p(h, 3, 1, 5)));
    h.getLevel().addFreshEntity(n);
    return n;
  }

  static String house() {
    return "{\"name\":\"小さな家\",\"size\":[5,4,5],\"parts\":[{\"from\":[0,0,0],\"to\":[4,3,4],\"block\":\"minecraft:oak_planks\"},{\"from\":[1,1,1],\"to\":[3,2,3],\"block\":\"minecraft:air\"},{\"from\":[2,1,0],\"to\":[2,2,0],\"block\":\"minecraft:air\"}]}";
  }

  private static JsonObject site(GameTestHelper h, int x1, int y1, int z1, int x2, int y2, int z2) {
    var a = p(h, x1, y1, z1);
    var b = p(h, x2, y2, z2);
    return object(
        "from",
        new Gson().toJsonTree(List.of(a.getX(), a.getY(), a.getZ())),
        "to",
        new Gson().toJsonTree(List.of(b.getX(), b.getY(), b.getZ())));
  }

  private static void build(GameTestHelper h) {
    var n = npc(h);
    var s = new NpcSkills(n, (t, id) -> {});
    h.setBlock(4, 1, 8, Blocks.CHEST);
    var chest = (Container) h.getLevel().getBlockEntity(p(h, 4, 1, 8));
    int needed = BlueprintSpec.parse(house()).blocks().size();
    chest.setItem(0, new ItemStack(Items.OAK_PLANKS, 64));
    chest.setItem(1, new ItemStack(Items.OAK_PLANKS, needed - 64));
    chest.setItem(2, new ItemStack(Items.DIRT));
    var current =
        new java.util.concurrent.atomic.AtomicReference<>(
            s.execute(
                    "create_blueprint",
                    object(
                        "design_json", house(), "site_json", site(h, 6, 1, 4, 10, 4, 8).toString()))
                .toCompletableFuture());
    int[] stage = {0};
    h.onEachTick(
        () -> {
          s.tick();
          if (!current.get().isDone()) return;
          var r = current.get().join();
          h.assertTrue(r.get("ok").getAsBoolean(), "Blueprint stage " + stage[0] + ": " + r);
          if (stage[0] == 0) {
            var data = NpcBlueprints.get(h.getLevel().getServer());
            var encoded =
                NpcBlueprints.TYPE
                    .codec()
                    .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, data)
                    .getOrThrow();
            h.assertTrue(
                NpcBlueprints.TYPE
                    .codec()
                    .parse(com.mojang.serialization.JsonOps.INSTANCE, encoded)
                    .getOrThrow()
                    .plans
                    .containsKey(n.getUUID().toString()),
                "Blueprint survives world save codec");
            h.assertTrue(
                NpcBlueprints.geometry(data.current(n, "current")).getAsJsonArray("cells").size()
                    == needed,
                "Preview matches actual plan");
            stage[0] = 1;
            current.set(
                s.execute("gather_blueprint", object("blueprint_id", "current"))
                    .toCompletableFuture());
            return;
          }
          if (stage[0] == 1) {
            h.assertTrue(
                NpcConstruction.carried(n, "minecraft:oak_planks") == needed && chest.isEmpty(),
                "Actual supplies withdrawn before build");
            stage[0] = 2;
            current.set(
                s.execute("build_blueprint", object("blueprint_id", "current"))
                    .toCompletableFuture());
            return;
          }
          var plan = NpcBlueprints.get(h.getLevel().getServer()).current(n, "current");
          for (var c : plan.spec().blocks().keySet())
            h.assertTrue(
                h.getLevel()
                    .getBlockState(new BlockPos(plan.x + c.x(), plan.y + c.y(), plan.z + c.z()))
                    .is(Blocks.OAK_PLANKS),
                "All designed floor/wall/roof cells built");
          h.assertTrue(
              h.getLevel().getBlockState(new BlockPos(plan.x + 2, plan.y + 1, plan.z)).isAir(),
              "Entrance stays open");
          h.assertTrue(
              plan.status.equals("complete")
                  && NpcConstruction.carried(n, "minecraft:oak_planks") == 0,
              "Supplies consumed, preview complete");
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static void site(GameTestHelper h) {
    var n = npc(h);
    var s = new NpcSkills(n, (t, id) -> {});
    var rejected =
        s.execute(
                "create_blueprint",
                object("design_json", house(), "site_json", site(h, 6, 1, 4, 7, 2, 5).toString()))
            .toCompletableFuture();
    h.assertTrue(
        rejected.isDone() && !rejected.join().get("ok").getAsBoolean(),
        "Reject design beyond owner range");
    var f =
        s.execute("create_blueprint", object("design_json", house(), "site_json", "{}"))
            .toCompletableFuture();
    h.onEachTick(
        () -> {
          s.tick();
          if (!f.isDone()) return;
          h.assertTrue(f.join().get("ok").getAsBoolean(), "Automatic supported site: " + f.join());
          var plan = NpcBlueprints.get(h.getLevel().getServer()).current(n, "current");
          var cancelled =
              s.execute("cancel_blueprint", object("blueprint_id", "current"))
                  .toCompletableFuture();
          h.assertTrue(
              cancelled.join().get("ok").getAsBoolean() && plan.status.equals("cancelled"),
              "Preview cancellation");
          n.discard();
          h.succeed();
        });
  }

  private static void missing(GameTestHelper h) {
    var n = npc(h);
    var s = new NpcSkills(n, (t, id) -> {});
    var f =
        s.execute(
                "create_blueprint",
                object("design_json", house(), "site_json", site(h, 6, 1, 4, 10, 4, 8).toString()))
            .toCompletableFuture();
    h.onEachTick(
        () -> {
          s.tick();
          if (!f.isDone()) return;
          h.assertTrue(f.join().get("ok").getAsBoolean(), "Design saved");
          var r =
              s.execute("build_blueprint", object("blueprint_id", "current"))
                  .toCompletableFuture()
                  .join();
          h.assertTrue(
              !r.get("ok").getAsBoolean()
                  && r.get("error").getAsString().contains("MATERIAL_MISSING"),
              "Build requires real inventory");
          h.assertTrue(h.getLevel().getBlockState(p(h, 6, 1, 4)).isAir(), "No invented blocks");
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static void mixed(GameTestHelper h) {
    for (int x = 5; x <= 7; x++) for (int z = 4; z <= 6; z++) h.setBlock(x, 0, z, Blocks.DIRT);
    h.setBlock(6, 0, 3, Blocks.WATER);
    var n = npc(h);
    n.inventory.setItem(0, new ItemStack(Items.IRON_HOE));
    n.inventory.setItem(1, new ItemStack(Items.WHEAT_SEEDS, 9));
    var s = new NpcSkills(n, (t, id) -> {});
    var templates = ToolProgram.example("till", "", "");
    var plant = ToolProgram.example("plant", "", "minecraft:wheat_seeds").get(0).getAsJsonObject();
    plant.addProperty("priority", 100);
    plant.add("after", new Gson().toJsonTree(List.of("till")));
    templates.add(plant);
    var a = p(h, 5, 0, 4);
    var b = p(h, 7, 0, 6);
    var f =
        s.execute(
                "area_work",
                object(
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
                    "order",
                    "bottom_up",
                    "steps_json",
                    templates.toString()))
            .toCompletableFuture();
    h.onEachTick(
        () -> {
          s.tick();
          if (!f.isDone()) return;
          h.assertTrue(
              f.join().get("ok").getAsBoolean(), "Mixed template dependencies: " + f.join());
          for (int x = 5; x <= 7; x++)
            for (int z = 4; z <= 6; z++)
              h.assertTrue(
                  h.getLevel().getBlockState(p(h, x, 0, z)).is(Blocks.FARMLAND)
                      && h.getLevel().getBlockState(p(h, x, 1, z)).is(Blocks.WHEAT),
                  "Higher priority planting waits for tilling");
          s.cancel("Test done");
          n.discard();
          h.succeed();
        });
  }

  private static void live(GameTestHelper h) {
    var n = npc(h);
    h.setBlock(4, 1, 8, Blocks.CHEST);
    var chest = (Container) h.getLevel().getBlockEntity(p(h, 4, 1, 8));
    for (int i = 0; i < 3; i++) chest.setItem(i, new ItemStack(Items.OAK_PLANKS, 64));
    chest.setItem(3, new ItemStack(Items.DIRT, 16));
    var a = p(h, 6, 1, 4);
    var b = p(h, 10, 4, 8);
    System.setProperty("avatarcraft.complexLive", "true");
    var brain =
        new NpcComplexLiveBrain(
            "construction-design",
            () ->
                "Author the building design yourself; no reference blueprint or TaskPlan is"
                    + " supplied. Available oak planks are the main structure stock; dirt is"
                    + " available for temporary supports. The requested space is five by four by"
                    + " five inclusive. Keep an accessible two-block entrance and hollow"
                    + " interior.");
    n.setInstruction(
        "座標("
            + a.getX()
            + ","
            + a.getY()
            + ","
            + a.getZ()
            + ")から("
            + b.getX()
            + ","
            + b.getY()
            + ","
            + b.getZ()
            + ")までの範囲内に、入口と床と壁と屋根がある、中が空洞の小さな家を建ててください。材料の指定はありません。近くのチェストの材料から考えて設計図を作り、必要な物を集めて施工し、終わったら待機してください。");
    n.gameTestMind(new NpcMind(n, NpcService.get(h.getLevel().getServer()), brain));
    long start = System.nanoTime();
    h.onEachTick(
        () -> {
          if (brain.waiting)
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
          var plan = NpcBlueprints.get(h.getLevel().getServer()).plans.get(n.getUUID().toString());
          boolean complete =
              plan != null && plan.status.equals("complete") && n.instruction().isBlank();
          if (!complete && System.nanoTime() - start < TimeUnit.MINUTES.toNanos(5)) return;
          boolean success =
              complete
                  && plan.x >= a.getX()
                  && plan.y >= a.getY()
                  && plan.z >= a.getZ()
                  && plan.x + plan.spec().width() - 1 <= b.getX()
                  && plan.y + plan.spec().height() - 1 <= b.getY()
                  && plan.z + plan.spec().depth() - 1 <= b.getZ();
          if (success)
            for (var e : plan.spec().blocks().entrySet()) {
              var c = e.getKey();
              success &=
                  h.getLevel()
                      .getBlockState(new BlockPos(plan.x + c.x(), plan.y + c.y(), plan.z + c.z()))
                      .is(
                          net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(
                              net.minecraft.resources.Identifier.parse(e.getValue())));
            }
          try {
            java.nio.file.Files.writeString(
                java.nio.file.Path.of("../build/construction-live-result.json"),
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
                            "blueprint",
                            plan == null ? JsonNull.INSTANCE : new Gson().toJsonTree(plan),
                            "state",
                            n.mind().saveState())));
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
          brain.close();
          n.discard();
          h.assertTrue(success, "Live AI designs, gathers and builds within owner range");
          h.succeed();
        });
  }
}

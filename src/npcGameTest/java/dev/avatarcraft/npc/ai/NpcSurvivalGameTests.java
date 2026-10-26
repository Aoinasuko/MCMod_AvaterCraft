package dev.avatarcraft.npc.ai;

import dev.avatarcraft.npc.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

final class NpcSurvivalGameTests {
  static void register(RegisterGameTestsEvent event) {
    var env =
        event.registerEnvironment(bep_3davater_companion.id("survival_qa"), new TestEnvironmentDefinition.AllOf());
    event.registerTest(
        bep_3davater_companion.id("partial_ground_materials_continue_acquisition"),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(env, bep_3davater_companion.id("qa_empty"), 2000, 1, true)) {
          public void run(GameTestHelper h) {
            partialMaterials(h);
          }
        });
    event.registerTest(
        bep_3davater_companion.id("empty_hands_to_iron_pickaxe"),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(env, bep_3davater_companion.id("qa_large"), 16000, 1, true)) {
          public void run(GameTestHelper h) {
            emptyHands(h);
          }
        });
    event.registerTest(
        bep_3davater_companion.id("vanilla_furnace_batch"),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(env, bep_3davater_companion.id("qa_empty"), 1500, 1, true)) {
          public void run(GameTestHelper h) {
            smelting(h);
          }
        });
    event.registerTest(
        bep_3davater_companion.id("cooperative_empty_hands_diamond_storage"),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(env, bep_3davater_companion.id("qa_large"), 22000, 1, true)) {
          public void run(GameTestHelper h) {
            teamDiamond(h);
          }
        });
    event.registerTest(
        bep_3davater_companion.id("bow_charge_and_inventory"),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(env, bep_3davater_companion.id("qa_empty"), 300, 1, true)) {
          public void run(GameTestHelper h) {
            bow(h);
          }
        });
    event.registerTest(
        bep_3davater_companion.id("insert_real_eye_in_frame"),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(env, bep_3davater_companion.id("qa_empty"), 100, 1, true)) {
          public void run(GameTestHelper h) {
            eye(h);
          }
        });
    event.registerTest(
        bep_3davater_companion.id("real_nether_portal_travel"),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(env, bep_3davater_companion.id("qa_empty"), 1800, 1, true)) {
          public void run(GameTestHelper h) {
            portal(h);
          }
        });
    event.registerTest(
        bep_3davater_companion.id("persistent_chunk_square_and_overlap"),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(env, bep_3davater_companion.id("qa_large"), 150, 1, true)) {
          public void run(GameTestHelper h) {
            chunks(h);
          }
        });
    event.registerTest(
        bep_3davater_companion.id("companion_damage_can_hurt_dragon"),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(env, bep_3davater_companion.id("qa_empty"), 100, 1, true)) {
          public void run(GameTestHelper h) {
            boss(h);
          }
        });
    event.registerTest(
        bep_3davater_companion.id("new_chest_binds_return_storage"),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(env, bep_3davater_companion.id("qa_empty"), 500, 1, true)) {
          public void run(GameTestHelper h) {
            newStorage(h);
          }
        });
    event.registerTest(
        bep_3davater_companion.id("team_claim_and_saved_work_roundtrip"),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(env, bep_3davater_companion.id("qa_empty"), 100, 1, true)) {
          public void run(GameTestHelper h) {
            teamState(h);
          }
        });
  }

  private static CompanionEntity npc(GameTestHelper h, int x, int y, int z) {
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(x + 1, y, z))));
    var n = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    n.initialize(owner, "tortobu");
    n.setFollowsOwner(false);
    n.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(x, y, z))));
    h.getLevel().addFreshEntity(n);
    return n;
  }

  private static int count(CompanionEntity npc, Item item) {
    int n = 0;
    for (int i = 0; i < NpcInventory.SIZE; i++)
      if (npc.inventory.getItem(i).is(item)) n += npc.inventory.getItem(i).getCount();
    return n;
  }

  private static void newStorage(GameTestHelper h) {
    for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.STONE);
    var n = npc(h, 5, 1, 5);
    n.inventory.setItem(0, new ItemStack(Items.CHEST));
    n.inventory.setItem(1, new ItemStack(Items.COAL));
    n.setInstruction("チェストを設置して石炭を1個収納して");
    var place =
        new TaskPlan.Step(
            "tool",
            "bep_3davater_companion:place_workstation",
            "",
            1,
            -1,
            -1,
            "main",
            1,
            List.of(),
            "",
            "{\"item\":\"minecraft:chest\"}");
    n.mind()
        .acceptDelegatedPlan(
            new TaskPlan(
                1,
                "Place and use new storage",
                "",
                List.of(place, NpcSurvival.step("deposit", "minecraft:coal", "", 1))));
    h.onEachTick(
        () -> {
          h.assertTrue(!n.aiStatus().startsWith("作業保留"), "New storage failed: " + n.aiStatus());
          if (!n.instruction().isBlank()) return;
          int stored = 0, chests = 0;
          for (int x = 0; x < 12; x++)
            for (int z = 0; z < 12; z++)
              if (h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(x, 1, z)))
                  instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest) {
                chests++;
                for (int i = 0; i < chest.getContainerSize(); i++)
                  if (chest.getItem(i).is(Items.COAL)) stored += chest.getItem(i).getCount();
              }
          h.assertTrue(
              chests == 1 && stored == 1, "Exactly one placed chest must receive the actual coal");
          h.assertTrue(
              count(n, Items.COAL) == 0 && count(n, Items.CHEST) == 0,
              "Placement and transfer must consume actual carried items");
          n.discard();
          h.succeed();
        });
  }

  private static void teamState(GameTestHelper h) {
    for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.STONE);
    var leader = npc(h, 4, 1, 4);
    var owner = h.getLevel().getServer().getPlayerList().getPlayer(leader.ownerId());
    var worker = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    worker.initialize(owner, "tortobu");
    worker.setPos(leader.position().add(2, 0, 0));
    worker.setFollowsOwner(false);
    h.getLevel().addFreshEntity(worker);
    String instruction = "仲間と協力して木材を集めて";
    leader.setInstruction(instruction);
    worker.setInstruction(instruction);
    h.assertTrue(NpcTeams.coordinates(leader), "One actual NPC must claim leadership");
    h.assertTrue(
        !NpcTeams.coordinates(worker), "Same instruction must not start a duplicate root plan");
    var delegated = NpcTeams.delegate(leader, worker.getId(), "minecraft:oak_log", 1);
    h.assertTrue(
        delegated.get("ok").getAsBoolean(), "Waiting member must accept actual assignment");
    var data = NpcWorkData.get(h.getLevel());
    var codec = NpcWorkData.TYPE.codecFactory().create(h.getLevel());
    var encoded = codec.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, data).getOrThrow();
    var restored = codec.parse(com.mojang.serialization.JsonOps.INSTANCE, encoded).getOrThrow();
    h.assertTrue(
        restored.jobs.stream()
            .anyMatch(
                j ->
                    j.leader.equals(leader.getUUID())
                        && j.worker.equals(worker.getUUID())
                        && j.status.equals("running")),
        "Delegation must survive saved-data codec round trip");
    h.assertTrue(
        restored.groups.stream().anyMatch(g -> g.leader.equals(leader.getUUID())),
        "Coordination claim must survive saved-data codec round trip");
    leader.setInstruction("");
    h.assertTrue(
        worker.instruction().isBlank() && NpcTeams.job(worker) == null,
        "Stopping leader must cancel actual child work");
    leader.discard();
    worker.discard();
    h.succeed();
  }

  private static void emptyHands(GameTestHelper h) {
    for (int x = 0; x < 40; x++)
      for (int z = 0; z < 40; z++) for (int y = 0; y <= 5; y++) h.setBlock(x, y, z, Blocks.STONE);
    for (int x = 3; x < 16; x++) h.setBlock(x, 6, 12, Blocks.OAK_LOG);
    for (int x = 4; x < 12; x++) h.setBlock(x, 5, 8, Blocks.IRON_ORE);
    var n = npc(h, 7, 6, 9);
    var survival = new NpcSurvival(n, "minecraft:iron_pickaxe", 1, 1);
    h.onEachTick(
        () -> {
          survival.tick();
          if (!survival.result.isDone()) return;
          var outcome = survival.result.join();
          h.assertTrue(
              outcome.get("ok").getAsBoolean(), "Empty-hands preparation failed: " + outcome);
          h.assertTrue(
              count(n, Items.IRON_PICKAXE) == 1, "Iron pickaxe must exist in real inventory");
          // Nearby automatic pickup may collect surplus ore while travelling. It is legitimate
          // to keep that surplus; the three ingots used by this recipe must have been consumed.
          h.assertTrue(
              count(n, Items.IRON_INGOT) == 0, "The pickaxe's real ingots must be consumed");
          survival.close();
          n.discard();
          h.succeed();
        });
  }

  private static void partialMaterials(GameTestHelper h) {
    for (int x = 0; x < 12; x++)
      for (int z = 0; z < 12; z++) for (int y = 0; y < 4; y++) h.setBlock(x, y, z, Blocks.BEDROCK);
    h.setBlock(3, 4, 5, Blocks.CRAFTING_TABLE);
    h.setBlock(5, 4, 3, Blocks.STONE);
    var n = npc(h, 3, 4, 3);
    n.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 6));
    n.inventory.setItem(1, new ItemStack(Items.WOODEN_PICKAXE));
    var drop =
        new net.minecraft.world.entity.item.ItemEntity(
            h.getLevel(), n.getX() + .3, n.getY(), n.getZ() + .3, new ItemStack(Items.COBBLESTONE));
    drop.setNoPickUpDelay();
    h.getLevel().addFreshEntity(drop);
    var survival = new NpcSurvival(n, "minecraft:furnace", 1, 1);
    h.onEachTick(
        () -> {
          survival.tick();
          if (!survival.result.isDone()) return;
          h.assertTrue(
              survival.result.join().get("ok").getAsBoolean() && count(n, Items.FURNACE) == 1,
              "After picking one of two missing stones, gather the remainder and craft the actual"
                  + " furnace");
          survival.close();
          n.discard();
          h.succeed();
        });
  }

  private static void smelting(GameTestHelper h) {
    for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.STONE);
    h.setBlock(4, 1, 3, Blocks.FURNACE);
    var n = npc(h, 3, 1, 3);
    n.inventory.setItem(0, new ItemStack(Items.RAW_IRON, 3));
    n.inventory.setItem(1, new ItemStack(Items.COAL));
    var skills = new NpcSkills(n, (text, id) -> {});
    var batch =
        new NpcSmelting[] {
          new NpcSmelting(
              n, skills, h.absolutePos(new BlockPos(4, 1, 3)), "minecraft:iron_ingot", 3)
        };
    boolean[] resumed = {false};
    long started = h.getLevel().getGameTime();
    h.onEachTick(
        () -> {
          batch[0].tick();
          if (!resumed[0] && h.getLevel().getGameTime() - started >= 220) {
            h.assertTrue(
                count(n, Items.IRON_INGOT) == 0,
                "Incomplete batch must remain in actual furnace across interruption");
            batch[0].close();
            batch[0] =
                new NpcSmelting(
                    n, skills, h.absolutePos(new BlockPos(4, 1, 3)), "minecraft:iron_ingot", 3);
            resumed[0] = true;
          }
          if (h.getLevel().getGameTime() - started < 190)
            h.assertTrue(count(n, Items.IRON_INGOT) == 0, "No instant synthetic output");
          if (!batch[0].result.isDone()) return;
          h.assertTrue(
              batch[0].result.join().get("ok").getAsBoolean(),
              "Real furnace batch failed: " + batch[0].result.join());
          h.assertTrue(count(n, Items.IRON_INGOT) == 3, "Only actual smelted output is counted");
          h.assertTrue(
              NpcWorkData.get(h.getLevel()).furnaces.isEmpty(),
              "Completed furnace lease must be released");
          batch[0].close();
          n.discard();
          h.succeed();
        });
  }

  private static void teamDiamond(GameTestHelper h) {
    for (int x = 0; x < 40; x++)
      for (int z = 0; z < 40; z++) for (int y = 0; y <= 7; y++) h.setBlock(x, y, z, Blocks.STONE);
    for (int x = 3; x < 18; x++) h.setBlock(x, 8, 14, Blocks.OAK_LOG);
    for (int x = 4; x < 14; x++) h.setBlock(x, 7, 10, Blocks.IRON_ORE);
    h.setBlock(17, 5, 17, Blocks.DIAMOND_ORE);
    h.setBlock(6, 8, 8, Blocks.CHEST);
    var leader = npc(h, 7, 8, 9);
    var owner = h.getLevel().getServer().getPlayerList().getPlayer(leader.ownerId());
    var worker = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    worker.initialize(owner, "tortobu");
    worker.setFollowsOwner(false);
    worker.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(9, 8, 9))));
    h.getLevel().addFreshEntity(worker);
    leader.setInstruction("協力して道具を用意し、ダイヤモンドを1個採掘してチェストへ収納して");
    var plan =
        new TaskPlan(
            1,
            "Team preparation and one diamond",
            "",
            List.of(
                new TaskPlan.Step(
                    "delegate",
                    "minecraft:iron_pickaxe",
                    "",
                    1,
                    worker.getId(),
                    -1,
                    "main",
                    1,
                    List.of()),
                NpcSurvival.step("acquire", "minecraft:diamond", "", 1),
                NpcSurvival.at(
                    "deposit",
                    "minecraft:diamond",
                    1,
                    h.absolutePos(new BlockPos(6, 8, 8)).getX(),
                    h.absolutePos(new BlockPos(6, 8, 8)).getY(),
                    h.absolutePos(new BlockPos(6, 8, 8)).getZ())));
    leader.mind().acceptDelegatedPlan(plan);
    h.onEachTick(
        () -> {
          h.assertTrue(
              !leader.aiStatus().startsWith("作業保留"), "Leader failed: " + leader.aiStatus());
          h.assertTrue(
              !worker.aiStatus().startsWith("作業保留"), "Worker failed: " + worker.aiStatus());
          if (!leader.instruction().isBlank()) return;
          var chest =
              (net.minecraft.world.Container)
                  h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(6, 8, 8)));
          int stored = 0;
          for (int i = 0; i < chest.getContainerSize(); i++)
            if (chest.getItem(i).is(Items.DIAMOND)) stored += chest.getItem(i).getCount();
          h.assertTrue(stored == 1, "The shared goal must store exactly one real diamond");
          h.assertTrue(
              NpcWorkData.get(h.getLevel()).jobs.stream()
                  .anyMatch(
                      j ->
                          j.leader.equals(leader.getUUID())
                              && j.worker.equals(worker.getUUID())
                              && j.status.equals("complete")),
              "Worker delivery must be verified");
          leader.discard();
          worker.discard();
          h.succeed();
        });
  }

  private static void bow(GameTestHelper h) {
    for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.STONE);
    var n = npc(h, 3, 1, 3);
    n.inventory.setItem(0, new ItemStack(Items.GOLD_INGOT, 7));
    n.inventory.setItem(1, new ItemStack(Items.BOW));
    n.inventory.setItem(2, new ItemStack(Items.ARROW, 3));
    var skills = new NpcSkills(n, (text, id) -> {});
    var result =
        new java.util.concurrent.atomic.AtomicReference<
            java.util.concurrent.CompletionStage<com.google.gson.JsonObject>>();
    var aim = h.absolutePos(new BlockPos(10, 2, 3));
    skills
        .execute("look_at", CodexBridge.object("x", aim.getX(), "y", aim.getY(), "z", aim.getZ()))
        .thenCompose(v -> skills.execute("use_item", CodexBridge.object("slot", 1, "ticks", 20)))
        .thenAccept(v -> result.set(java.util.concurrent.CompletableFuture.completedFuture(v)));
    h.onEachTick(
        () -> {
          skills.tick();
          if (result.get() == null) return;
          h.assertTrue(
              result.get().toCompletableFuture().join().get("ok").getAsBoolean(),
              "Bow must perform a real release");
          h.assertTrue(count(n, Items.ARROW) == 2, "Exactly one actual arrow must be consumed");
          h.assertTrue(count(n, Items.GOLD_INGOT) == 7, "Unrelated bag contents must be preserved");
          h.assertTrue(
              h
                  .getLevel()
                  .getEntitiesOfClass(
                      net.minecraft.world.entity.projectile.Projectile.class,
                      n.getBoundingBox().inflate(32))
                  .stream()
                  .anyMatch(p -> p.getOwner() == n),
              "Projectile must belong to the actual NPC");
          skills.cancel("done");
          n.discard();
          h.succeed();
        });
  }

  private static void eye(GameTestHelper h) {
    for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.STONE);
    h.setBlock(4, 1, 3, Blocks.END_PORTAL_FRAME);
    var n = npc(h, 3, 1, 3);
    n.inventory.setItem(0, new ItemStack(Items.ENDER_EYE));
    var skills = new NpcSkills(n, (text, id) -> {});
    var p = h.absolutePos(new BlockPos(4, 1, 3));
    var future =
        skills
            .execute(
                "use_on_block",
                CodexBridge.object(
                    "x", p.getX(), "y", p.getY(), "z", p.getZ(), "slot", 0, "face", "up"))
            .toCompletableFuture();
    h.onEachTick(
        () -> {
          if (!future.isDone()) return;
          h.assertTrue(
              future.join().get("ok").getAsBoolean(),
              "Vanilla eye insertion must succeed: " + future.join());
          h.assertTrue(
              h.getLevel().getBlockState(p).getValue(EndPortalFrameBlock.HAS_EYE),
              "Actual frame eye property must be set");
          h.assertTrue(count(n, Items.ENDER_EYE) == 0, "Eye must be consumed");
          n.discard();
          h.succeed();
        });
  }

  private static void portal(GameTestHelper h) {
    for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.STONE);
    for (int x = 4; x <= 7; x++)
      for (int y = 0; y <= 4; y++)
        if (x == 4 || x == 7 || y == 0 || y == 4) h.setBlock(x, y, 6, Blocks.OBSIDIAN);
    var n = npc(h, 5, 1, 3);
    var id = n.getUUID();
    n.inventory.setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
    n.setInstruction("実在するポータルでネザーへ移動して");
    var step =
        new TaskPlan.Step(
            "tool",
            "bep_3davater_companion:travel_dimension",
            "",
            1,
            -1,
            -1,
            "main",
            1,
            List.of(),
            "",
            "{\"dimension\":\"minecraft:the_nether\"}");
    var at = h.absolutePos(new BlockPos(5, 0, 6));
    var light =
        new TaskPlan.Step(
            "tool",
            "bep_3davater_companion:use_on_block",
            "",
            1,
            -1,
            -1,
            "main",
            1,
            List.of(),
            "",
            CodexBridge.object(
                    "x", at.getX(), "y", at.getY(), "z", at.getZ(), "slot", 0, "face", "up")
                .toString());
    n.mind()
        .acceptDelegatedPlan(
            new TaskPlan(1, "Light and walk through real portal", "", List.of(light, step)));
    h.onEachTick(
        () -> {
          var nether = h.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
          if (nether.getEntity(id) instanceof CompanionEntity arrived
              && arrived.instruction().isBlank()) {
            h.assertTrue(!arrived.isRemoved(), "NPC must survive actual dimension transfer");
            arrived.discard();
            h.succeed();
          }
        });
  }

  private static Set<Long> tickets(
      GameTestHelper h, java.util.UUID id, net.minecraft.world.level.TicketStorage storage) {
    var ops =
        h.getLevel()
            .registryAccess()
            .createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
    var json =
        net.minecraft.world.level.TicketStorage.CODEC
            .encodeStart(ops, storage)
            .getOrThrow()
            .getAsJsonObject();
    for (var e : json.getAsJsonArray("neo_ticket_data")) {
      var owner =
          net.neoforged.neoforge.common.world.chunk.ForcedChunkManager.OwnedChunks.CODEC
              .parse(ops, e)
              .getOrThrow();
      if (owner.controller().equals(NpcChunkLoading.CONTROLLER.id())
          && owner.entityChunks().containsKey(id)) {
        var result = new HashSet<Long>();
        for (long chunk : owner.entityChunks().get(id).naturalSpawning()) result.add(chunk);
        return result;
      }
    }
    return Set.of();
  }

  private static void chunks(GameTestHelper h) {
    for (int x = 0; x < 40; x++) for (int z = 0; z < 40; z++) h.setBlock(x, 0, z, Blocks.STONE);
    var n = npc(h, 7, 1, 7);
    var second = new CompanionEntity[1];
    int[] phase = {0};
    h.onEachTick(
        () -> {
          var storage =
              h.getLevel()
                  .getDataStorage()
                  .computeIfAbsent(net.minecraft.world.level.TicketStorage.TYPE);
          if (phase[0] == 0) {
            if (tickets(h, n.getUUID(), storage).size() != 49) return;
            h.assertTrue(
                tickets(h, n.getUUID(), storage)
                    .equals(NpcChunkLoading.square(n.chunkPosition().x(), n.chunkPosition().z())),
                "Exactly current 7x7 must be tick-loaded");
            var ops =
                h.getLevel()
                    .registryAccess()
                    .createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
            var serialized =
                net.minecraft.world.level.TicketStorage.CODEC
                    .encodeStart(ops, storage)
                    .getOrThrow();
            var restored =
                net.minecraft.world.level.TicketStorage.CODEC.parse(ops, serialized).getOrThrow();
            h.assertTrue(
                tickets(h, n.getUUID(), restored).equals(tickets(h, n.getUUID(), storage)),
                "Persistent tickets must survive actual codec round trip");
            n.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(23, 1, 7))));
            phase[0]++;
            return;
          }
          if (phase[0] == 1) {
            if (!tickets(h, n.getUUID(), storage)
                .equals(NpcChunkLoading.square(n.chunkPosition().x(), n.chunkPosition().z())))
              return;
            var owner = h.getLevel().getServer().getPlayerList().getPlayer(n.ownerId());
            second[0] = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
            second[0].initialize(owner, "tortobu");
            second[0].setFollowsOwner(false);
            second[0].setPos(n.position().add(1, 0, 0));
            h.getLevel().addFreshEntity(second[0]);
            phase[0]++;
            return;
          }
          if (phase[0] == 2) {
            if (tickets(h, second[0].getUUID(), storage).size() != 49) return;
            n.discard();
            h.assertTrue(
                tickets(h, n.getUUID(), storage).isEmpty(), "Removed NPC must release its tickets");
            h.assertTrue(
                tickets(h, second[0].getUUID(), storage).size() == 49,
                "One NPC must not release another NPC's overlapping tickets");
            second[0].discard();
            h.succeed();
          }
        });
  }

  private static void boss(GameTestHelper h) {
    for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.STONE);
    var n = npc(h, 3, 1, 3);
    var dragon =
        (net.minecraft.world.entity.boss.enderdragon.EnderDragon)
            net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getValue(net.minecraft.resources.Identifier.withDefaultNamespace("ender_dragon"))
                .create(h.getLevel(), EntitySpawnReason.COMMAND);
    dragon.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(6, 2, 6))));
    dragon
        .getPhaseManager()
        .setPhase(net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.HOVERING);
    h.getLevel().addFreshEntity(dragon);
    float before = dragon.getHealth();
    h.assertTrue(
        NpcCombat.hit(n, dragon.head, n, 8),
        "Companion-specific damage must reach the dragon part");
    h.assertTrue(
        dragon.getHealth() < before,
        "Dragon health must actually decrease, not just return a hit flag");
    dragon.discard();
    n.discard();
    h.succeed();
  }
}

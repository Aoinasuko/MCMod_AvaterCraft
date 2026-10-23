package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.JsonObject;
import dev.avatarcraft.npc.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** Opt-in development GameTest; uses an isolated world and the actual server game operations. */
public final class NpcGameTests {
  public static void register(RegisterGameTestsEvent event) {
    var environment =
        event.registerEnvironment(NpcMod.id("qa"), new TestEnvironmentDefinition.AllOf());
    event.registerTest(
        NpcMod.id("companion_lifecycle"),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(environment, NpcMod.id("qa_empty"), 600, 1, true)) {
          @Override
          public void run(GameTestHelper helper) {
            new Scenario(helper).start();
          }
        });
    event.registerTest(
        NpcMod.id("follow_catch_up"),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(environment, NpcMod.id("qa_empty"), 300, 1, true)) {
          public void run(GameTestHelper h) {
            for (int x = 0; x < 12; x++)
              for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.STONE);
            for (int y = 1; y <= 3; y++)
              for (int z = 0; z <= 5; z++) h.setBlock(5, y, z, Blocks.STONE);
            var owner = h.makeMockServerPlayerInLevel();
            owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(10, 1, 2))));
            var npc = NpcMod.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
            npc.initialize(owner, "tortobu");
            npc.setFollowsOwner(false);
            npc.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 2))));
            h.getLevel().addFreshEntity(npc);
            h.assertTrue(!npc.hasLineOfSight(owner), "Wall must initially obstruct the owner");
            var skills = new NpcSkills(npc, (text, target) -> {});
            var result =
                skills
                    .execute("follow", object("entity_id", owner.getId(), "seconds", 10))
                    .toCompletableFuture();
            boolean[] sprinted = {false};
            h.onEachTick(
                () -> {
                  skills.tick();
                  sprinted[0] |= npc.isSprinting();
                  if (result.isDone()) {
                    h.assertTrue(
                        result.join().get("ok").getAsBoolean(), "Follow must finish successfully");
                    h.assertTrue(sprinted[0], "A distant owner must trigger sprinting");
                    h.assertTrue(
                        npc.distanceToSqr(owner) < 9, "Must navigate around the wall and catch up");
                    h.assertTrue(!npc.isSprinting(), "Sprint must stop after following ends");
                    npc.discard();
                    h.succeed();
                  }
                });
          }
        });
  }

  private record Step(
      String name, Supplier<CompletionStage<JsonObject>> begin, Consumer<JsonObject> verify) {}

  private static final class Scenario {
    final GameTestHelper h;
    final Queue<Step> steps = new ArrayDeque<>();
    CompanionEntity npc;
    ServerPlayer owner;
    NpcSkills skills;
    Step current;
    CompletableFuture<JsonObject> pending;

    Scenario(GameTestHelper helper) {
      h = helper;
    }

    BlockPos pos(int x, int y, int z) {
      return h.absolutePos(new BlockPos(x, y, z));
    }

    JsonObject xyz(int x, int y, int z) {
      var p = pos(x, y, z);
      return object("x", p.getX(), "y", p.getY(), "z", p.getZ());
    }

    void check(boolean condition, String message) {
      h.assertTrue(condition, message);
    }

    int count(Item item) {
      int amount = 0;
      for (int i = 0; i < NpcInventory.SIZE; i++)
        if (npc.inventory.getItem(i).is(item)) amount += npc.inventory.getItem(i).getCount();
      return amount;
    }

    CompletionStage<JsonObject> now(Runnable action) {
      action.run();
      return CompletableFuture.completedFuture(object("ok", true));
    }

    void add(
        String name, Supplier<CompletionStage<JsonObject>> action, Consumer<JsonObject> verify) {
      steps.add(new Step(name, action, verify));
    }

    void success(JsonObject result) {
      check(result.get("ok").getAsBoolean(), current.name + ": " + result);
    }

    void start() {
      for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.STONE);
      owner = h.makeMockServerPlayerInLevel();
      owner.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
      owner.setPos(Vec3.atBottomCenterOf(pos(3, 1, 3)));
      npc = NpcMod.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
      check(npc != null, "NPC must be registered");
      npc.initialize(owner, "tortobu");
      npc.setPos(Vec3.atBottomCenterOf(pos(3, 1, 3)));
      h.getLevel().addFreshEntity(npc);
      skills =
          new NpcSkills(
              npc,
              (text, listener) ->
                  NpcService.get(h.getLevel().getServer()).say(npc, text, listener));
      check(npc.getMaxHealth() == 20, "Player health must be 20");
      check(
          npc.getAttributeValue(Attributes.ATTACK_DAMAGE) == 1,
          "Player bare-hand damage must be one");
      check(npc.inventory.getContainerSize() == 42, "36 bag, four armor, two hands");
      var stranger = h.makeMockPlayer(GameType.SURVIVAL);
      stranger.setPos(npc.position());
      check(!npc.inventory.stillValid(stranger), "Other players cannot edit inventory");
      check(npc.inventory.stillValid(owner), "Owner can edit nearby inventory");
      check(
          NpcChatRouter.receive(owner, "@tortobu こんにちは") == npc.getId()
              && npc.mind().pendingPlayerChats() == 1,
          "Nearby player chat reaches the addressed NPC");
      var menu = new CompanionMenu(1, owner.getInventory(), npc);
      check(
          menu.slots.get(0).x == 8
              && menu.slots.get(0).y == 101
              && menu.slots.get(42).y == 191
              && menu.slots.get(69).y == 249,
          "NPC inventory is above player inventory and hotbar, aligned with supplied texture");
      check(
          menu.slots.stream().allMatch(s -> s.isActive()),
          "Both inventories must be active together");
      menu.slotsVisible = false;
      check(
          menu.slots.stream().noneMatch(s -> s.isActive()), "Other sections must hide every slot");
      menu.slotsVisible = true;
      owner.getInventory().setItem(9, new ItemStack(Items.IRON_HELMET));
      menu.quickMoveStack(owner, NpcInventory.SIZE);
      check(
          npc.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET),
          "Shift transfer equips actual armor");
      npc.inventory.setItem(40, new ItemStack(Items.SHIELD));
      check(npc.getOffhandItem().is(Items.SHIELD), "Offhand slot must equip real shield");
      var output =
          TagValueOutput.createWithContext(
              ProblemReporter.DISCARDING, h.getLevel().registryAccess());
      npc.inventory.setItem(12, new ItemStack(Items.DIAMOND, 3));
      npc.saveWithoutId(output);
      var tag = output.buildResult();
      tag.putInt("foodLevel", 10);
      var restored = NpcMod.COMPANION.get().create(h.getLevel(), EntitySpawnReason.LOAD);
      restored.load(
          TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag));
      check(
          restored.ownedBy(owner)
              && restored.avatarId().equals("tortobu")
              && restored.foodLevel() == 10,
          "Owner, avatar and hunger must survive NBT save/load");
      check(
          restored.inventory.getItem(12).getCount() == 3
              && restored.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET)
              && restored.getOffhandItem().is(Items.SHIELD),
          "Carried items and all equipment survive NBT");
      restored.discard();
      npc.load(
          TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag));
      npc.inventory.setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
      npc.inventory.setItem(NpcInventory.MAIN_HAND, new ItemStack(Items.DIRT, 5));
      add(
          "equip mining tool",
          () -> skills.execute("equip", object("slot", 0, "hand", "main")),
          r -> {
            success(r);
            check(
                npc.getMainHandItem().is(Items.WOODEN_PICKAXE)
                    && npc.inventory.getItem(0).is(Items.DIRT)
                    && npc.inventory.getItem(0).getCount() == 5,
                "Swap must preserve the old main-hand stack");
          });
      add(
          "equip offhand",
          () -> {
            npc.inventory.setItem(10, new ItemStack(Items.TORCH, 12));
            return skills.execute("equip", object("slot", 10, "hand", "off"));
          },
          r -> {
            success(r);
            check(
                npc.getOffhandItem().is(Items.TORCH)
                    && npc.getOffhandItem().getCount() == 12
                    && npc.inventory.getItem(10).is(Items.SHIELD),
                "Offhand swap preserves both stacks");
          });
      add(
          "put away offhand",
          () -> skills.execute("equip", object("slot", 11, "hand", "off")),
          r -> {
            success(r);
            check(
                npc.getOffhandItem().isEmpty() && npc.inventory.getItem(11).getCount() == 12,
                "An empty bag slot unequips the hand without deletion");
          });
      add(
          "reject armor as equip source",
          () -> skills.execute("equip", object("slot", 36, "hand", "main")),
          r -> {
            check(
                !r.get("ok").getAsBoolean() && npc.getMainHandItem().is(Items.WOODEN_PICKAXE),
                "Armor cannot be swapped by bag skill");
          });
      add(
          "reject invalid hand",
          () -> skills.execute("equip", object("slot", 0, "hand", "left")),
          r -> {
            check(!r.get("ok").getAsBoolean(), "Only main and off are accepted");
          });
      h.setBlock(5, 1, 3, Blocks.BEDROCK);
      add(
          "unbreakable block",
          () -> skills.execute("dig", xyz(5, 1, 3)),
          r -> {
            check(!r.get("ok").getAsBoolean(), "Bedrock must not break");
            h.setBlock(5, 1, 3, Blocks.STONE);
          });
      add(
          "normal mining",
          () -> skills.execute("dig", xyz(5, 1, 3)),
          r -> {
            success(r);
            check(h.getLevel().getBlockState(pos(5, 1, 3)).isAir(), "Stone must actually be mined");
            check(npc.getMainHandItem().getDamageValue() > 0, "Real tool durability must decrease");
          });
      add(
          "block placement",
          () -> {
            npc.inventory.setItem(1, new ItemStack(Items.COBBLESTONE, 2));
            var args = xyz(5, 1, 4);
            args.addProperty("slot", 1);
            return skills.execute("place", args);
          },
          r -> {
            success(r);
            check(
                h.getLevel().getBlockState(pos(5, 1, 4)).is(Blocks.COBBLESTONE),
                "Real block must be placed");
            check(
                npc.inventory.getItem(1).getCount() == 1,
                "Placement must consume exactly one item");
          });
      add(
          "container transfer",
          () -> {
            h.setBlock(3, 1, 5, Blocks.CHEST);
            npc.inventory.setItem(2, new ItemStack(Items.DIRT, 6));
            var args = xyz(3, 1, 5);
            args.addProperty("slot", 2);
            args.addProperty("count", 4);
            return skills.execute("deposit", args);
          },
          r -> {
            success(r);
            var container = (Container) h.getLevel().getBlockEntity(pos(3, 1, 5));
            check(
                container.getItem(0).getCount() == 4 && npc.inventory.getItem(2).getCount() == 2,
                "Transfer must conserve item counts");
          });
      add(
          "find chest contents",
          () -> skills.execute("find_chests", object("radius", 4)),
          r -> {
            success(r);
            check(
                r.getAsJsonArray("chests").asList().stream()
                    .anyMatch(
                        e ->
                            e.getAsJsonObject().getAsJsonArray("items").asList().stream()
                                .anyMatch(
                                    i ->
                                        i.getAsJsonObject()
                                                .get("item")
                                                .getAsString()
                                                .equals("minecraft:dirt")
                                            && i.getAsJsonObject().get("count").getAsInt() == 4)),
                "Inspect actual chest slots before extracting");
          });
      add(
          "withdraw from chest",
          () -> {
            var args = xyz(3, 1, 5);
            args.addProperty("slot", 0);
            args.addProperty("count", 3);
            return skills.execute("withdraw", args);
          },
          r -> {
            success(r);
            check(
                r.get("moved").getAsInt() == 3
                    && ((Container) h.getLevel().getBlockEntity(pos(3, 1, 5))).getItem(0).getCount()
                        == 1,
                "Withdrawal consumes exactly the inserted count");
          });
      add(
          "blocked chest",
          () -> {
            h.setBlock(3, 2, 5, Blocks.STONE);
            var args = xyz(3, 1, 5);
            args.addProperty("slot", 0);
            args.addProperty("count", 1);
            return skills.execute("withdraw", args);
          },
          r -> {
            check(
                !r.get("ok").getAsBoolean()
                    && ((Container) h.getLevel().getBlockEntity(pos(3, 1, 5))).getItem(0).getCount()
                        == 1,
                "Blocked chest must not lose items");
            h.setBlock(3, 2, 5, Blocks.AIR);
          });
      add(
          "give item to owner",
          () -> {
            npc.inventory.setItem(13, new ItemStack(Items.GOLD_INGOT, 5));
            return skills.execute(
                "give_item", object("entity_id", owner.getId(), "slot", 13, "count", 2));
          },
          r -> {
            success(r);
            check(
                owner.getInventory().countItem(Items.GOLD_INGOT) == 2
                    && npc.inventory.getItem(13).getCount() == 3,
                "Giving to player conserves both inventories");
          });
      add(
          "reject distant recipient",
          () -> {
            owner.setPos(npc.position().add(6, 0, 0));
            return skills.execute(
                "give_item", object("entity_id", owner.getId(), "slot", 13, "count", 2));
          },
          r -> {
            check(
                !r.get("ok").getAsBoolean() && npc.inventory.getItem(13).getCount() == 3,
                "Out-of-range gift leaves source intact");
            owner.setPos(npc.position());
          });
      var recipient = new CompanionEntity[1];
      add(
          "give partial stack to NPC",
          () -> {
            recipient[0] = NpcMod.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
            recipient[0].initialize(owner, "tortobu");
            recipient[0].setFollowsOwner(false);
            recipient[0].setPos(npc.position().add(1, 0, 0));
            h.getLevel().addFreshEntity(recipient[0]);
            for (int i = 0; i < 36; i++)
              recipient[0].inventory.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            recipient[0].inventory.setItem(5, new ItemStack(Items.GOLD_INGOT, 63));
            return skills.execute(
                "give_item", object("entity_id", recipient[0].getId(), "slot", 13, "count", 3));
          },
          r -> {
            success(r);
            check(
                r.get("moved").getAsInt() == 1
                    && recipient[0].inventory.getItem(5).getCount() == 64
                    && npc.inventory.getItem(13).getCount() == 2,
                "Only accepted part is removed; overflow stays in sender bag");
          });
      add(
          "full NPC rejects gift",
          () ->
              skills.execute(
                  "give_item", object("entity_id", recipient[0].getId(), "slot", 13, "count", 2)),
          r -> {
            check(
                !r.get("ok").getAsBoolean()
                    && r.get("moved").getAsInt() == 0
                    && npc.inventory.getItem(13).getCount() == 2,
                "Full recipient must not delete or drop a gift");
            recipient[0].discard();
          });
      var savedBag = new ArrayList<ItemStack>();
      add(
          "full bag rejects chest withdrawal",
          () -> {
            for (int i = 0; i < 36; i++) {
              savedBag.add(npc.inventory.getItem(i).copy());
              npc.inventory.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            }
            var args = xyz(3, 1, 5);
            args.addProperty("slot", 0);
            args.addProperty("count", 1);
            return skills.execute("withdraw", args);
          },
          r -> {
            check(
                !r.get("ok").getAsBoolean()
                    && ((Container) h.getLevel().getBlockEntity(pos(3, 1, 5))).getItem(0).getCount()
                        == 1,
                "Full bag leaves chest contents unchanged");
            for (int i = 0; i < 36; i++) npc.inventory.setItem(i, savedBag.get(i));
          });
      add(
          "crafting",
          () -> {
            npc.inventory.setItem(3, new ItemStack(Items.OAK_LOG));
            return skills.execute("craft", object("recipe", "minecraft:oak_planks"));
          },
          r -> {
            success(r);
            check(
                count(Items.OAK_LOG) == 0 && count(Items.OAK_PLANKS) == 4,
                "Registered crafting consumes log and produces four planks");
          });
      add(
          "missing materials",
          () -> skills.execute("craft", object("recipe", "minecraft:diamond_block")),
          r ->
              check(
                  !r.get("ok").getAsBoolean() && count(Items.DIAMOND) == 3,
                  "Failed crafting must not consume any materials"));
      int[] soulstoneMaterials = new int[2];
      add(
          "craft soulstone",
          () -> {
            h.setBlock(2, 1, 3, Blocks.CRAFTING_TABLE);
            npc.inventory.setItem(4, new ItemStack(Items.IRON_INGOT, 4));
            npc.inventory.setItem(5, new ItemStack(Items.GOLD_INGOT, 5));
            soulstoneMaterials[0] = count(Items.IRON_INGOT);
            soulstoneMaterials[1] = count(Items.GOLD_INGOT);
            return skills.execute("craft", object("recipe", "avatarcraftnpc:soulstone"));
          },
          r -> {
            success(r);
            check(count(NpcMod.SOULSTONE.get()) == 1, "Soulstone recipe produces one item");
            check(
                count(Items.IRON_INGOT) == soulstoneMaterials[0] - 4
                    && count(Items.GOLD_INGOT) == soulstoneMaterials[1] - 5,
                "Soulstone consumes four iron ingots and five gold ingots");
          });
      add(
          "craft journal",
          () -> {
            npc.inventory.setItem(4, new ItemStack(Items.AMETHYST_SHARD));
            npc.inventory.setItem(5, new ItemStack(Items.BOOK));
            return skills.execute("craft", object("recipe", "avatarcraftnpc:memory_journal"));
          },
          r -> {
            success(r);
            check(count(NpcMod.MEMORY_JOURNAL.get()) == 1, "Journal recipe must be craftable");
          });
      add(
          "eat actual food",
          () -> {
            npc.inventory.setItem(9, new ItemStack(Items.APPLE, 2));
            return skills.execute("eat", object("slot", 9));
          },
          this::success);
      add(
          "cannot interrupt consumption with equip",
          () -> skills.execute("equip", object("slot", 0, "hand", "main")),
          r ->
              check(
                  !r.get("ok").getAsBoolean() && npc.isUsingItem(),
                  "Equip must not interrupt eating"));
      add(
          "finish eating",
          () -> skills.execute("wait", object("seconds", 2)),
          r -> {
            success(r);
            check(
                npc.foodLevel() == 14 && count(Items.APPLE) == 1,
                "Eating raises real hunger by four and consumes one apple");
          });
      add(
          "soulstone summons standard avatar",
          () ->
              now(
                  () -> {
                    owner.setItemInHand(
                        InteractionHand.MAIN_HAND, new ItemStack(NpcMod.SOULSTONE.get()));
                    var support = pos(8, 0, 8);
                    var hit =
                        new BlockHitResult(
                            Vec3.atCenterOf(support).add(0, .5, 0), Direction.UP, support, false);
                    var result =
                        owner
                            .getMainHandItem()
                            .useOn(new UseOnContext(owner, InteractionHand.MAIN_HAND, hit));
                    check(
                        result.consumesAction() && owner.getMainHandItem().isEmpty(),
                        "Survival summoning consumes one charm");
                    check(
                        h
                            .getLevel()
                            .getEntitiesOfClass(
                                CompanionEntity.class,
                                new AABB(
                                    Vec3.atLowerCornerOf(pos(7, 1, 7)),
                                    Vec3.atLowerCornerOf(pos(10, 4, 10))))
                            .stream()
                            .anyMatch(e -> e.avatarId().equals("tortobu") && e.ownedBy(owner)),
                        "Summoned NPC uses tortobu and owner UUID");
                  }),
          this::success);
      add(
          "persistent conversation journal",
          () -> {
            var service = NpcService.get(h.getLevel().getServer());
            service.say(npc, "検証の会話です", -1);
            return service.readJournal(
                () ->
                    object(
                        "ok",
                        service
                            .journal
                            .read(owner.getUUID(), npc.getUUID(), "", 12)
                            .entries()
                            .stream()
                            .anyMatch(
                                e -> e.kind().equals("speech") && e.text().equals("検証の会話です"))));
          },
          this::success);
      add(
          "walking navigation",
          () -> {
            h.setBlock(5, 1, 4, Blocks.AIR);
            h.setBlock(3, 1, 5, Blocks.AIR);
            return skills.execute("move_to", xyz(7, 1, 3));
          },
          r -> {
            success(r);
            check(
                npc.distanceToSqr(Vec3.atCenterOf(pos(7, 1, 3))) <= 2.3,
                "Movement must arrive using navigation");
          });
      add(
          "owner range protection",
          () ->
              now(
                  () -> {
                    owner.setPos(npc.position().add(20, 0, 0));
                    check(
                        !npc.inventory.stillValid(owner),
                        "Owner inventory closes when too far away");
                  }),
          this::success);
      add(
          "death preserves equipment",
          () ->
              now(
                  () -> {
                    var victim =
                        NpcMod.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
                    victim.initialize(owner, "tortobu");
                    victim.setPos(Vec3.atBottomCenterOf(pos(9, 1, 1)));
                    var pick = new ItemStack(Items.WOODEN_PICKAXE);
                    pick.setDamageValue(5);
                    var helmet = new ItemStack(Items.IRON_HELMET);
                    helmet.setDamageValue(7);
                    var shield = new ItemStack(Items.SHIELD);
                    shield.setDamageValue(9);
                    victim.inventory.setItem(NpcInventory.MAIN_HAND, pick);
                    victim.inventory.setItem(36, helmet);
                    victim.inventory.setItem(40, shield);
                    victim.inventory.setItem(1, new ItemStack(Items.DIRT, 5));
                    h.getLevel().addFreshEntity(victim);
                    victim.hurtServer(h.getLevel(), victim.damageSources().genericKill(), 999);
                    var drops =
                        h.getLevel()
                            .getEntitiesOfClass(
                                net.minecraft.world.entity.item.ItemEntity.class,
                                victim.getBoundingBox().inflate(1.5));
                    for (var expected : new ItemStack[] {pick, helmet, shield})
                      check(
                          drops.stream()
                              .anyMatch(
                                  e ->
                                      e.getItem().is(expected.getItem())
                                          && e.getItem().getDamageValue()
                                              == expected.getDamageValue()),
                          "Non-player death must preserve equipment and durability");
                    check(
                        drops.stream()
                                .filter(e -> e.getItem().is(Items.DIRT))
                                .mapToInt(e -> e.getItem().getCount())
                                .sum()
                            == 5,
                        "Death must also drop carried items");
                  }),
          this::success);
      h.onEachTick(this::tick);
    }

    void tick() {
      try {
        skills.tick();
        if (pending != null) {
          if (!pending.isDone()) return;
          var value = pending.join();
          current.verify.accept(value);
          System.getLogger("AvatarCraft NPC QA")
              .log(System.Logger.Level.INFO, "PASS: " + current.name);
          pending = null;
        }
        if (steps.isEmpty()) {
          skills.cancel("QA finished");
          h.succeed();
          return;
        }
        current = steps.remove();
        pending = current.begin.get().toCompletableFuture();
      } catch (Throwable e) {
        h.fail((current == null ? "setup" : current.name) + ": " + e);
      }
    }
  }

  private NpcGameTests() {}
}

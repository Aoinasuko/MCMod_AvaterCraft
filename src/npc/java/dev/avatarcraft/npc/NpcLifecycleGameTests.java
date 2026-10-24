package dev.avatarcraft.npc;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Isolated server scenarios for actual damage, retained inventory, cross-dimension recall and caps.
 */
public final class NpcLifecycleGameTests {
  private static void test(
      RegisterGameTestsEvent event,
      net.minecraft.core.Holder<TestEnvironmentDefinition<?>> env,
      String name,
      java.util.function.Consumer<GameTestHelper> action) {
    event.registerTest(
        NpcMod.id(name),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(env, NpcMod.id("qa_empty"), 400, 1, true)) {
          public void run(GameTestHelper h) {
            action.accept(h);
          }
        });
  }

  public static void register(RegisterGameTestsEvent event) {
    var env =
        event.registerEnvironment(NpcMod.id("lifecycle_qa"), new TestEnvironmentDefinition.AllOf());
    test(event, env, "death_drops_and_owner_respawn", h -> death(h, true));
    test(event, env, "death_preserves_and_owner_respawn", h -> death(h, false));
    test(event, env, "recall_across_dimensions", NpcLifecycleGameTests::recall);
    test(event, env, "server_summon_cap_and_roster", NpcLifecycleGameTests::limit);
    test(event, env, "recall_and_journal_recipes", NpcLifecycleGameTests::recipes);
  }

  private static ServerPlayer owner(GameTestHelper h) {
    for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.STONE);
    var p = h.makeMockServerPlayerInLevel();
    p.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(8, 1, 8))));
    return p;
  }

  private static CompanionEntity npc(GameTestHelper h, ServerPlayer owner) {
    var n = NpcMod.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    n.initialize(owner, "tortobu");
    n.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3, 1, 3))));
    n.setFollowsOwner(false);
    h.getLevel().addFreshEntity(n);
    return n;
  }

  private static void death(GameTestHelper h, boolean drop) {
    var owner = owner(h);
    var n = npc(h, owner);
    var id = n.getUUID();
    var at = owner.blockPosition();
    var previous = owner.getRespawnConfig();
    owner.setRespawnPosition(
        new ServerPlayer.RespawnConfig(
            LevelData.RespawnData.of(h.getLevel().dimension(), at, 0, 0), true),
        false);
    n.inventory.setItem(0, new ItemStack(Items.DIAMOND, 3));
    n.inventory.setItem(NpcInventory.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
    n.inventory.setItem(36, new ItemStack(Items.IRON_HELMET));
    n.setInstruction("テスト作業を中断して復帰");
    n.setFollowsOwner(true);
    var deathAt = n.position();
    boolean configured = NpcSettings.DROP_ITEMS_ON_DEATH.get();
    NpcSettings.DROP_ITEMS_ON_DEATH.set(drop);
    try {
      n.hurtServer(h.getLevel(), n.damageSources().genericKill(), 10000);
    } finally {
      NpcSettings.DROP_ITEMS_ON_DEATH.set(configured);
    }
    h.onEachTick(
        () -> {
          var p = h.getLevel().getServer().getPlayerList().getPlayer(n.ownerId());
          var alive = NpcRoster.find(h.getLevel().getServer(), id);
          if (alive == null || !alive.isAlive()) return;
          h.assertTrue(
              alive.position().distanceToSqr(Vec3.atBottomCenterOf(at)) < 12,
              "NPC must return to owner respawn");
          h.assertTrue(
              alive.instruction().isBlank() && !alive.followsOwner(),
              "Old work and follow must be cancelled");
          var drops =
              h.getLevel()
                  .getEntitiesOfClass(ItemEntity.class, new AABB(deathAt, deathAt).inflate(2));
          int diamonds =
              drops.stream()
                  .filter(e -> e.getItem().is(Items.DIAMOND))
                  .mapToInt(e -> e.getItem().getCount())
                  .sum();
          h.assertTrue(
              drop ? diamonds == 3 : diamonds == 0, "Drop option must control actual ground items");
          h.assertTrue(
              drop
                  ? alive.inventory.isEmpty()
                  : alive.inventory.getItem(0).getCount() == 3
                      && alive.getMainHandItem().is(Items.IRON_SWORD)
                      && alive.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET),
              "Bag and equipment must not duplicate or disappear");
          if (p != null) p.setRespawnPosition(previous, false);
          alive.discard();
          h.succeed();
        });
  }

  private static void recall(GameTestHelper h) {
    var owner = owner(h);
    owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(NpcMod.RECALL_MAP.get()));
    var n = npc(h, owner);
    var id = n.getUUID();
    var nether = h.getLevel().getServer().getLevel(Level.NETHER);
    n.setInstruction("中断される作業");
    n.setFollowsOwner(true);
    var remote =
        (CompanionEntity)
            n.teleport(
                new TeleportTransition(
                    nether, new Vec3(10, 100, 10), Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
    h.assertTrue(remote != null, "Real cross dimension transfer must work");
    NpcRoster.get(h.getLevel().getServer()).update(remote);
    var stranger = h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
    h.assertTrue(
        NpcRoster.get(h.getLevel().getServer()).owned(stranger.getUUID(), id) == null,
        "Other player must not enumerate or claim this actor");
    var other = owner(h);
    other.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(NpcMod.RECALL_MAP.get()));
    h.assertTrue(
        NpcRecall.request(other, id).contains("自分のNPC"),
        "Server must reject a forged request for another owner's NPC");
    NpcRecall.request(owner, id);
    h.onEachTick(
        () -> {
          var actor = NpcRoster.find(h.getLevel().getServer(), id);
          if (actor == null || actor.level() != owner.level()) return;
          h.assertTrue(
              actor.distanceToSqr(owner) < 100
                  && actor.instruction().isBlank()
                  && !actor.followsOwner(),
              "Recalled NPC must be near owner and in standby");
          actor.discard();
          h.succeed();
        });
  }

  private static void limit(GameTestHelper h) {
    var owner = owner(h);
    var n = npc(h, owner);
    var roster = NpcRoster.get(h.getLevel().getServer());
    var second = npc(h, owner);
    npc(h, owner);
    var nether = h.getLevel().getServer().getLevel(Level.NETHER);
    second.teleport(
        new TeleportTransition(
            nether, new Vec3(10, 110, 10), Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
    int count = roster.owned(owner.getUUID()).size();
    h.assertTrue(
        !roster.canSummon(owner.getUUID(), count),
        "Same-owner existing actors must count toward cap");
    h.assertTrue(roster.canSummon(owner.getUUID(), -1), "-1 must be unlimited");
    var codec = NpcRoster.TYPE.codecFactory().create(h.getLevel());
    var saved =
        codec
            .parse(
                com.mojang.serialization.JsonOps.INSTANCE,
                codec.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, roster).getOrThrow())
            .getOrThrow();
    h.assertTrue(
        saved.owned(owner.getUUID(), n.getUUID()) != null
            && !saved.canSummon(owner.getUUID(), count),
        "Roster and cap must survive saved-data round trip");
    var pos = h.absolutePos(new BlockPos(5, 0, 5));
    owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(NpcMod.SOULSTONE.get(), 2));
    int configured = NpcSettings.MAX_PER_PLAYER.get();
    NpcSettings.MAX_PER_PLAYER.set(3);
    try {
      var result =
          new ItemStack(NpcMod.SOULSTONE.get())
              .useOn(
                  new UseOnContext(
                      owner,
                      InteractionHand.MAIN_HAND,
                      new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)));
      h.assertTrue(
          result == InteractionResult.FAIL && owner.getMainHandItem().getCount() == 2,
          "Server cap must reject summon without consuming item");
      NpcSettings.MAX_PER_PLAYER.set(-1);
      var allowed =
          new ItemStack(NpcMod.SOULSTONE.get())
              .useOn(
                  new UseOnContext(
                      owner,
                      InteractionHand.MAIN_HAND,
                      new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)));
      h.assertTrue(
          allowed == InteractionResult.SUCCESS && roster.owned(owner.getUUID()).size() == 4,
          "Unlimited setting must allow the fourth actual summon across dimensions");
    } finally {
      NpcSettings.MAX_PER_PLAYER.set(configured);
    }
    for (var entry : roster.owned(owner.getUUID())) {
      var actor = NpcRoster.find(h.getLevel().getServer(), entry.id());
      if (actor != null) actor.discard();
    }
    h.succeed();
  }

  private static void recipes(GameTestHelper h) {
    var owner = owner(h);
    var n = npc(h, owner);
    h.setBlock(4, 1, 3, Blocks.CRAFTING_TABLE);
    n.inventory.setItem(0, new ItemStack(Items.IRON_INGOT, 9));
    n.inventory.setItem(1, new ItemStack(Items.MAP));
    n.inventory.setItem(2, new ItemStack(Items.BOOK, 8));
    var skills = new dev.avatarcraft.npc.ai.NpcSkills(n, (s, id) -> {});
    var result =
        skills
            .execute(
                "craft",
                dev.avatarcraft.npc.ai.CodexBridge.object("recipe", "avatarcraftnpc:recall_map"))
            .thenCompose(
                r -> {
                  h.assertTrue(
                      r.get("ok").getAsBoolean(),
                      "Eight iron around one empty map must craft recall map");
                  return skills.execute(
                      "craft",
                      dev.avatarcraft.npc.ai.CodexBridge.object(
                          "recipe", "avatarcraftnpc:memory_journal"));
                })
            .toCompletableFuture();
    h.onEachTick(
        () -> {
          if (!result.isDone()) return;
          h.assertTrue(
              result.join().get("ok").getAsBoolean(),
              "Eight books around one iron must craft journal");
          int maps = 0, books = 0, materials = 0;
          for (int i = 0; i < NpcInventory.SIZE; i++) {
            var stack = n.inventory.getItem(i);
            if (stack.is(NpcMod.RECALL_MAP.get())) maps += stack.getCount();
            if (stack.is(NpcMod.MEMORY_JOURNAL.get())) books += stack.getCount();
            if (stack.is(Items.IRON_INGOT) || stack.is(Items.MAP) || stack.is(Items.BOOK))
              materials += stack.getCount();
          }
          h.assertTrue(
              maps == 1 && books == 1 && materials == 0,
              "Recipes must consume exactly nine iron, eight books and one empty map");
          skills.cancel("done");
          n.discard();
          h.succeed();
        });
  }
}

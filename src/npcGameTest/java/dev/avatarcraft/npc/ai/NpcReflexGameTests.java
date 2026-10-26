package dev.avatarcraft.npc.ai;

import dev.avatarcraft.npc.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** Reflexes act through the entity's own tick, without an instruction or a model. */
final class NpcReflexGameTests {
  static void register(RegisterGameTestsEvent event) {
    var env =
        event.registerEnvironment(
            bep_3davater_companion.id("reflex_qa"), new TestEnvironmentDefinition.AllOf());
    test(event, env, "reflex_strikes_back_without_instruction", h -> strike(h, false));
    test(event, env, "reflex_defends_the_owner", h -> strike(h, true));
    test(event, env, "reflex_steps_out_of_lava", NpcReflexGameTests::lava);
    test(event, env, "reflex_runs_from_a_swelling_creeper", NpcReflexGameTests::creeper);
    test(event, env, "reflex_raises_shield_against_arrow", NpcReflexGameTests::arrow);
    test(event, env, "reflex_retreats_when_badly_hurt", NpcReflexGameTests::retreat);
    test(event, env, "reflex_stops_at_a_deep_edge", NpcReflexGameTests::edge);
  }

  private static void test(
      RegisterGameTestsEvent event,
      Holder<TestEnvironmentDefinition<?>> env,
      String name,
      Consumer<GameTestHelper> body) {
    event.registerTest(
        bep_3davater_companion.id(name),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(env, bep_3davater_companion.id("qa_navigation"), 600, 1, true)) {
          public void run(GameTestHelper h) {
            body.accept(h);
          }
        });
  }

  private static CompanionEntity fixture(GameTestHelper h, BlockPos at) {
    for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) h.setBlock(x, 0, z, Blocks.STONE);
    var owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(2, 1, 2))));
    var n = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    n.initialize(owner, "tortobu");
    n.setFollowsOwner(false);
    n.setPos(Vec3.atBottomCenterOf(h.absolutePos(at)));
    h.getLevel().addFreshEntity(n);
    return n;
  }

  /** A monster that hit the NPC (or its owner) is fought off although no task asks for it. */
  private static void strike(GameTestHelper h, boolean owner) {
    var n = fixture(h, new BlockPos(6, 1, 6));
    n.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
    var zombie = EntityTypes.ZOMBIE.create(h.getLevel(), EntitySpawnReason.COMMAND);
    zombie.setNoAi(true);
    // A helmet keeps daylight from killing it; only the NPC can.
    zombie.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
    var p = h.absoluteVec(new Vec3(9.5, 1, 6.5));
    zombie.setPos(p.x, p.y, p.z);
    h.getLevel().addFreshEntity(zombie);
    var player = h.getLevel().getServer().getPlayerList().getPlayer(n.ownerId());
    // The mock owner starts in creative mode, where it cannot be hurt.
    player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
    LivingEntity victim = owner ? player : n;
    int[] ticks = {0};
    h.onEachTick(
        () -> {
          // A freshly joined player is briefly invulnerable, so the hit lands a little later.
          if (++ticks[0] == 80)
            h.assertTrue(
                victim.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(zombie), 1),
                "The monster's hit must land");
          if (zombie.isAlive()) return;
          h.assertTrue(n.isAlive(), "NPC must survive its own counter-attack");
          n.discard();
          h.succeed();
        });
  }

  /** Sunk in a lava pit, the NPC climbs to the nearest safe floor by itself. */
  private static void lava(GameTestHelper h) {
    var n = fixture(h, new BlockPos(6, 0, 6));
    // Wide enough that bobbing in place does not drift the NPC to the rim by chance.
    for (int x = 4; x <= 8; x++)
      for (int z = 4; z <= 8; z++) {
        h.setBlock(x, -1, z, Blocks.STONE);
        h.setBlock(x, 0, z, Blocks.LAVA);
      }
    int[] ticks = {0};
    h.onEachTick(
        () -> {
          ticks[0]++;
          if (n.isInLava() || ticks[0] < 10) return;
          h.assertTrue(n.isAlive(), "NPC must get out alive");
          h.assertTrue(ticks[0] < 80, "NPC must leave the lava quickly: " + ticks[0]);
          n.discard();
          h.succeed();
        });
  }

  /** A primed creeper next to the NPC: it runs before the blast. */
  private static void creeper(GameTestHelper h) {
    var n = fixture(h, new BlockPos(6, 1, 6));
    var creeper = EntityTypes.CREEPER.create(h.getLevel(), EntitySpawnReason.COMMAND);
    creeper.setNoAi(true);
    var p = h.absoluteVec(new Vec3(8.5, 1, 6.5));
    creeper.setPos(p.x, p.y, p.z);
    h.getLevel().addFreshEntity(creeper);
    creeper.ignite();
    h.onEachTick(
        () -> {
          if (creeper.isAlive()) return;
          h.assertTrue(n.isAlive() && n.getHealth() > 10, "NPC must be clear of the blast: " + n.getHealth());
          n.discard();
          h.succeed();
        });
  }

  /** A skeleton draws on the NPC and looses; the shield in the off hand takes the arrow. */
  private static void arrow(GameTestHelper h) {
    var n = fixture(h, new BlockPos(6, 1, 6));
    n.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
    var skeleton = EntityTypes.SKELETON.create(h.getLevel(), EntitySpawnReason.COMMAND);
    skeleton.setNoAi(true);
    var at = h.absoluteVec(new Vec3(13.5, 1, 6.5));
    skeleton.setPos(at.x, at.y, at.z);
    skeleton.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
    skeleton.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
    h.getLevel().addFreshEntity(skeleton);
    int[] ticks = {0};
    float[] start = {0};
    h.onEachTick(
        () -> {
          ticks[0]++;
          if (ticks[0] == 10) {
            start[0] = n.getHealth();
            skeleton.setTarget(n);
            skeleton.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
          }
          if (ticks[0] == 40) {
            var shot = new net.minecraft.world.entity.projectile.arrow.Arrow(
                h.getLevel(), skeleton, new ItemStack(Items.ARROW), null);
            var from = h.absoluteVec(new Vec3(12.5, 2.2, 6.5));
            shot.setPos(from.x, from.y, from.z);
            shot.setDeltaMovement(n.getBoundingBox().getCenter().subtract(from).normalize().scale(2));
            h.getLevel().addFreshEntity(shot);
          }
          if (ticks[0] < 80) return;
          h.assertTrue(n.getHealth() >= start[0], "The shield must stop the arrow: " + n.getHealth() + " < " + start[0]);
          n.discard();
          h.succeed();
        });
  }

  /** At low health the NPC backs away from the monster instead of trading blows. */
  private static void retreat(GameTestHelper h) {
    var n = fixture(h, new BlockPos(6, 1, 6));
    n.setHealth(4);
    var zombie = EntityTypes.ZOMBIE.create(h.getLevel(), EntitySpawnReason.COMMAND);
    zombie.setNoAi(true);
    zombie.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
    var p = h.absoluteVec(new Vec3(8.5, 1, 6.5));
    zombie.setPos(p.x, p.y, p.z);
    h.getLevel().addFreshEntity(zombie);
    int[] ticks = {0};
    h.onEachTick(
        () -> {
          if (++ticks[0] == 5) n.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(zombie), 1);
          if (ticks[0] < 60) return;
          h.assertTrue(n.distanceTo(zombie) > 6, "NPC must back away: " + n.distanceTo(zombie));
          zombie.discard();
          n.discard();
          h.succeed();
        });
  }

  /** Knocked toward a deep drop, the NPC stops on the rim. */
  private static void edge(GameTestHelper h) {
    var n = fixture(h, new BlockPos(6, 1, 6));
    // Floor ends at x=8; beyond it a deep shaft.
    for (int x = 9; x < 16; x++)
      for (int z = 0; z < 16; z++) for (int y = -6; y <= 0; y++) h.setBlock(x, y, z, Blocks.AIR);
    int[] ticks = {0};
    h.onEachTick(
        () -> {
          ticks[0]++;
          if (ticks[0] > 5 && ticks[0] < 30 && n.onGround()) n.setDeltaMovement(.2, n.getDeltaMovement().y, 0);
          if (ticks[0] < 60) return;
          h.assertTrue(n.getY() > h.absoluteVec(new Vec3(0, .5, 0)).y, "NPC must stay on the rim: " + n.position());
          n.discard();
          h.succeed();
        });
  }
}

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
}

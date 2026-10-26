package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import dev.avatarcraft.npc.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Per-tick rules that act before planning, like a player's reflexes: strike back at a monster that
 * hurt the NPC or its owner, and step out of lava or fire. While one is active the planned work is
 * paused, not cancelled. No model is consulted.
 */
public final class NpcReflexes {
  /** How recent a hit must be to answer it. */
  static final int RECENT_HIT = 100;
  /** One engagement is bounded so a fleeing monster cannot hold the NPC off its work. */
  static final int ENGAGEMENT = 200;

  private final CompanionEntity npc;
  private LivingEntity target;
  private long engagedSince, lastAttack;
  private BlockPos refuge;
  private long hazardSince;

  public NpcReflexes(CompanionEntity npc) {
    this.npc = npc;
  }

  public boolean active() {
    return target != null || refuge != null;
  }

  /** Returns true while a reflex owns the body this tick. */
  public boolean tick() {
    if (!(npc.level() instanceof ServerLevel level)) return false;
    long now = level.getGameTime();
    if (hazard(level, now)) return true;
    // A planned attack, hunt or guard already fights; do not compete with it.
    if (now - npc.lastCombatAction < 20) {
      end(level, "planned_combat");
      return false;
    }
    if (target == null) {
      var threat = threat();
      if (threat == null) return false;
      target = threat;
      engagedSince = now;
      record(level, "reflex_start", "Counter-attack", object("entity_id", threat.getId()));
    }
    if (!target.isAlive()
        || target.level() != npc.level()
        || npc.distanceToSqr(target) > 12 * 12
        || now - engagedSince > ENGAGEMENT
        || npc.getHealth() < 6) {
      end(level, target.isAlive() ? "disengaged" : "defeated");
      return false;
    }
    npc.getLookControl().setLookAt(target);
    if (npc.distanceToSqr(target) > 6.25 || !npc.hasLineOfSight(target)) {
      npc.getNavigation().moveTo(target, 2.2);
      npc.setSprinting(NpcSkills.canSprint(npc));
      return true;
    }
    npc.getNavigation().stop();
    npc.setSprinting(false);
    int interval =
        Math.max(1, (int) Math.ceil(20 / Math.max(.1, npc.getAttributeValue(Attributes.ATTACK_SPEED))));
    if (now - lastAttack >= interval) {
      lastAttack = now;
      npc.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
      if (npc.doHurtTarget(level, target)) npc.exhaust(.1f);
    }
    return true;
  }

  /** A monster that recently hurt the NPC or its owner and is close enough to answer. */
  private LivingEntity threat() {
    if (npc.getHealth() < 6) return null;
    var own = npc.getLastHurtByMob();
    if (answerable(own, npc.tickCount - npc.getLastHurtByMobTimestamp())) return own;
    var owner =
        npc.ownerId() == null
            ? null
            : npc.level().getServer().getPlayerList().getPlayer(npc.ownerId());
    if (owner != null && owner.level() == npc.level()) {
      var attacker = owner.getLastHurtByMob();
      if (answerable(attacker, owner.tickCount - owner.getLastHurtByMobTimestamp()))
        return attacker;
    }
    return null;
  }

  private boolean answerable(LivingEntity mob, int age) {
    return mob instanceof Enemy
        && mob.isAlive()
        && age >= 0
        && age < RECENT_HIT
        && npc.distanceToSqr(mob) <= 8 * 8
        && npc.hasLineOfSight(mob);
  }

  /** In lava or fire: walk to the nearest safe floor within a few blocks, preferring water. */
  private boolean hazard(ServerLevel level, long now) {
    boolean burning = npc.isInLava() || burningBlock(level, npc.blockPosition());
    if (!burning && refuge == null) return false;
    if (!burning && npc.blockPosition().equals(refuge) || now - hazardSince > 60 && refuge != null) {
      record(level, "reflex_end", "Hazard escape", object("reason", burning ? "timeout" : "safe"));
      refuge = null;
      return false;
    }
    if (refuge == null || burningBlock(level, refuge)) {
      refuge = refuge(level);
      hazardSince = now;
      if (refuge == null) return false;
      target = null;
      record(level, "reflex_start", "Hazard escape", object("x", refuge.getX(), "y", refuge.getY(), "z", refuge.getZ()));
    }
    npc.getNavigation().stop();
    npc.setSprinting(true);
    var at = Vec3.atBottomCenterOf(refuge);
    npc.getMoveControl().setWantedPosition(at.x, refuge.getY(), at.z, 2.2);
    if (npc.horizontalCollision || at.y > npc.getY() + .5) npc.getJumpControl().jump();
    return true;
  }

  private BlockPos refuge(ServerLevel level) {
    var here = npc.blockPosition();
    BlockPos best = null;
    double score = Double.POSITIVE_INFINITY;
    for (var p : BlockPos.betweenClosed(here.offset(-4, -2, -4), here.offset(4, 2, 4))) {
      if (!level.hasChunkAt(p) || burningBlock(level, p) || burningBlock(level, p.below())) continue;
      boolean water = level.getFluidState(p).isSource() && level.getBlockState(p).is(Blocks.WATER);
      if (!water && !NpcWalkSurface.standable(npc, p)) continue;
      double cost = p.distSqr(here) - (water ? 6 : 0);
      if (cost < score) {
        score = cost;
        best = p.immutable();
      }
    }
    return best;
  }

  private static boolean burningBlock(ServerLevel level, BlockPos p) {
    var state = level.getBlockState(p);
    return state.is(BlockTags.FIRE)
        || state.is(Blocks.LAVA)
        || level.getFluidState(p).is(net.minecraft.tags.FluidTags.LAVA);
  }

  private void end(ServerLevel level, String reason) {
    if (target == null) return;
    record(level, "reflex_end", "Counter-attack", object("reason", reason));
    target = null;
    npc.getNavigation().stop();
    npc.setSprinting(false);
  }

  private void record(ServerLevel level, String kind, String text, com.google.gson.JsonObject details) {
    NpcService.get(level.getServer()).record(npc, kind, text, null, details);
  }
}

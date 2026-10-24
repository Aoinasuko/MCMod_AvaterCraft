package dev.avatarcraft.npc.ai;

import dev.avatarcraft.npc.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.*;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;

/** Companion-only boss damage type; ordinary mobs and their damage types are unchanged. */
public final class NpcCombat {
  private static final ResourceKey<DamageType> TYPE =
      ResourceKey.create(Registries.DAMAGE_TYPE, NpcMod.id("companion_attack"));

  static boolean boss(Entity target) {
    return target instanceof EnderDragon || target instanceof EnderDragonPart;
  }

  static Entity entity(ServerLevel level, int id) {
    var e = level.getEntity(id);
    if (e != null) return e;
    for (var entity : level.getAllEntities())
      if (entity instanceof EnderDragon dragon)
        for (var part : dragon.getSubEntities()) if (part.getId() == id) return part;
    return null;
  }

  static boolean hit(CompanionEntity npc, Entity target, Entity direct, float amount) {
    var level = (ServerLevel) npc.level();
    var source =
        new DamageSource(
            level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(TYPE),
            direct,
            npc);
    return target.hurtServer(level, source, amount);
  }

  public static void projectile(ProjectileImpactEvent event) {
    if (!(event.getProjectile() instanceof AbstractArrow arrow)
        || !(arrow.getOwner() instanceof CompanionEntity npc)
        || !(event.getRayTraceResult() instanceof EntityHitResult hit)
        || !boss(hit.getEntity())
        || !(npc.level() instanceof ServerLevel)) return;
    var level = (ServerLevel) npc.level();
    var saved =
        net.minecraft.world.level.storage.TagValueOutput.createWithContext(
            net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess());
    arrow.saveWithoutId(saved);
    float base = (float) saved.buildResult().getDoubleOr("damage", 2.0);
    var source =
        new DamageSource(
            level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(TYPE),
            arrow,
            npc);
    if (arrow.getWeaponItem() != null)
      base =
          net.minecraft.world.item.enchantment.EnchantmentHelper.modifyDamage(
              level, arrow.getWeaponItem(), hit.getEntity(), source, base);
    int damage = (int) Math.ceil(Math.clamp(arrow.getDeltaMovement().length() * base, 0, 100));
    if (arrow.isCritArrow()) damage += level.getRandom().nextInt(damage / 2 + 2);
    hit(npc, hit.getEntity(), arrow, damage);
    arrow.discard();
    event.setCanceled(true);
  }
}

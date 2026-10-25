package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.*;
import dev.avatarcraft.npc.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.util.*;

/** Small, actual vanilla interactions. The fake player inventory is copied back and restored. */
final class NpcInteractions implements AutoCloseable {
  private final CompanionEntity npc;
  private final NpcSkills skills;
  private Vec3 aim;
  private long aimUntil, until;
  private int useTicks, beforeCount;
  private Item beforeItem;
  private boolean consuming;
  private CompletableFuture<JsonObject> pending;

  NpcInteractions(CompanionEntity npc, NpcSkills skills) {
    this.npc = npc;
    this.skills = skills;
  }

  boolean busy() {
    return pending != null;
  }

  boolean supports(String name) {
    return Set.of(
            "look_at",
            "jump",
            "set_sneaking",
            "set_sprinting",
            "equip_armor",
            "drop_item",
            "use_item",
            "release_item",
            "use_on_block",
            "interact_block",
            "interact_entity",
            "mount",
            "dismount",
            "furnace_put",
            "furnace_take",
            "inspect_entities",
            "inspect_block",
            "inspect_entity",
            "container_put",
            "container_take")
        .contains(name);
  }

  private ServerLevel level() {
    return (ServerLevel) npc.level();
  }

  private JsonObject error(String text) {
    return object("ok", false, "error", text);
  }

  CompletionStage<JsonObject> execute(String name, JsonObject args) {
    try {
      return perform(name, args);
    } catch (RuntimeException e) {
      return CompletableFuture.completedFuture(
          error(e.getMessage() == null ? "Interaction rejected" : e.getMessage()));
    }
  }

  private CompletionStage<JsonObject> immediate(JsonObject result) {
    return CompletableFuture.completedFuture(result);
  }

  private BlockPos pos(JsonObject a) {
    return BlockPos.containing(
        a.get("x").getAsDouble(), a.get("y").getAsDouble(), a.get("z").getAsDouble());
  }

  private CompletionStage<JsonObject> perform(String name, JsonObject a) {
    if (name.equals("look_at")) {
      aim = new Vec3(a.get("x").getAsDouble(), a.get("y").getAsDouble(), a.get("z").getAsDouble());
      if (aim.distanceToSqr(npc.position()) > 128 * 128)
        return immediate(error("Aim point beyond 128 blocks"));
      aimUntil = level().getGameTime() + 1200;
      look();
      return immediate(object("ok", true));
    }
    if (name.equals("jump")) {
      npc.getJumpControl().jump();
      return immediate(object("ok", true));
    }
    if (name.equals("set_sneaking")) {
      npc.setShiftKeyDown(a.get("enabled").getAsInt() != 0);
      return immediate(object("ok", true));
    }
    if (name.equals("set_sprinting")) {
      npc.setSprinting(a.get("enabled").getAsInt() != 0);
      return immediate(object("ok", true));
    }
    if (name.equals("dismount")) {
      npc.stopRiding();
      return immediate(object("ok", !npc.isPassenger()));
    }
    if (name.equals("mount")) {
      var e = level().getEntity(a.get("entity_id").getAsInt());
      if (e == null
          || e instanceof net.minecraft.world.entity.player.Player
          || e instanceof CompanionEntity
          || npc.distanceToSqr(e) > 16)
        return immediate(error("Mount unavailable or out of reach"));
      return immediate(object("ok", npc.startRiding(e)));
    }
    if (name.equals("release_item")) {
      npc.releaseUsingItem();
      finish(object("ok", true));
      return immediate(object("ok", true));
    }
    if (name.equals("inspect_entities")) {
      int radius = a.get("radius").getAsInt();
      if (radius < 1 || radius > 64) return immediate(error("Radius 1..64 required"));
      var out = new JsonArray();
      for (var e :
          level().getEntities(npc, npc.getBoundingBox().inflate(radius)).stream()
              .sorted(
                  Comparator.comparingInt(
                          (Entity e) ->
                              net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                                      .getKey(e.getType())
                                      .toString()
                                      .equals("minecraft:eye_of_ender")
                                  ? 0
                                  : e instanceof Projectile
                                      ? 1
                                      : e instanceof net.minecraft.world.entity.monster.Enemy
                                              || NpcCombat.boss(e)
                                          ? 2
                                          : 3)
                      .thenComparingDouble(npc::distanceToSqr))
              .limit(64)
              .toList()) {
        var o =
            object(
                "entity_id",
                e.getId(),
                "type",
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getKey(e.getType())
                    .toString(),
                "position",
                new Gson().toJsonTree(List.of(e.getX(), e.getY(), e.getZ())),
                "velocity",
                new Gson()
                    .toJsonTree(
                        List.of(
                            e.getDeltaMovement().x,
                            e.getDeltaMovement().y,
                            e.getDeltaMovement().z)),
                "alive",
                e.isAlive(),
                "visible",
                npc.hasLineOfSight(e));
        if (e instanceof LivingEntity living) o.addProperty("health", living.getHealth());
        out.add(o);
        if (e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon)
          for (var part : dragon.getSubEntities())
            if (out.size() < 64)
              out.add(
                  object(
                      "entity_id",
                      part.getId(),
                      "type",
                      "minecraft:ender_dragon_part",
                      "name",
                      part.name,
                      "position",
                      new Gson().toJsonTree(List.of(part.getX(), part.getY(), part.getZ()))));
      }
      return immediate(object("ok", true, "entities", out));
    }
    if (name.equals("inspect_entity")) {
      var e = NpcCombat.entity(level(), a.get("entity_id").getAsInt());
      if (e == null || npc.distanceToSqr(e) > 128 * 128)
        return immediate(error("Entity not present in observation range"));
      var out =
          object(
              "ok",
              true,
              "entity_id",
              e.getId(),
              "alive",
              e.isAlive(),
              "visible",
              npc.hasLineOfSight(e),
              "position",
              new Gson().toJsonTree(List.of(e.getX(), e.getY(), e.getZ())),
              "velocity",
              new Gson()
                  .toJsonTree(
                      List.of(
                          e.getDeltaMovement().x, e.getDeltaMovement().y, e.getDeltaMovement().z)));
      if (e instanceof LivingEntity l) out.addProperty("health", l.getHealth());
      if (e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragonPart part)
        out.addProperty("health", part.getParent().getHealth());
      return immediate(out);
    }
    if (name.equals("inspect_block")) {
      var p = pos(a);
      if (npc.distanceToSqr(Vec3.atCenterOf(p)) > 32 * 32 || !level().hasChunkAt(p))
        return immediate(error("Block is outside loaded observation range"));
      var state = level().getBlockState(p);
      var out =
          object(
              "ok",
              true,
              "block",
              net.minecraft.core.registries.BuiltInRegistries.BLOCK
                  .getKey(state.getBlock())
                  .toString(),
              "state",
              state.toString(),
              "visible",
              skills.visible(p),
              "hardness",
              state.getDestroySpeed(level(), p),
              "requires_tool",
              state.requiresCorrectToolForDrops(),
              "x",
              p.getX(),
              "y",
              p.getY(),
              "z",
              p.getZ());
      var container = skills.container(p, 5);
      if (container != null) {
        var slots = new JsonArray();
        for (int i = 0; i < container.getContainerSize(); i++) {
          var stack = container.getItem(i);
          if (!stack.isEmpty())
            slots.add(
                object(
                    "slot", i, "item", NpcRecipes.id(stack.getItem()), "count", stack.getCount()));
        }
        out.add("items", slots);
        out.addProperty("slots", container.getContainerSize());
      }
      return immediate(out);
    }
    if (name.equals("container_put") || name.equals("container_take")) {
      var p = pos(a);
      return skills.animateContainer(
          p,
          4,
          () -> {
            var container = skills.container(p, 4);
            int index = a.get("container_slot").getAsInt(), n = a.get("count").getAsInt();
            if (container == null
                || index < 0
                || index >= container.getContainerSize()
                || n < 1
                || n > 64) return error("Invalid accessible container/slot/count");
            var lease = NpcWorkData.get(level()).furnaces.get(NpcSmelting.key(level(), p));
            if (lease != null && !lease.equals(npc.getUUID()))
              return error("Another NPC owns this batch");
            int moved;
            if (name.equals("container_take")) {
              var stack = container.getItem(index);
              int wanted = Math.min(n, stack.getCount());
              moved = wanted - npc.inventory.insert(stack.copyWithCount(wanted)).getCount();
              container.removeItem(index, moved);
            } else {
              var source = npc.inventory.getItem(bag(a));
              var destination = container.getItem(index);
              if (!container.canPlaceItem(index, source)
                  || !destination.isEmpty()
                      && !ItemStack.isSameItemSameComponents(source, destination))
                return error("Container rejected this item/slot");
              moved =
                  Math.max(
                      0,
                      Math.min(
                          n,
                          Math.min(
                              source.getCount(),
                              Math.min(source.getMaxStackSize(), container.getMaxStackSize(source))
                                  - destination.getCount())));
              if (destination.isEmpty()) container.setItem(index, source.copyWithCount(moved));
              else destination.grow(moved);
              source.shrink(moved);
            }
            container.setChanged();
            npc.inventory.setChanged();
            return object("ok", moved > 0, "moved", moved);
          });
    }
    if (name.equals("equip_armor")) {
      int slot = bag(a);
      var source = npc.inventory.getItem(slot);
      var equippable = source.get(DataComponents.EQUIPPABLE);
      if (equippable == null
          || equippable.slot() == EquipmentSlot.MAINHAND
          || equippable.slot() == EquipmentSlot.OFFHAND)
        return immediate(error("Item is not wearable armor/equipment"));
      var old = npc.getItemBySlot(equippable.slot());
      npc.setItemSlot(equippable.slot(), source);
      npc.inventory.setItem(slot, old);
      return immediate(object("ok", true, "equipment", equippable.slot().getName()));
    }
    if (name.equals("drop_item")) {
      int slot = bag(a), count = a.get("count").getAsInt();
      if (count < 1 || count > 64) return immediate(error("Count 1..64 required"));
      var stack = npc.inventory.removeItem(slot, count);
      if (stack.isEmpty()) return immediate(error("No carried item"));
      npc.spawnAtLocation(level(), stack);
      return immediate(object("ok", true, "moved", stack.getCount()));
    }
    if (name.equals("furnace_put") || name.equals("furnace_take")) {
      var p = pos(a);
      var furnace = skills.furnace(p);
      int index = a.get("furnace_slot").getAsInt(), n = a.get("count").getAsInt();
      if (furnace == null || index < 0 || index > 2 || n < 1 || n > 64)
        return immediate(error("Invalid accessible furnace/slot/count"));
      var lease = NpcWorkData.get(level()).furnaces.get(NpcSmelting.key(level(), p));
      if (lease != null && !lease.equals(npc.getUUID()))
        return immediate(error("Another NPC owns this furnace batch"));
      int moved;
      if (name.equals("furnace_take")) {
        var stack = furnace.getItem(index);
        int wanted = Math.min(n, stack.getCount());
        moved = wanted - npc.inventory.insert(stack.copyWithCount(wanted)).getCount();
        furnace.removeItem(index, moved);
      } else {
        var source = npc.inventory.getItem(bag(a));
        var destination = furnace.getItem(index);
        if (index == 2
            || !furnace.canPlaceItem(index, source)
            || !destination.isEmpty() && !ItemStack.isSameItemSameComponents(source, destination))
          return immediate(error("Cannot insert into this furnace slot"));
        moved =
            Math.min(
                n, Math.min(source.getCount(), source.getMaxStackSize() - destination.getCount()));
        if (destination.isEmpty()) furnace.setItem(index, source.copyWithCount(moved));
        else destination.grow(moved);
        source.shrink(moved);
      }
      furnace.setChanged();
      npc.inventory.setChanged();
      return immediate(object("ok", moved > 0, "moved", moved));
    }
    if (name.equals("use_item")) {
      int slot = a.get("slot").getAsInt(), ticks = a.get("ticks").getAsInt();
      if (slot < 0 || slot >= NpcInventory.SIZE || ticks < 0 || ticks > 1200)
        return immediate(error("Invalid slot/use duration"));
      if (slot < 36 && !npc.inventory.equip(slot, false))
        return immediate(error("Cannot equip item"));
      if (slot >= 36 && slot != NpcInventory.MAIN_HAND)
        return immediate(error("Use a bag slot or main hand"));
      var stack = npc.getMainHandItem();
      if (stack.isEmpty()) return immediate(error("No held item"));
      consuming = stack.has(DataComponents.CONSUMABLE);
      beforeCount = stack.getCount();
      beforeItem = stack.getItem();
      useTicks = ticks;
      until = level().getGameTime() + Math.max(1, ticks);
      if (consuming) {
        npc.startUsingItem(InteractionHand.MAIN_HAND);
        until = level().getGameTime() + 1200;
      } else if (ticks > 0) npc.startUsingItem(InteractionHand.MAIN_HAND);
      if (ticks > 0 || consuming) {
        pending = new CompletableFuture<>();
        return pending;
      }
      return immediate(
          withPlayer(
              fake -> {
                var result =
                    fake.gameMode.useItem(
                        fake, level(), fake.getMainHandItem(), InteractionHand.MAIN_HAND);
                return object("ok", result.consumesAction());
              }));
    }
    if (name.equals("use_on_block") || name.equals("interact_block")) {
      var p = pos(a);
      if (!skills.mayChange(p, 5)) return immediate(error("Block is protected or out of reach"));
      if (name.equals("use_on_block")) {
        int slot = a.get("slot").getAsInt();
        if (slot < 0 || slot > 41 || slot >= 36 && slot != 41)
          return immediate(error("Use a bag slot or main hand 41"));
        if (slot < 36 && !npc.inventory.equip(slot, false))
          return immediate(error("Cannot equip item"));
      }
      Direction face = Direction.byName(a.get("face").getAsString());
      if (face == null) return immediate(error("Unknown face"));
      var hit =
          new BlockHitResult(
              Vec3.atCenterOf(p)
                  .add(face.getStepX() * .5, face.getStepY() * .5, face.getStepZ() * .5),
              face,
              p,
              false);
      if (!skills.visibleHit(hit.getLocation(), p))
        return immediate(error("Requested block face is not visible"));
      var block = level().getBlockEntity(p);
      if (!npc.isShiftKeyDown()
          && (block instanceof net.minecraft.world.level.block.entity.ChestBlockEntity
              || block instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity
              || block instanceof net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity
              || block instanceof net.minecraft.world.level.block.entity.EnderChestBlockEntity))
        return skills.interactContainer(p);
      return immediate(
          withPlayer(
              fake -> {
                var result =
                    fake.gameMode.useItemOn(
                        fake, level(), fake.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
                if (!result.consumesAction()
                    && fake.getMainHandItem().getItem() instanceof BucketItem) {
                  // Buckets implement use(), not useOn(). Aim at the requested face and invoke
                  // vanilla's real raycast/use path, preserving permissions and fluid rules.
                  var d = hit.getLocation().subtract(fake.getEyePosition());
                  fake.setYRot((float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90));
                  fake.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
                  result =
                      fake.gameMode.useItem(
                          fake, level(), fake.getMainHandItem(), InteractionHand.MAIN_HAND);
                }
                return object("ok", result.consumesAction());
              }));
    }
    if (name.equals("interact_entity")) {
      var e = level().getEntity(a.get("entity_id").getAsInt());
      if (e == null
          || e instanceof net.minecraft.world.entity.player.Player
          || e instanceof CompanionEntity
          || npc.distanceToSqr(e) > 16
          || !npc.hasLineOfSight(e))
        return immediate(error("Entity unavailable or outside interaction reach"));
      return immediate(
          withPlayer(
              fake ->
                  object(
                      "ok",
                      fake.interactOn(
                              e, InteractionHand.MAIN_HAND, e.position().subtract(fake.position()))
                          .consumesAction())));
    }
    return immediate(error("Unknown interaction"));
  }

  private int bag(JsonObject a) {
    int slot = a.get("slot").getAsInt();
    if (slot < 0 || slot >= 36) throw new IllegalArgumentException("Bag slot 0..35 required");
    return slot;
  }

  void tick() {
    if (aim != null && level().getGameTime() <= aimUntil) look();
    if (pending == null) return;
    if (consuming) {
      if (!npc.isUsingItem()) {
        finish(
            object(
                "ok",
                npc.getMainHandItem().getCount() < beforeCount
                    || !npc.getMainHandItem().is(beforeItem),
                "consumed",
                true));
        return;
      }
    } else if (level().getGameTime() >= until) {
      try {
        finish(
            withPlayer(
                fake -> {
                  var stack = fake.getMainHandItem();
                  fake.startUsingItem(InteractionHand.MAIN_HAND);
                  stack
                      .getItem()
                      .onUseTick(
                          level(), fake, stack, Math.max(0, stack.getUseDuration(fake) - useTicks));
                  boolean released =
                      stack
                          .getItem()
                          .releaseUsing(
                              stack,
                              level(),
                              fake,
                              Math.max(0, stack.getUseDuration(fake) - useTicks));
                  fake.stopUsingItem();
                  npc.stopUsingItem();
                  return object("ok", released || stack.is(Items.SHIELD));
                }));
      } catch (RuntimeException e) {
        finish(error("Item release failed"));
      }
    }
    if (pending != null && level().getGameTime() > until) finish(error("Item use timed out"));
  }

  private void look() {
    if (!npc.getNavigation().isDone()) return;
    var d = aim.subtract(npc.getEyePosition());
    // While walking, MoveControl owns body yaw. Forcing a stale aiming yaw here rotates the
    // movement vector away from the navigation path (notably when returning to a guard post).
    if (npc.getNavigation().isDone())
      npc.setYRot((float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90));
    npc.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
    npc.setYHeadRot(npc.getYRot());
    npc.getLookControl().setLookAt(aim.x, aim.y, aim.z);
  }

  void clearAim() {
    aim = null;
  }

  private JsonObject withPlayer(Function<FakePlayer, JsonObject> operation) {
    var owner = level().getServer().getPlayerList().getPlayer(npc.ownerId());
    if (owner == null) throw new IllegalArgumentException("Owner offline");
    var fake = FakePlayerFactory.get(level(), owner.getGameProfile());
    var inventory = fake.getInventory();
    var saved = new ArrayList<ItemStack>();
    for (int i = 0; i < inventory.getContainerSize(); i++) saved.add(inventory.getItem(i).copy());
    int selected = inventory.getSelectedSlot();
    var oldPos = fake.position();
    float yaw = fake.getYRot(), pitch = fake.getXRot();
    var oldMode = fake.gameMode.getGameModeForPlayer();
    boolean shift = fake.isShiftKeyDown();
    boolean grounded = fake.onGround();
    int free = -1;
    for (int i = 1; i < 36; i++)
      if (npc.inventory.getItem(i).isEmpty()) {
        free = i;
        break;
      }
    if (free < 0)
      throw new IllegalArgumentException("One empty bag slot is needed for item interaction");
    var before = new HashSet<UUID>();
    for (var e : level().getEntitiesOfClass(Projectile.class, npc.getBoundingBox().inflate(16)))
      before.add(e.getUUID());
    try {
      fake.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
      fake.setPos(npc.position());
      fake.setYRot(npc.getYRot());
      fake.setXRot(npc.getXRot());
      fake.setShiftKeyDown(npc.isShiftKeyDown());
      fake.setOnGround(npc.onGround());
      for (var equipment :
          List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET))
        fake.setItemSlot(equipment, npc.getItemBySlot(equipment).copy());
      for (int i = 0; i < 36; i++) inventory.setItem(i, npc.inventory.getItem(i).copy());
      inventory.setItem(free, npc.inventory.getItem(0).copy());
      inventory.setItem(0, npc.getMainHandItem().copy());
      inventory.setSelectedSlot(0);
      fake.setItemInHand(InteractionHand.OFF_HAND, npc.getOffhandItem().copy());
      return operation.apply(fake);
    } finally {
      for (var e : level().getEntitiesOfClass(Projectile.class, npc.getBoundingBox().inflate(16)))
        if (!before.contains(e.getUUID()) && e.getOwner() == fake) e.setOwner(npc);
      npc.setItemInHand(InteractionHand.MAIN_HAND, inventory.getItem(0).copy());
      npc.setItemInHand(InteractionHand.OFF_HAND, fake.getOffhandItem().copy());
      npc.inventory.setItem(0, inventory.getItem(free).copy());
      for (int i = 1; i < 36; i++)
        if (i != free) npc.inventory.setItem(i, inventory.getItem(i).copy());
      for (int i = 0; i < saved.size(); i++) inventory.setItem(i, saved.get(i));
      inventory.setSelectedSlot(selected);
      fake.setPos(oldPos);
      fake.setYRot(yaw);
      fake.setXRot(pitch);
      fake.setShiftKeyDown(shift);
      fake.gameMode.changeGameModeForPlayer(oldMode);
      fake.setOnGround(grounded);
    }
  }

  private void finish(JsonObject outcome) {
    var f = pending;
    pending = null;
    if (f != null) f.complete(outcome);
  }

  public void close() {
    npc.stopUsingItem();
    finish(error("Item interaction stopped"));
    aim = null;
  }
}

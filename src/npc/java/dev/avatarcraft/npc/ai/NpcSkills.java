package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.*;
import dev.avatarcraft.npc.CompanionEntity;
import dev.avatarcraft.npc.CompanionMenu;
import dev.avatarcraft.npc.InventoryTransfer;
import dev.avatarcraft.npc.NpcInventory;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.util.*;

/**
 * Actual server-thread game operations. No teleports, /give, operator commands, or invented drops.
 */
public final class NpcSkills {
  private final CompanionEntity npc;
  private final BiConsumer<String, Integer> speak;
  private Action action;
  private final NpcFollowing following;
  private volatile long generation;
  private long lastAttack = Long.MIN_VALUE / 2;

  private static final class Action {
    final String name;
    final JsonObject args;
    final long deadline;
    final CompletableFuture<JsonObject> result = new CompletableFuture<>();
    final net.minecraft.world.level.block.state.BlockState original;
    int miningTicks;
    float progress;
    ItemStack tool = ItemStack.EMPTY;

    Action(
        String name,
        JsonObject args,
        long deadline,
        net.minecraft.world.level.block.state.BlockState original) {
      this.name = name;
      this.args = args;
      this.deadline = deadline;
      this.original = original;
    }
  }

  public NpcSkills(CompanionEntity npc, BiConsumer<String, Integer> speak) {
    this.npc = npc;
    this.speak = speak;
    following = new NpcFollowing(npc);
  }

  public CompletionStage<JsonObject> execute(String name, JsonObject args) {
    long expected = generation;
    var result = new CompletableFuture<JsonObject>();
    if (!(npc.level() instanceof ServerLevel level))
      return CompletableFuture.completedFuture(error("Server action required"));
    level
        .getServer()
        .execute(
            () -> {
              try {
                if (expected != generation) {
                  result.complete(error("Action was cancelled"));
                  return;
                }
                NpcTools.validate(name, args, npc.personality().allowedSkills);
                if (!npc.isAlive() || owner() == null) {
                  result.complete(error("Owner is offline or companion is unavailable"));
                  return;
                }
                perform(name, args)
                    .whenComplete(
                        (value, failure) -> {
                          if (failure == null) result.complete(value);
                          else result.complete(error("Game action failed"));
                        });
              } catch (RuntimeException e) {
                result.complete(error("Invalid or disallowed game action"));
              }
            });
    return result;
  }

  private ServerLevel level() {
    return (ServerLevel) npc.level();
  }

  private ServerPlayer owner() {
    return npc.ownerId() == null
        ? null
        : level().getServer().getPlayerList().getPlayer(npc.ownerId());
  }

  private BlockPos pos(JsonObject args) {
    return BlockPos.containing(
        args.get("x").getAsDouble(), args.get("y").getAsDouble(), args.get("z").getAsDouble());
  }

  private boolean permitted(BlockPos pos, double distance) {
    var owner = owner();
    var level = level();
    return owner != null
        && level.hasChunkAt(pos)
        && level.isInWorldBounds(pos)
        && level.getWorldBorder().isWithinBounds(pos)
        && npc.distanceToSqr(Vec3.atCenterOf(pos)) <= distance * distance;
  }

  private boolean mayChange(BlockPos pos, double distance) {
    return permitted(pos, distance) && owner().mayBuild() && level().mayInteract(owner(), pos);
  }

  private boolean visible(BlockPos target) {
    var hit =
        level()
            .clip(
                new ClipContext(
                    npc.getEyePosition(),
                    Vec3.atCenterOf(target),
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    npc));
    return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
  }

  private static JsonObject error(String text) {
    return object("ok", false, "error", text);
  }

  private static CompletionStage<JsonObject> immediate(JsonObject value) {
    return CompletableFuture.completedFuture(value);
  }

  private CompletionStage<JsonObject> perform(String name, JsonObject args) {
    if (name.equals("observe")) return immediate(observe());
    if (name.equals("find_blocks")) return immediate(findBlocks(args));
    if (name.equals("find_chests")) return immediate(findChests(args));
    if (name.equals("say")) {
      String text = args.get("text").getAsString().strip();
      if (text.isEmpty() || text.length() > 300)
        return immediate(error("Speech must contain 1..300 characters"));
      speak.accept(text, args.get("target_id").getAsInt());
      return immediate(object("ok", true));
    }
    if (name.equals("stop")) {
      cancel("Stopped");
      return immediate(object("ok", true));
    }
    if (action != null) return immediate(error("Another game action is still in progress"));
    if (name.equals("deposit")) return immediate(deposit(args));
    if (name.equals("withdraw")) return immediate(withdraw(args));
    if (name.equals("give_item")) return immediate(giveItem(args));
    if (name.equals("craft")) return immediate(craft(args));
    if (name.equals("place")) return immediate(place(args));
    if (name.equals("eat")) return immediate(eat(args));
    if (name.equals("equip")) {
      String hand = args.get("hand").getAsString();
      if (!hand.equals("main") && !hand.equals("off"))
        return immediate(error("Hand must be main or off"));
      boolean equipped = npc.inventory.equip(args.get("slot").getAsInt(), hand.equals("off"));
      return immediate(
          equipped
              ? object("ok", true, "hand", hand, "inventory", observe().get("inventory"))
              : error("Invalid bag slot or an item is still being used"));
    }
    if (Set.of("move_to", "dig").contains(name) && !permitted(pos(args), 64))
      return immediate(error("Target is outside the permitted loaded area"));
    if (name.equals("wait") || name.equals("follow")) {
      int seconds = args.get("seconds").getAsInt();
      if (seconds < 1 || seconds > 60) return immediate(error("Duration must be 1..60 seconds"));
    }
    long duration =
        name.equals("wait") || name.equals("follow") ? args.get("seconds").getAsInt() * 20L : 1200;
    action =
        new Action(
            name,
            args.deepCopy(),
            level().getGameTime() + duration,
            name.equals("dig") ? level().getBlockState(pos(args)) : null);
    following.stop();
    npc.getNavigation().stop();
    return action.result;
  }

  public void tick() {
    var current = action;
    if (current == null) {
      var owner = owner();
      boolean menuOpen =
          owner != null && owner.containerMenu instanceof CompanionMenu menu && menu.npc == npc;
      if (npc.followsOwner() && npc.instruction().isBlank() && !menuOpen) following.tick(owner);
      else following.stop();
      return;
    }
    if (!npc.isAlive() || owner() == null) {
      cancel("Owner is offline or companion is unavailable");
      return;
    }
    try {
      long now = level().getGameTime();
      if (current.name.equals("wait")) {
        if (now >= current.deadline) finish(object("ok", true));
        return;
      }
      if (now >= current.deadline) {
        finish(
            current.name.equals("follow")
                ? object("ok", true)
                : error("Target was not reached before timeout"));
        return;
      }
      if (current.name.equals("follow") || current.name.equals("attack")) {
        entityAction(current, now);
        return;
      }
      var target = pos(current.args);
      if (!permitted(target, 64)) {
        finish(error("Target became unavailable or protected"));
        return;
      }
      double distance =
          npc.distanceToSqr(
              current.name.equals("dig") ? Vec3.atCenterOf(target) : Vec3.atBottomCenterOf(target));
      double reach = current.name.equals("dig") ? 16 : .36;
      if (distance > reach) {
        if (npc.tickCount % 20 == 0 || npc.getNavigation().isDone()) {
          var navigation = npc.getNavigation();
          boolean path =
              current.name.equals("move_to")
                  ? navigation.moveTo(navigation.createPath(target, 0), 1.6)
                  : navigation.moveTo(target.getX() + .5, target.getY(), target.getZ() + .5, 1.6);
          if (!path)
            finish(
                object(
                    "ok",
                    false,
                    "error",
                    "No reachable path to target",
                    "position",
                    vector(npc.position()),
                    "target",
                    vector(Vec3.atBottomCenterOf(target))));
        }
        return;
      }
      npc.getNavigation().stop();
      if (current.name.equals("move_to")) {
        finish(object("ok", true, "position", vector(npc.position())));
        return;
      }
      if (current.name.equals("dig")) mine(current, target);
    } catch (RuntimeException e) {
      finish(error("Game action became invalid"));
    }
  }

  private void entityAction(Action current, long now) {
    var target = level().getEntity(current.args.get("entity_id").getAsInt());
    if (target == null || !target.isAlive() || npc.distanceToSqr(target) > 64 * 64) {
      finish(error("Target entity is not nearby"));
      return;
    }
    if (current.name.equals("attack")
        && (!(target instanceof Enemy) || target instanceof CompanionEntity)) {
      finish(error("Only hostile entities can be attacked"));
      return;
    }
    if (current.name.equals("follow")) {
      following.tick(target);
      return;
    }
    npc.getLookControl().setLookAt(target);
    double reach = current.name.equals("attack") ? 6.25 : 9;
    if (npc.distanceToSqr(target) > reach) {
      if (npc.tickCount % 20 == 0 || npc.getNavigation().isDone())
        if (!npc.getNavigation().moveTo(target, 1.6)) finish(error("No path to target entity"));
      return;
    }
    npc.getNavigation().stop();
    int interval =
        Math.max(
            1, (int) Math.ceil(20 / Math.max(.1, npc.getAttributeValue(Attributes.ATTACK_SPEED))));
    if (now - lastAttack < interval) return;
    lastAttack = now;
    if (!npc.hasLineOfSight(target)) {
      finish(error("Target is not visible"));
      return;
    }
    npc.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
    boolean hit = npc.doHurtTarget(level(), target);
    if (hit) npc.exhaust(.1f);
    finish(object("ok", hit, "target_alive", target.isAlive()));
  }

  private <T> T asPlayer(int slot, Function<FakePlayer, T> operation) {
    var fake = FakePlayerFactory.get(level(), owner().getGameProfile());
    var oldHand = fake.getMainHandItem();
    var oldMode = fake.gameMode.getGameModeForPlayer();
    var oldPosition = fake.position();
    boolean oldShift = fake.isShiftKeyDown();
    float oldYaw = fake.getYRot(), oldPitch = fake.getXRot();
    fake.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
    fake.setPos(npc.position());
    fake.setYRot(npc.getYRot());
    fake.setXRot(npc.getXRot());
    fake.setItemInHand(InteractionHand.MAIN_HAND, npc.inventory.getItem(slot).copy());
    fake.setShiftKeyDown(true);
    try {
      return operation.apply(fake);
    } finally {
      npc.inventory.setItem(slot, fake.getMainHandItem().copy());
      fake.setItemInHand(InteractionHand.MAIN_HAND, oldHand);
      fake.setPos(oldPosition);
      fake.setShiftKeyDown(oldShift);
      fake.setYRot(oldYaw);
      fake.setXRot(oldPitch);
      fake.gameMode.changeGameModeForPlayer(oldMode);
    }
  }

  private void mine(Action current, BlockPos target) {
    npc.setMining(true);
    var state = level().getBlockState(target);
    if (state.isAir() || !state.equals(current.original)) {
      finish(error("Mining target changed"));
      return;
    }
    if (state.getDestroySpeed(level(), target) < 0 || !mayChange(target, 4) || !visible(target)) {
      finish(error("Block cannot be mined or is not visible"));
      return;
    }
    npc.getLookControl().setLookAt(target.getX() + .5, target.getY() + .5, target.getZ() + .5);
    float progress =
        asPlayer(NpcInventory.MAIN_HAND, fake -> state.getDestroyProgress(fake, level(), target));
    if (!Float.isFinite(progress) || progress <= 0) {
      finish(error("No mining progress with current tool"));
      return;
    }
    current.miningTicks++;
    if (!ItemStack.isSameItemSameComponents(current.tool, npc.getMainHandItem())) {
      current.progress = 0;
      current.tool = npc.getMainHandItem().copy();
    }
    current.progress += progress;
    if (npc.tickCount % 5 == 0) npc.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
    level().destroyBlockProgress(npc.getId(), target, Math.min(9, (int) (current.progress * 10)));
    if (current.progress >= 1) {
      boolean changed =
          asPlayer(NpcInventory.MAIN_HAND, fake -> fake.gameMode.destroyBlock(target));
      if (changed) npc.exhaust(.005f);
      finish(object("ok", changed && !level().getBlockState(target).equals(state)));
    }
  }

  private JsonObject place(JsonObject args) {
    int slot = args.get("slot").getAsInt();
    var target = pos(args);
    if (slot < 0 || slot >= 36 || !mayChange(target, 4))
      return error("Placement target or material slot is not permitted");
    var carried = npc.inventory.getItem(slot);
    if (!(carried.getItem() instanceof BlockItem blockItem)
        || blockItem.getBlock() instanceof GameMasterBlock
        || !level().getBlockState(target).canBeReplaced())
      return error("No placeable block or target is occupied");
    for (var face : Direction.values()) {
      var support = target.relative(face.getOpposite());
      if (!mayChange(support, 5)
          || !visible(support)
          || level().getBlockState(support).getCollisionShape(level(), support).isEmpty()) continue;
      var hit =
          new BlockHitResult(
              Vec3.atCenterOf(support)
                  .add(face.getStepX() * .5, face.getStepY() * .5, face.getStepZ() * .5),
              face,
              support,
              false);
      var before = level().getBlockState(target);
      return asPlayer(
          slot,
          fake -> {
            var context =
                new BlockPlaceContext(fake, InteractionHand.MAIN_HAND, fake.getMainHandItem(), hit);
            if (!context.getClickedPos().equals(target) || !context.canPlace())
              return error("Block cannot be placed here");
            fake.gameMode.useItemOn(
                fake, level(), fake.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
            return object("ok", !level().getBlockState(target).equals(before));
          });
    }
    return error("No adjacent supporting block");
  }

  private JsonObject deposit(JsonObject args) {
    int slot = args.get("slot").getAsInt(), count = args.get("count").getAsInt();
    var target = pos(args);
    if (slot < 0
        || slot >= 36
        || count < 1
        || count > 64
        || !mayChange(target, 4)
        || !visible(target)) return error("Invalid container transfer");
    var container = chest(target, 4);
    if (container == null) return error("Chest is blocked, locked, protected or unavailable");
    var source = npc.inventory.getItem(slot);
    int moved = 0;
    for (int i = 0; i < container.getContainerSize() && moved < count && !source.isEmpty(); i++) {
      if (!container.canPlaceItem(i, source)) continue;
      var destination = container.getItem(i);
      if (!destination.isEmpty() && !ItemStack.isSameItemSameComponents(source, destination))
        continue;
      int capacity =
          Math.min(source.getMaxStackSize(), container.getMaxStackSize(source))
              - destination.getCount();
      int amount = Math.min(Math.min(capacity, source.getCount()), count - moved);
      if (amount <= 0) continue;
      if (destination.isEmpty()) container.setItem(i, source.copyWithCount(amount));
      else destination.grow(amount);
      source.shrink(amount);
      moved += amount;
      container.setChanged();
    }
    return object("ok", moved > 0, "moved", moved);
  }

  private Container chest(BlockPos target, double reach) {
    if (!mayChange(target, reach) || !visible(target)) return null;
    var state = level().getBlockState(target);
    if (!(state.getBlock() instanceof ChestBlock block)
        || !(level().getBlockEntity(target)
            instanceof net.minecraft.world.level.block.entity.ChestBlockEntity entity)
        || !entity.canOpen(owner())) return null;
    if (state.getValue(ChestBlock.TYPE)
        != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
      var other = target.relative(ChestBlock.getConnectedDirection(state));
      if (!mayChange(other, reach + 1)
          || !(level().getBlockEntity(other)
              instanceof net.minecraft.world.level.block.entity.ChestBlockEntity second)
          || !second.canOpen(owner())) return null;
    }
    return ChestBlock.getContainer(block, state, level(), target, false);
  }

  private JsonObject withdraw(JsonObject args) {
    int slot = args.get("slot").getAsInt(), count = args.get("count").getAsInt();
    if (count < 1 || count > 64) return error("Count must be 1..64");
    var container = chest(pos(args), 4);
    if (container == null || slot < 0 || slot >= container.getContainerSize())
      return error("Chest or chest slot is unavailable");
    var source = container.getItem(slot);
    int requested = Math.min(source.getCount(), count);
    var remaining = npc.inventory.insert(source.copyWithCount(requested));
    int moved = requested - remaining.getCount();
    if (moved > 0) {
      container.removeItem(slot, moved);
      container.setChanged();
    }
    return object("ok", moved > 0, "moved", moved);
  }

  private JsonObject giveItem(JsonObject args) {
    int slot = args.get("slot").getAsInt(), count = args.get("count").getAsInt();
    var target = level().getEntity(args.get("entity_id").getAsInt());
    if (slot < 0
        || slot >= NpcInventory.BAG_SIZE
        || count < 1
        || count > 64
        || target == null
        || target == npc
        || !target.isAlive()
        || target.isSpectator()
        || npc.distanceToSqr(target) > 16
        || !npc.hasLineOfSight(target))
      return error(
          "Recipient must be another nearby visible NPC or player; invalid source or count");
    Container destination =
        target instanceof CompanionEntity companion
            ? companion.inventory
            : target instanceof net.minecraft.world.entity.player.Player player
                ? player.getInventory()
                : null;
    if (destination == null) return error("Only NPCs and players can receive items");
    var source = npc.inventory.getItem(slot);
    int requested = Math.min(source.getCount(), count);
    var remaining = InventoryTransfer.insert(destination, source.copyWithCount(requested), 0, 36);
    int moved = requested - remaining.getCount();
    if (moved > 0) {
      npc.inventory.removeItem(slot, moved);
      npc.inventory.setChanged();
    }
    return object("ok", moved > 0, "moved", moved, "recipient", target.getId());
  }

  private JsonObject findChests(JsonObject args) {
    int radius = args.get("radius").getAsInt();
    if (radius < 1 || radius > 16) return error("Radius must be 1..16");
    var found = new ArrayList<BlockPos>();
    for (var target :
        BlockPos.betweenClosed(
            npc.blockPosition().offset(-radius, -4, -radius),
            npc.blockPosition().offset(radius, 4, radius)))
      if (permitted(target, radius)
          && level().getBlockState(target).getBlock() instanceof ChestBlock)
        found.add(target.immutable());
    found.sort(Comparator.comparingDouble(p -> p.distSqr(npc.blockPosition())));
    var chests = new JsonArray();
    for (var target : found.stream().limit(24).toList()) {
      var container = chest(target, radius);
      if (container == null) continue;
      var items = new JsonArray();
      for (int i = 0; i < container.getContainerSize(); i++) {
        var item = container.getItem(i);
        if (!item.isEmpty())
          items.add(
              object(
                  "slot",
                  i,
                  "item",
                  BuiltInRegistries.ITEM.getKey(item.getItem()).toString(),
                  "count",
                  item.getCount()));
      }
      chests.add(
          object(
              "x",
              target.getX(),
              "y",
              target.getY(),
              "z",
              target.getZ(),
              "slots",
              container.getContainerSize(),
              "items",
              items));
    }
    return object("ok", true, "chests", chests);
  }

  private JsonObject eat(JsonObject args) {
    int slot = args.get("slot").getAsInt();
    if (slot < 0 || slot >= 36 || npc.isUsingItem())
      return error("Invalid food slot or already using an item");
    var food = npc.inventory.getItem(slot).get(net.minecraft.core.component.DataComponents.FOOD);
    if (food == null || npc.foodLevel() >= 20 && !food.canAlwaysEat())
      return error("Food is not edible or not hungry");
    if (!npc.inventory.equip(slot, false)) return error("Unable to equip food");
    npc.startUsingItem(InteractionHand.MAIN_HAND);
    return object("ok", true, "status", "eating_started");
  }

  private JsonObject craft(JsonObject args) {
    var id = Identifier.tryParse(args.get("recipe").getAsString());
    if (id == null) return error("Invalid recipe ID");
    var holder =
        level()
            .getServer()
            .getRecipeManager()
            .byKey(ResourceKey.create(Registries.RECIPE, id))
            .orElse(null);
    if (holder == null || !(holder.value() instanceof CraftingRecipe recipe))
      return error("Recipe is not a crafting recipe");
    var placement = recipe.placementInfo();
    if (placement.isImpossibleToPlace() || placement.ingredients().size() > 9)
      return error("Recipe requires manual special handling");
    if (placement.ingredients().isEmpty()) return error("Recipe has no consumable materials");
    int width =
        recipe instanceof ShapedRecipe shaped
            ? shaped.getWidth()
            : Math.min(placement.ingredients().size() <= 4 ? 2 : 3, placement.ingredients().size());
    int height =
        recipe instanceof ShapedRecipe shaped
            ? shaped.getHeight()
            : (placement.ingredients().size() + width - 1) / width;
    if ((width > 2 || height > 2 || placement.ingredients().size() > 4) && !hasCraftingTable())
      return error("A crafting table must be within four blocks");
    int[] counts = new int[36];
    for (int i = 0; i < 36; i++) counts[i] = npc.inventory.getItem(i).getCount();
    var candidates = new ArrayList<int[]>();
    for (var ingredient : placement.ingredients()) {
      var slots = new ArrayList<Integer>();
      for (int i = 0; i < 36; i++) if (ingredient.test(npc.inventory.getItem(i))) slots.add(i);
      candidates.add(slots.stream().mapToInt(Integer::intValue).toArray());
    }
    var selected = CraftingAssignment.find(candidates, counts).orElse(null);
    if (selected == null) return error("Missing crafting materials");
    var grid = new ArrayList<ItemStack>(Collections.nCopies(width * height, ItemStack.EMPTY));
    for (int i = 0; i < placement.slotsToIngredientIndex().size(); i++) {
      int ingredient = placement.slotsToIngredientIndex().getInt(i);
      if (ingredient >= 0)
        grid.set(i, npc.inventory.getItem(selected[ingredient]).copyWithCount(1));
    }
    var input = CraftingInput.of(width, height, grid);
    if (!recipe.matches(input, level())) return error("Materials do not match recipe");
    var output = recipe.assemble(input);
    var remainders = recipe.getRemainingItems(input);
    if (output.isEmpty()) return error("Recipe produced no item");
    for (int material : selected) npc.inventory.removeItem(material, 1);
    giveOrDrop(output);
    for (var remainder : remainders) if (!remainder.isEmpty()) giveOrDrop(remainder);
    return object(
        "ok",
        true,
        "item",
        BuiltInRegistries.ITEM.getKey(output.getItem()).toString(),
        "count",
        output.getCount());
  }

  private void giveOrDrop(ItemStack stack) {
    var remainder = npc.inventory.insert(stack);
    if (!remainder.isEmpty()) npc.spawnAtLocation(level(), remainder);
  }

  private boolean hasCraftingTable() {
    for (var pos :
        BlockPos.betweenClosed(
            npc.blockPosition().offset(-4, -2, -4), npc.blockPosition().offset(4, 2, 4)))
      if (level().hasChunkAt(pos)
          && level().getBlockState(pos).is(Blocks.CRAFTING_TABLE)
          && permitted(pos, 5)) return true;
    return false;
  }

  public JsonObject observe() {
    var items = new JsonArray();
    for (int i = 0; i < NpcInventory.SIZE; i++) {
      var stack = npc.inventory.getItem(i);
      if (!stack.isEmpty())
        items.add(
            object(
                "slot",
                i,
                "item",
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                "count",
                stack.getCount(),
                "equipment",
                NpcInventory.equipment(i) == null ? "bag" : NpcInventory.equipment(i).getName()));
    }
    var entities = new JsonArray();
    for (var entity :
        level()
            .getEntities(
                npc, npc.getBoundingBox().inflate(16), e -> e.isAlive() && npc.hasLineOfSight(e))
            .stream()
            .limit(24)
            .toList())
      entities.add(
          object(
              "entity_id",
              entity.getId(),
              "name",
              entity.getName().getString(),
              "hostile",
              entity instanceof Enemy,
              "position",
              vector(entity.position())));
    return object(
        "ok",
        true,
        "health",
        npc.getHealth(),
        "food",
        npc.foodLevel(),
        "using_item",
        npc.isUsingItem(),
        "position",
        vector(npc.position()),
        "inventory",
        items,
        "main_hand_slot",
        NpcInventory.MAIN_HAND,
        "off_hand_slot",
        NpcInventory.OFF_HAND,
        "owner",
        owner() != null && owner().level() == level()
            ? object("entity_id", owner().getId(), "position", vector(owner().position()))
            : JsonNull.INSTANCE,
        "follow_owner",
        npc.followsOwner(),
        "nearby",
        entities,
        "action",
        action == null ? "idle" : action.name);
  }

  private JsonObject findBlocks(JsonObject args) {
    int radius = args.get("radius").getAsInt();
    var id = Identifier.tryParse(args.get("block").getAsString());
    if (id == null || radius < 1 || radius > 16 || !BuiltInRegistries.BLOCK.containsKey(id))
      return error("Invalid block search");
    var block = BuiltInRegistries.BLOCK.getValue(id);
    var found = new ArrayList<BlockPos>();
    for (var target :
        BlockPos.betweenClosed(
            npc.blockPosition().offset(-radius, -Math.min(radius, 8), -radius),
            npc.blockPosition().offset(radius, Math.min(radius, 8), radius)))
      if (level().hasChunkAt(target) && level().getBlockState(target).is(block))
        found.add(target.immutable());
    found.sort(Comparator.comparingDouble(p -> p.distSqr(npc.blockPosition())));
    var result = new JsonArray();
    found.stream().limit(24).forEach(p -> result.add(vector(Vec3.atCenterOf(p))));
    return object("ok", true, "blocks", result);
  }

  private static JsonArray vector(Vec3 position) {
    var array = new JsonArray();
    array.add(position.x);
    array.add(position.y);
    array.add(position.z);
    return array;
  }

  private void finish(JsonObject result) {
    var current = action;
    action = null;
    following.stop();
    npc.getNavigation().stop();
    npc.setMining(false);
    if (current != null) {
      if (current.name.equals("dig"))
        level().destroyBlockProgress(npc.getId(), pos(current.args), -1);
      current.result.complete(result);
    }
  }

  public void cancel(String reason) {
    generation++;
    finish(error(reason));
  }

  public boolean busy() {
    return action != null;
  }
}

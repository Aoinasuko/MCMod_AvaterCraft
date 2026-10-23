package dev.avatarcraft.npc;

import dev.avatarcraft.api.AvatarActor;
import dev.avatarcraft.npc.ai.NpcMind;
import dev.avatarcraft.npc.ai.NpcService;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.*;

public final class CompanionEntity extends PathfinderMob implements AvatarActor, MenuProvider {
  private static final EntityDataAccessor<String> AVATAR =
      SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.STRING);
  private static final EntityDataAccessor<String> PERSONALITY =
      SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.STRING);
  private static final EntityDataAccessor<String> OWNER =
      SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.STRING);
  private UUID owner;
  private static final EntityDataAccessor<String> INSTRUCTION =
      SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.STRING);
  private static final EntityDataAccessor<String> AI_STATUS =
      SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.STRING);
  private NpcMind mind;
  private static final EntityDataAccessor<Integer> FOOD =
      SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
  private final NpcVitals vitals = new NpcVitals();
  private static final EntityDataAccessor<Boolean> MINING =
      SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.BOOLEAN);
  private static final EntityDataAccessor<Boolean> FOLLOW_OWNER =
      SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.BOOLEAN);
  private Personality personality = Personality.preset("tortobu");
  public final NpcInventory inventory = new NpcInventory(this);

  public CompanionEntity(EntityType<? extends CompanionEntity> type, Level level) {
    super(type, level);
    setPersistenceRequired();
    for (var slot : EquipmentSlot.values()) setGuaranteedDrop(slot);
  }

  protected void registerGoals() {
    goalSelector.addGoal(0, new FloatGoal(this));
    goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8));
    goalSelector.addGoal(9, new RandomLookAroundGoal(this));
  }

  protected void defineSynchedData(SynchedEntityData.Builder builder) {
    super.defineSynchedData(builder);
    builder.define(AVATAR, "tortobu");
    builder.define(PERSONALITY, "");
    builder.define(OWNER, "");
    builder.define(INSTRUCTION, "");
    builder.define(AI_STATUS, "待機中");
    builder.define(FOOD, 20);
    builder.define(MINING, false);
    builder.define(FOLLOW_OWNER, true);
  }

  public void initialize(Player player, String preset) {
    owner = player.getUUID();
    entityData.set(OWNER, owner.toString());
    setPersonality(Personality.preset(preset));
  }

  public boolean ownedBy(Player player) {
    return entityData.get(OWNER).equals(player.getUUID().toString());
  }

  public UUID ownerId() {
    return owner;
  }

  public String avatarId() {
    return entityData.get(AVATAR);
  }

  public Personality personality() {
    return Personality.parse(personality.json());
  }

  public String personalityJson() {
    return entityData.get(PERSONALITY);
  }

  public void setPersonality(Personality value) {
    value = Personality.parse(value.json());
    personality = value;
    entityData.set(PERSONALITY, value.json());
    entityData.set(AVATAR, value.avatar);
    setCustomName(Component.literal(value.name));
    setCustomNameVisible(true);
    if (mind != null) mind.instructionChanged(instruction());
  }

  public NpcMind mind() {
    if (!(level() instanceof ServerLevel server)) throw new IllegalStateException("Server only");
    if (mind == null) mind = new NpcMind(this, NpcService.get(server.getServer()));
    return mind;
  }

  public String instruction() {
    return entityData.get(INSTRUCTION);
  }

  public String aiStatus() {
    return entityData.get(AI_STATUS);
  }

  public int foodLevel() {
    return entityData.get(FOOD);
  }

  public boolean avatarMining() {
    return entityData.get(MINING);
  }

  public boolean followsOwner() {
    return entityData.get(FOLLOW_OWNER);
  }

  public void setFollowsOwner(boolean follow) {
    entityData.set(FOLLOW_OWNER, follow);
    getNavigation().stop();
    setSprinting(false);
  }

  public void setMining(boolean value) {
    entityData.set(MINING, value);
  }

  public void exhaust(float amount) {
    if (!level().isClientSide()) vitals.exhaust(amount);
  }

  public void setAiStatus(String value) {
    entityData.set(AI_STATUS, value);
  }

  public void setInstruction(String value) {
    if (value == null || value.length() > 2048)
      throw new IllegalArgumentException("Instruction exceeds 2048 characters");
    entityData.set(INSTRUCTION, value.strip());
    mind().instructionChanged(instruction());
  }

  public void completeInstruction() {
    entityData.set(INSTRUCTION, "");
  }

  @Override
  public void remove(RemovalReason reason) {
    if (mind != null) {
      mind.close();
      mind = null;
    }
    super.remove(reason);
  }

  protected InteractionResult mobInteract(Player player, InteractionHand hand) {
    if (!ownedBy(player)) {
      if (!level().isClientSide())
        player.sendOverlayMessage(Component.translatable("message.avatarcraftnpc.not_owner"));
      return InteractionResult.SUCCESS;
    }
    if (player instanceof ServerPlayer serverPlayer)
      serverPlayer.openMenu(this, buffer -> buffer.writeVarInt(getId()));
    return InteractionResult.SUCCESS;
  }

  public Component getDisplayName() {
    return getName();
  }

  public AbstractContainerMenu createMenu(
      int id, net.minecraft.world.entity.player.Inventory playerInventory, Player player) {
    return new CompanionMenu(id, playerInventory, this);
  }

  public void tick() {
    super.tick();
    if (level() instanceof ServerLevel server && isAlive()) {
      var effect =
          vitals.tick(
              getHealth(),
              getMaxHealth(),
              server.getDifficulty().getId(),
              server
                  .getGameRules()
                  .get(net.minecraft.world.level.gamerules.GameRules.NATURAL_HEALTH_REGENERATION));
      if (effect.healing() > 0) heal(effect.healing());
      if (effect.starvation()) hurtServer(server, damageSources().starve(), 1);
      entityData.set(FOOD, vitals.food());
      if (isInWater() && !isPassenger()) vitals.exhaust((float) getDeltaMovement().length() * .01f);
      mind().tick();
      if (tickCount % 10 == 0)
        for (var item :
            server.getEntitiesOfClass(
                ItemEntity.class,
                getBoundingBox().inflate(1),
                e -> e.isAlive() && !e.hasPickUpDelay())) {
          var original = item.getItem();
          var remaining = inventory.insert(original);
          int collected = original.getCount() - remaining.getCount();
          if (collected > 0) {
            take(item, collected);
            if (remaining.isEmpty()) item.discard();
            else item.setItem(remaining);
          }
        }
    }
  }

  protected void addAdditionalSaveData(ValueOutput output) {
    super.addAdditionalSaveData(output);
    if (owner != null) output.putString("Owner", owner.toString());
    output.putString("Personality", personality.json());
    output.putString("Instruction", instruction());
    output.putBoolean("FollowOwner", followsOwner());
    output.putInt("foodLevel", vitals.food());
    output.putFloat("foodSaturationLevel", vitals.saturation());
    output.putFloat("foodExhaustionLevel", vitals.exhaustion());
    output.putInt("foodTickTimer", vitals.timer());
    inventory.save(output.child("CompanionInventory"));
  }

  protected void readAdditionalSaveData(ValueInput input) {
    super.readAdditionalSaveData(input);
    try {
      owner = UUID.fromString(input.getStringOr("Owner", ""));
    } catch (IllegalArgumentException e) {
      owner = null;
    }
    entityData.set(OWNER, owner == null ? "" : owner.toString());
    try {
      setPersonality(
          Personality.parse(
              input.getStringOr("Personality", Personality.preset("tortobu").json())));
    } catch (RuntimeException e) {
      setPersonality(Personality.preset("tortobu"));
    }
    input.child("CompanionInventory").ifPresent(inventory::load);
    for (var slot : EquipmentSlot.values()) setGuaranteedDrop(slot);
    String instruction = input.getStringOr("Instruction", "");
    entityData.set(INSTRUCTION, instruction.length() <= 2048 ? instruction : "");
    entityData.set(FOLLOW_OWNER, input.getBooleanOr("FollowOwner", true));
    vitals.restore(
        input.getIntOr("foodLevel", 20),
        input.getFloatOr("foodSaturationLevel", 5),
        input.getFloatOr("foodExhaustionLevel", 0),
        input.getIntOr("foodTickTimer", 0));
    entityData.set(FOOD, vitals.food());
  }

  @Override
  public void jumpFromGround() {
    super.jumpFromGround();
    exhaust(isSprinting() ? .2f : .05f);
  }

  @Override
  protected void hurtArmor(DamageSource source, float amount) {
    doHurtEquipment(
        source,
        amount,
        EquipmentSlot.FEET,
        EquipmentSlot.LEGS,
        EquipmentSlot.CHEST,
        EquipmentSlot.HEAD);
  }

  @Override
  protected void hurtHelmet(DamageSource source, float amount) {
    doHurtEquipment(source, amount, EquipmentSlot.HEAD);
  }

  @Override
  public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
    if (source.scalesWithDifficulty())
      damage =
          switch (level.getDifficulty()) {
            case PEACEFUL -> 0;
            case EASY -> Math.min(damage / 2 + 1, damage);
            case HARD -> damage * 1.5f;
            default -> damage;
          };
    float before = getHealth();
    boolean hurt = damage > 0 && super.hurtServer(level, source, damage);
    if (getHealth() < before) exhaust(source.getFoodExhaustion());
    return hurt;
  }

  @Override
  protected void completeUsingItem() {
    var food = getUseItem().get(net.minecraft.core.component.DataComponents.FOOD);
    var before = getUseItem().copy();
    var hand = getUsedItemHand();
    boolean consumed =
        isUsingItem()
            && getUseItem().equals(getItemInHand(hand))
            && !before.isEmpty()
            && food != null;
    super.completeUsingItem();
    var after = getItemInHand(hand);
    if (!level().isClientSide()
        && consumed
        && !isUsingItem()
        && (after.getCount() < before.getCount()
            || !net.minecraft.world.item.ItemStack.isSameItemSameComponents(before, after))) {
      vitals.eat(food.nutrition(), food.saturation());
      entityData.set(FOOD, vitals.food());
    }
  }

  @Override
  public void handleExtraItemsCreatedOnUse(net.minecraft.world.item.ItemStack extra) {
    var remaining = inventory.insert(extra);
    if (!remaining.isEmpty() && level() instanceof ServerLevel server)
      spawnAtLocation(server, remaining);
  }

  protected void dropCustomDeathLoot(
      ServerLevel level, DamageSource source, boolean killedByPlayer) {
    super.dropCustomDeathLoot(level, source, killedByPlayer);
    if (mind != null) mind.close();
    for (int i = 0; i < NpcInventory.BAG_SIZE; i++) {
      var item = inventory.removeItemNoUpdate(i);
      if (!item.isEmpty()) spawnAtLocation(level, item);
    }
  }
}

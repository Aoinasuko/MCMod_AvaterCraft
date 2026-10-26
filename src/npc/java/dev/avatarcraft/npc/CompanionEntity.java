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

public final class CompanionEntity extends PathfinderMob
    implements AvatarActor, MenuProvider, ContainerUser {
  public final NpcContainerAnimation containerAnimation = new NpcContainerAnimation(this);

  @Override
  public boolean hasContainerOpen(
      net.minecraft.world.level.block.entity.ContainerOpenersCounter counter,
      net.minecraft.core.BlockPos pos) {
    return containerAnimation.hasOpen(pos);
  }

  @Override
  public double getContainerInteractionRange() {
    return 5;
  }

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
  private final NpcGateController gateController = new NpcGateController(this);
  private final NpcChunkLoading chunkLoading = new NpcChunkLoading();
  private String localTaskState = "";
  private String workSequenceState = "{}";

  public String workSequenceState() {
    return workSequenceState;
  }

  public void workSequenceState(String state) {
    if (state.length() > 16384) throw new IllegalArgumentException("Queue checkpoint too large");
    workSequenceState = state;
  }

  private String expeditionState = "{}";
  private String commandContext = "{}";
  private com.google.gson.JsonArray recentResults = new com.google.gson.JsonArray();
  public final dev.avatarcraft.npc.ai.NpcExecutionMemory executionMemory =
      new dev.avatarcraft.npc.ai.NpcExecutionMemory();
  private boolean recovering;
  private String respawnDimension = "", respawnConfigKey = "";
  private net.minecraft.world.phys.Vec3 respawnPosition = net.minecraft.world.phys.Vec3.ZERO;
  private float respawnYaw;

  public com.google.gson.JsonArray recentResults() {
    var latest = new com.google.gson.JsonArray();
    for (int i = recentResults.size() - 1; i >= 0; i--) latest.add(recentResults.get(i).deepCopy());
    return latest;
  }

  public void rememberResult(String tool, com.google.gson.JsonObject outcome) {
    if (!java.util.Set.of(
            "inspect_entities",
            "find_blocks",
            "find_chests",
            "inspect_entity",
            "inspect_block",
            "inspect_structures",
            "explore_structure",
            "team_status",
            "use_item",
            "travel_dimension",
            "place_workstation",
            "attack",
            "hunt",
            "acquire")
        .contains(tool)) return;
    recentResults.add(
        dev.avatarcraft.npc.ai.CodexBridge.object(
            "tool",
            tool,
            "tick",
            level().getGameTime(),
            "result",
            dev.avatarcraft.npc.ai.NpcObservations.bounded(outcome, 1500)));
    while (recentResults.size() > 8 || recentResults.toString().length() > 6000)
      recentResults.remove(0);
  }

  public String expeditionState() {
    return expeditionState;
  }

  public void setExpeditionState(String state) {
    if (state.length() > 65536) throw new IllegalArgumentException("Expedition state too large");
    expeditionState = state;
  }

  private static final EntityDataAccessor<Integer> FOOD =
      SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
  private final NpcVitals vitals = new NpcVitals();
  private static final EntityDataAccessor<Boolean> MINING =
      SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.BOOLEAN);
  private static final EntityDataAccessor<Boolean> FOLLOW_OWNER =
      SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.BOOLEAN);
  @Override
  public java.util.List<String> avatarExpressionKeys() {
    boolean eating = isUsingItem() && (getUseItem().getUseAnimation() == net.minecraft.world.item.ItemUseAnimation.EAT
        || getUseItem().getUseAnimation() == net.minecraft.world.item.ItemUseAnimation.DRINK);
    return CompanionExpressions.keys(eating, hurtTime > 0 || deathTime > 0);
  }

  private Personality personality = Personality.preset("tortobu");
  public final NpcInventory inventory = new NpcInventory(this);
  public final NpcLocationMemory locations = new NpcLocationMemory();
  public final dev.avatarcraft.npc.ai.NpcMission mission = new dev.avatarcraft.npc.ai.NpcMission(this);
  public final dev.avatarcraft.npc.ai.NpcWaterSafety waterSafety = new dev.avatarcraft.npc.ai.NpcWaterSafety(this);
  public final dev.avatarcraft.npc.ai.NpcReflexes reflexes = new dev.avatarcraft.npc.ai.NpcReflexes(this);
  /** Game time of the last planned attack, hunt, guard or defeat step; reflexes yield to it. */
  public long lastCombatAction = Long.MIN_VALUE / 2;

  public CompanionEntity(EntityType<? extends CompanionEntity> type, Level level) {
    super(type, level);
    moveControl = new dev.avatarcraft.npc.ai.NpcStepMoveControl(this);
    setPersistenceRequired();
    for (var slot : EquipmentSlot.values()) setGuaranteedDrop(slot);
  }

  protected void registerGoals() {
    goalSelector.addGoal(
        0,
        new FloatGoal(this) {
          // With the head in air the body is wading or already at the surface; a player only
          // swims up once the eyes go under, so shallow water lets it settle on the bottom.
          @Override
          public boolean canUse() {
            return super.canUse()
                && (isInLava() || isEyeInFluid(net.minecraft.tags.FluidTags.WATER));
          }

          @Override
          public boolean canContinueToUse() {
            return canUse();
          }
        });
    goalSelector.addGoal(1, new NpcDoorGoal(this));
    goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8));
    goalSelector.addGoal(9, new RandomLookAroundGoal(this));
  }

  @Override
  protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(Level level) {
    var navigation = new dev.avatarcraft.npc.ai.NpcGroundNavigation(this, level);
    navigation.getNodeEvaluator().setCanOpenDoors(true);
    navigation.getNodeEvaluator().setCanPassDoors(true);
    navigation.setCanFloat(true);
    return navigation;
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

  /** Isolated GameTest provider seam; unavailable in ordinary client/server runs. */
  public void gameTestMind(NpcMind replacement) {
    if (!Boolean.getBoolean("avatarcraft.npcGameTest"))
      throw new IllegalStateException("GameTest only");
    if (mind != null) mind.close();
    mind = java.util.Objects.requireNonNull(replacement);
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

  public NpcVitals vitals() {
    return vitals;
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
    var player = owner == null || level().isClientSide() ? null
        : level().getServer().getPlayerList().getPlayer(owner);
    setInstruction(value, dev.avatarcraft.npc.ai.NpcCommandContext.capture(this, player));
  }

  public com.google.gson.JsonObject commandContext() {
    return com.google.gson.JsonParser.parseString(commandContext).getAsJsonObject();
  }

  public void setInstruction(String value, com.google.gson.JsonObject context) {
    if (value == null || value.length() > 2048)
      throw new IllegalArgumentException("Instruction exceeds 2048 characters");
    String captured = context.toString();
    if (captured.length() > 4096) throw new IllegalArgumentException("Oversized command context");
    commandContext = value.isBlank() ? "{}" : captured;
    workSequenceState = "{}";
    entityData.set(INSTRUCTION, value.strip());
    mission.reset();
    mind().instructionChanged(instruction());
  }

  public void completeInstruction() {
    locations.clear();
    commandContext = "{}";
    entityData.set(INSTRUCTION, "");
  }

  @Override
  public void remove(RemovalReason reason) {
    if (!level().isClientSide()) containerAnimation.close();
    if (!level().isClientSide() && reason.shouldDestroy()) {
      NpcRoster.get(level().getServer()).remove(getUUID());
      dev.avatarcraft.npc.ai.NpcTeams.finished(this, "NPC was removed or died");
      dev.avatarcraft.npc.ai.NpcTeams.changed(this);
      var data = dev.avatarcraft.npc.ai.NpcWorkData.get((ServerLevel) level());
      if (data.furnaces.values().removeIf(getUUID()::equals)) data.setDirty();
    }
    // Persist tickets during a normal save/unload; release on death/discard and dimension transfer.
    if (!level().isClientSide()
        && (reason.shouldDestroy() || reason == RemovalReason.CHANGED_DIMENSION))
      chunkLoading.remove(this);
    if (mind != null) {
      localTaskState = mind.saveState();
      mind.close();
      mind = null;
    }
    super.remove(reason);
  }

  protected InteractionResult mobInteract(Player player, InteractionHand hand) {
    if (!ownedBy(player)) {
      if (!level().isClientSide())
        player.sendOverlayMessage(Component.translatable("message.bep_3davater_companion.not_owner"));
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
    if (!level().isClientSide() && isAlive()) {
      waterSafety.preTick();
      reflexes.preTick();
    }
    if (!level().isClientSide()) gateController.tick();
    super.tick();
    if (!level().isClientSide()) containerAnimation.tick();
    if (!level().isClientSide()) chunkLoading.update(this);
    if (level() instanceof ServerLevel server && isAlive()) {
      if (tickCount % 20 == 0) {
        NpcRoster.get(server.getServer()).update(this);
        cacheOwnerRespawn(server);
      }
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
      if (onGround() && !isInWater()) {
        var feet = blockPosition();
        executionMemory.walked(
            level().dimension().identifier().toString(), feet.getX(), feet.getY(), feet.getZ());
      }
      // Drowning first, then reflexes; planned work is paused while either owns the body.
      if (!waterSafety.tick() && !reflexes.tick()) mind().tick();
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
    output.putString("RememberedLocations", locations.json());
    output.putString("CommandContext", commandContext);
    output.putString("LocalTaskState", mind == null ? localTaskState : mind.saveState());
    output.putString("ExpeditionState", expeditionState);
    output.putString("WorkSequenceState", workSequenceState);
    output.putString("RecentActionResults", recentResults.toString());
    output.putString("ExecutionMemory", executionMemory.save());
    output.putString("MissionGoal", mission.save());
    output.putBoolean("Recovering", recovering);
    output.putString("OwnerRespawnDimension", respawnDimension);
    output.putDouble("OwnerRespawnX", respawnPosition.x);
    output.putDouble("OwnerRespawnY", respawnPosition.y);
    output.putDouble("OwnerRespawnZ", respawnPosition.z);
    output.putFloat("OwnerRespawnYaw", respawnYaw);
    output.putBoolean("FollowOwner", followsOwner());
    output.putInt("foodLevel", vitals.food());
    output.putFloat("foodSaturationLevel", vitals.saturation());
    output.putFloat("foodExhaustionLevel", vitals.exhaustion());
    output.putInt("foodTickTimer", vitals.timer());
    inventory.save(output.child("CompanionInventory"));
  }

  protected void readAdditionalSaveData(ValueInput input) {
    super.readAdditionalSaveData(input);
    recovering = input.getBooleanOr("Recovering", false);
    respawnDimension = input.getStringOr("OwnerRespawnDimension", "");
    respawnPosition =
        new net.minecraft.world.phys.Vec3(
            input.getDoubleOr("OwnerRespawnX", 0),
            input.getDoubleOr("OwnerRespawnY", 0),
            input.getDoubleOr("OwnerRespawnZ", 0));
    respawnYaw = input.getFloatOr("OwnerRespawnYaw", 0);
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
    try {
      locations.load(input.getStringOr("RememberedLocations", "[]"));
    } catch (RuntimeException ignored) {
      locations.load("[]");
    }
    localTaskState = input.getStringOr("LocalTaskState", "");
    try {
      String context = input.getStringOr("CommandContext", "{}");
      commandContext = context.length() <= 4096
          ? com.google.gson.JsonParser.parseString(context).getAsJsonObject().toString() : "{}";
    } catch (RuntimeException ignored) { commandContext = "{}"; }
    executionMemory.restore(input.getStringOr("ExecutionMemory", ""));
    mission.restore(input.getStringOr("MissionGoal", "{}"));
    expeditionState = input.getStringOr("ExpeditionState", "{}");
    workSequenceState = input.getStringOr("WorkSequenceState", "{}");
    if (workSequenceState.length() > 16384) workSequenceState = "{}";
    if (expeditionState.length() > 65536) expeditionState = "{}";
    try {
      String results = input.getStringOr("RecentActionResults", "[]");
      if (results.length() <= 6000) {
        var parsed = com.google.gson.JsonParser.parseString(results).getAsJsonArray();
        if (parsed.size() <= 8) recentResults = parsed;
      }
    } catch (RuntimeException ignored) {
    }
    if (localTaskState.length() > 65536) localTaskState = "invalid";
    if (mind != null) mind.restoreState(localTaskState);
  }

  public String localTaskState() {
    return localTaskState;
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

  protected void dropAllDeathLoot(ServerLevel level, DamageSource source) {
    if (!NpcSettings.DROP_ITEMS_ON_DEATH.get()) return;
    captureDrops(new java.util.ArrayList<>());
    for (int i = 0; i < NpcInventory.SIZE; i++) {
      var item = inventory.removeItemNoUpdate(i);
      if (!item.isEmpty()) spawnAtLocation(level, item);
    }
    var drops = captureDrops(null);
    if (!net.neoforged.neoforge.common.CommonHooks.onLivingDrops(
        this, source, drops, lastHurtByPlayerMemoryTime > 0)) drops.forEach(level::addFreshEntity);
  }

  /**
   * Clear both persistent tasks and current AI calls; recovery and recall always end in standby.
   */
  public void standBy() {
    setInstruction("");
    setFollowsOwner(false);
    setTarget(null);
    stopUsingItem();
    getNavigation().stop();
    setSprinting(false);
    setShiftKeyDown(false);
    setMining(false);
    setAiStatus("待機中");
    localTaskState = "";
    expeditionState = "{}";
  }

  private void cacheOwnerRespawn(ServerLevel level) {
    var player = owner == null ? null : level.getServer().getPlayerList().getPlayer(owner);
    if (player == null) return;
    String key = String.valueOf(player.getRespawnConfig());
    if (key.equals(respawnConfigKey) && !respawnDimension.isEmpty()) return;
    respawnConfigKey = key;
    var at =
        player.findRespawnPositionAndUseSpawnBlock(
            false, net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING);
    respawnDimension = at.newLevel().dimension().identifier().toString();
    respawnPosition = at.position();
    respawnYaw = at.yRot();
  }

  @Override
  public void die(DamageSource source) {
    super.die(source);
    if (level() instanceof ServerLevel server && dead && owner != null && !recovering) {
      recovering = true;
      standBy();
      var data = dev.avatarcraft.npc.ai.NpcWorkData.get(server);
      if (data.furnaces.values().removeIf(getUUID()::equals)) data.setDirty();
      NpcService.get(server.getServer()).reportDeath(this, source.getMsgId());
    }
  }

  @Override
  protected void tickDeath() {
    if (owner == null) {
      super.tickDeath();
      return;
    }
    if (!(level() instanceof ServerLevel server)) {
      deathTime = Math.min(20, deathTime + 1);
      return;
    }
    if (++deathTime < 20 || (deathTime > 20 && deathTime % 100 != 0)) return;
    var player = server.getServer().getPlayerList().getPlayer(owner);
    net.minecraft.world.level.portal.TeleportTransition at = null;
    if (player != null)
      at =
          player.findRespawnPositionAndUseSpawnBlock(
              false, net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING);
    var destination =
        at == null
            ? server
                .getServer()
                .getLevel(
                    net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.Identifier.tryParse(respawnDimension) == null
                            ? net.minecraft.resources.Identifier.parse("minecraft:overworld")
                            : net.minecraft.resources.Identifier.parse(respawnDimension)))
            : at.newLevel();
    boolean missingDimension = destination == null;
    if (missingDimension) destination = server.getServer().findRespawnDimension();
    var position =
        at != null
            ? at.position()
            : respawnDimension.isEmpty() || missingDimension
                ? net.minecraft.world.phys.Vec3.atBottomCenterOf(
                    adjustSpawnLocation(destination, destination.getRespawnData().pos()))
                : respawnPosition;
    var safe = NpcTravel.safePosition(destination, position);
    if (safe == null && at == null) {
      destination = server.getServer().findRespawnDimension();
      safe =
          NpcTravel.safePosition(
              destination,
              net.minecraft.world.phys.Vec3.atBottomCenterOf(
                  adjustSpawnLocation(destination, destination.getRespawnData().pos())));
    }
    if (safe == null) {
      setAiStatus("復帰先に安全な場所がありません。再試行中");
      return;
    }
    dead = false;
    recovering = false;
    deathTime = 0;
    hurtTime = 0;
    setHealth(getMaxHealth());
    setPose(Pose.STANDING);
    clearFire();
    removeAllEffects();
    setAirSupply(getMaxAirSupply());
    fallDistance = 0;
    setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
    vitals.restore(20, 5, 0, 0);
    entityData.set(FOOD, 20);
    standBy();
    var moved = NpcTravel.move(this, destination, safe, at == null ? respawnYaw : at.yRot());
    if (moved == null) {
      dead = true;
      recovering = true;
      setHealth(0);
      setPose(Pose.DYING);
      deathTime = 20;
      return;
    }
    destination.broadcastEntityEvent(moved, (byte) -70);
    NpcService.get(destination.getServer())
        .record(moved, "respawn", "所有者のリスポーン地点へ復帰して待機", owner, new com.google.gson.JsonObject());
  }

  @Override
  public void handleEntityEvent(byte event) {
    if (event == (byte) -70) {
      dead = false;
      deathTime = 0;
      hurtTime = 0;
      setPose(Pose.STANDING);
    } else super.handleEntityEvent(event);
  }
}

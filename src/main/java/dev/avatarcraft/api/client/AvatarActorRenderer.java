package dev.avatarcraft.api.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.avatarcraft.api.AvatarActor;
import dev.avatarcraft.avatar.*;
import dev.avatarcraft.client.*;
import java.util.*;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.layers.*;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.*;

/** Register this renderer for any Mob implementing AvatarActor. Only load on the logical client. */
public class AvatarActorRenderer<T extends Mob & AvatarActor>
    extends HumanoidMobRenderer<
        T,
        AvatarActorRenderer.ActorRenderState,
        HumanoidModel<AvatarActorRenderer.ActorRenderState>>
    implements AutoCloseable {
  public static final class ActorRenderState extends HumanoidRenderState {
    // AvatarRenderState is reserved for players: MC's dispatcher routes it to PlayerRenderer.
    public int id;
    public boolean inventoryPreview;
    AvatarActorVisual visual;
    MotionSignals signals;
  }

  private record Cached(AvatarActorVisual visual, int entityId) {}

  private final Map<UUID, Cached> cache = new HashMap<>();
  private Object world;
  private AvatarActorVisual inventoryVisual;
  private int inventoryEntityId = -1;
  private final Set<String> diagnosed = new HashSet<>();

  public AvatarActorRenderer(EntityRendererProvider.Context context) {
    super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), .4f);
    // Avatar hands submit their own attached items. Vanilla item layers remain for missing models.
    layers.removeIf(
        layer ->
            layer instanceof ItemInHandLayer<?, ?>
                || layer instanceof HumanoidArmorLayer<?, ?, ?>
                || layer instanceof CustomHeadLayer<?, ?>
                || layer instanceof WingsLayer<?, ?>);
    addLayer(
        new ItemInHandLayer<ActorRenderState, HumanoidModel<ActorRenderState>>(this) {
          public void submit(
              PoseStack stack,
              SubmitNodeCollector collector,
              int light,
              ActorRenderState state,
              float yaw,
              float pitch) {
            if (((ActorRenderState) state).visual == null)
              super.submit(stack, collector, light, state, yaw, pitch);
          }
        });
  }

  @Override
  public ActorRenderState createRenderState() {
    return new ActorRenderState();
  }

  @Override
  public Identifier getTextureLocation(ActorRenderState state) {
    return net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkin().body().texturePath();
  }

  @Override
  public void extractRenderState(T entity, ActorRenderState state, float partialTick) {
    super.extractRenderState(entity, state, partialTick);
    state.id = entity.getId();
    state.inventoryPreview = false;
    if (world != entity.level()) {
      close();
      world = entity.level();
    }
    if (entity.tickCount % 100 == 0)
      cache
          .entrySet()
          .removeIf(
              entry -> {
                if (entity.level().getEntity(entry.getValue().entityId()) != null) return false;
                entry.getValue().visual().close();
                return true;
              });
    var entry = AvatarLibrary.resolve(entity.avatarId());
    if (Boolean.getBoolean("avatarcraft.qa")
        && diagnosed.add(entity.getUUID() + ":" + entity.avatarId()))
      System.getLogger("AvatarCraft QA")
          .log(
              System.Logger.Level.INFO,
              "Actor avatar "
                  + entity.avatarId()
                  + ": "
                  + (entry == null ? "missing" : entry.path().getFileName())
                  + "; catalog size "
                  + AvatarLibrary.entries().size());
    var previous = cache.get(entity.getUUID());
    if (previous != null
        && (entry == null || previous.visual().animation.archive != entry.archive())) {
      previous.visual().close();
      cache.remove(entity.getUUID());
      previous = null;
    }
    if (entry != null && previous == null) {
      previous =
          new Cached(
              new AvatarActorVisual(entry.archive(), entity.getUUID().getLeastSignificantBits()),
              entity.getId());
      cache.put(entity.getUUID(), previous);
    }
    var frame = (ActorRenderState) state;
    frame.visual = previous == null ? null : previous.visual();
    var signals = new MotionSignals();
    signals.grounded = entity.onGround();
    signals.sprinting = entity.isSprinting();
    signals.crouching = state.isCrouching;
    signals.inWater = entity.isInWater();
    signals.swimming = state.isVisuallySwimming && signals.inWater;
    signals.crawling = state.hasPose(Pose.SWIMMING) && !signals.inWater;
    signals.fallFlying = state.isFallFlying;
    signals.climbing = entity.onClimbable();
    signals.passenger = state.isPassenger;
    signals.sleeping = state.hasPose(Pose.SLEEPING);
    signals.dead = state.deathTime > 0;
    signals.hurt = state.hasRedOverlay;
    var velocity = entity.getDeltaMovement();
    signals.speed = (float) velocity.horizontalDistance();
    signals.verticalSpeed = (float) velocity.y;
    double yaw = Math.toRadians(state.bodyRot + state.yRot);
    signals.forward = (float) (-velocity.x * Math.sin(yaw) + velocity.z * Math.cos(yaw));
    signals.side = (float) (velocity.x * Math.cos(yaw) + velocity.z * Math.sin(yaw));
    signals.headYaw = state.yRot;
    signals.headPitch = state.xRot;
    signals.swingProgress = state.swingAnimation;
    signals.hasItem = !entity.getMainHandItem().isEmpty();
    signals.leftHand =
        (state.isUsingItem ? state.useItemHand.asArm(state.mainArm) : state.mainArm)
            == HumanoidArm.LEFT;
    signals.mining = entity.avatarMining();
    signals.chargedCrossbow =
        net.minecraft.world.item.CrossbowItem.isCharged(entity.getMainHandItem());
    if (entity.isUsingItem())
      signals.useAction =
          switch (entity.getUseItem().getUseAnimation()) {
            case EAT -> "eat";
            case DRINK -> "drink";
            case BLOCK -> "block";
            case BOW -> "bow";
            case CROSSBOW -> "crossbow_charge";
            case TRIDENT, SPEAR -> "trident";
            case SPYGLASS -> "spyglass";
            case TOOT_HORN -> "horn";
            default -> "use";
          };
    frame.signals = signals;
  }

  @Override
  public void submit(
      ActorRenderState state,
      PoseStack stack,
      SubmitNodeCollector collector,
      CameraRenderState camera) {
    var frame = (ActorRenderState) state;
    if (state.inventoryPreview && frame.visual != null) {
      if (inventoryVisual == null
          || inventoryEntityId != state.id
          || inventoryVisual.animation.archive != frame.visual.animation.archive) {
        if (inventoryVisual != null) inventoryVisual.close();
        inventoryVisual = new AvatarActorVisual(frame.visual.animation.archive, state.id);
        inventoryEntityId = state.id;
      }
      frame.visual = inventoryVisual;
      frame.signals = new MotionSignals();
      frame.signals.grounded = true;
      frame.signals.headYaw = state.yRot;
      frame.signals.headPitch = state.xRot;
    }
    if (frame.visual != null) frame.visual.submit(state, frame.signals, stack, collector);
    if (Boolean.getBoolean("avatarcraft.qa") && diagnosed.add("submit:" + state.id))
      System.getLogger("AvatarCraft QA")
          .log(
              System.Logger.Level.INFO,
              "Actor submit visual="
                  + (frame.visual != null)
                  + ", invisible="
                  + state.isInvisible
                  + ", id="
                  + state.id);
    super.submit(state, stack, collector, camera);
  }

  @Override
  protected RenderType getRenderType(
      ActorRenderState state, boolean visible, boolean transparent, boolean glowing) {
    if (Boolean.getBoolean("avatarcraft.qa") && diagnosed.add("body:" + state.id))
      System.getLogger("AvatarCraft QA")
          .log(
              System.Logger.Level.INFO,
              "Actor body visual="
                  + (((ActorRenderState) state).visual != null)
                  + ", id="
                  + state.id);
    return ((ActorRenderState) state).visual == null
        ? super.getRenderType(state, visible, transparent, glowing)
        : null;
  }

  public void close() {
    if (inventoryVisual != null) inventoryVisual.close();
    inventoryVisual = null;
    cache.values().forEach(c -> c.visual().close());
    cache.clear();
    diagnosed.clear();
  }
}

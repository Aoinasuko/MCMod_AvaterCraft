package dev.avatarcraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Inventory-style extraction with an explicit preview marker for its independent pose/physics. */
public final class AvatarPreview {
  private static final java.util.Map<AvatarRenderState, AvatarSession> PREVIEWS = new java.util.WeakHashMap<>();

  public static boolean isPreview(AvatarRenderState state) {
    return PREVIEWS.containsKey(state);
  }

  /** Draw only explicitly requested GUI previews. Never replaces a world player. */
  public static void render(net.neoforged.neoforge.client.event.RenderPlayerEvent.Pre<?> event) {
    var state = event.getRenderState();
    var session = PREVIEWS.get(state);
    if (session == null) return;
    var visual = session.previewVisual();
    try {
      visual.animation.expressions(session.config);
      visual.animation.expressionKeys.set(session.preview == null
          ? session.expressionKeys.keys() : java.util.List.of(session.preview));
      var signals = new dev.avatarcraft.avatar.MotionSignals();
      signals.grounded = true;
      signals.headYaw = state.yRot;
      signals.headPitch = state.xRot;
      visual.submit(state, signals, event.getPoseStack(), event.getSubmitNodeCollector());
      event.setCanceled(true);
    } catch (Exception e) {
      System.getLogger("B.E.P 3DAvater Loader").log(System.Logger.Level.WARNING, "Preview failed", e);
    }
  }

  public static void extractEntityInInventoryFollowsMouse(
      GuiGraphicsExtractor graphics,
      int x0,
      int y0,
      int x1,
      int y1,
      int size,
      float offsetY,
      float mouseX,
      float mouseY,
      LivingEntity entity) {
    extractEntityInInventoryFollowsMouse(graphics, x0, y0, x1, y1, size, offsetY, mouseX, mouseY, entity, AvatarSession.active);
  }

  public static void extractEntityInInventoryFollowsMouse(
      GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int size, float offsetY,
      float mouseX, float mouseY, LivingEntity entity, AvatarSession session) {
    float xAngle = (float) Math.atan(((x0 + x1) / 2f - mouseX) / 40f);
    float yAngle = (float) Math.atan(((y0 + y1) / 2f - mouseY) / 40f);
    var rotation = new Quaternionf().rotateZ((float) Math.PI);
    var xRotation = new Quaternionf().rotateX(yAngle * 20 * (float) Math.PI / 180);
    rotation.mul(xRotation);
    EntityRenderer<? super LivingEntity, ?> renderer =
        Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
    var state = renderer.createRenderState(entity, 1);
    state.shadowPieces.clear();
    state.outlineColor = 0;
    state.nameTag = null;
    state.nameTagAttachment = null;
    if (state instanceof LivingEntityRenderState living) {
      living.bodyRot = 180 + xAngle * 20;
      living.yRot = xAngle * 20;
      living.xRot = living.pose == Pose.FALL_FLYING ? 0 : -yAngle * 20;
      living.boundingBoxWidth /= living.scale;
      living.boundingBoxHeight /= living.scale;
      living.scale = 1;
    }
    if (state instanceof AvatarRenderState avatar && session != null) PREVIEWS.put(avatar, session);
    if (state instanceof dev.avatarcraft.api.client.AvatarActorRenderer.ActorRenderState actor)
      actor.inventoryPreview = true;
    var translation = new Vector3f(0, state.boundingBoxHeight / 2 + offsetY, 0);
    graphics.entity(state, size, translation, rotation, xRotation, x0, y0, x1, y1);
  }
}

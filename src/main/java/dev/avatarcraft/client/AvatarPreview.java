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
final class AvatarPreview {
  static void extractEntityInInventoryFollowsMouse(
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
    if (state instanceof LivingEntityRenderState living) {
      living.bodyRot = 180 + xAngle * 20;
      living.yRot = xAngle * 20;
      living.xRot = living.pose == Pose.FALL_FLYING ? 0 : -yAngle * 20;
      living.boundingBoxWidth /= living.scale;
      living.boundingBoxHeight /= living.scale;
      living.scale = 1;
    }
    if (state instanceof AvatarRenderState avatar) AvatarRenderer.markPreview(avatar);
    var translation = new Vector3f(0, state.boundingBoxHeight / 2 + offsetY, 0);
    graphics.entity(state, size, translation, rotation, xRotation, x0, y0, x1, y1);
  }
}

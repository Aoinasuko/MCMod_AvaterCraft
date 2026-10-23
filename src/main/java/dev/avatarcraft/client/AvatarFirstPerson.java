package dev.avatarcraft.client;

import dev.avatarcraft.avatar.Avatar;
import java.util.Locale;
import net.minecraft.world.entity.HumanoidArm;
import net.neoforged.neoforge.client.event.RenderArmEvent;
import org.joml.Quaternionf;

/** Replaces arm geometry while retaining Minecraft's first-person animation and item renderer. */
public final class AvatarFirstPerson {
  private static final Avatar.Material FALLBACK = new Avatar.Material();

  public static void render(RenderArmEvent event) {
    var session = AvatarSession.active;
    if (session == null) return;
    boolean left = event.getArm() == HumanoidArm.LEFT;
    var arm = left ? session.leftFirstPerson : session.rightFirstPerson;
    if (arm.parts().isEmpty()) return;
    var stack = event.getPoseStack();
    stack.pushPose();
    try {
      // The event precedes ModelPart's pivot/roll, which vanilla normally applies itself.
      var initial = event.getArmPart().getInitialPose();
      stack.translate(initial.x() / 16f, initial.y() / 16f, initial.z() / 16f);
      stack.rotate(
          new Quaternionf().rotationZYX(left ? -.1f : .1f, initial.yRot(), initial.xRot()));
      stack.scale(initial.xScale(), initial.yScale(), initial.zScale());
      for (var part : arm.parts()) {
        var mesh = part.mesh();
        for (int sub = 0; sub < part.indices().length; sub++) {
          int[] indices = part.indices()[sub];
          if (indices.length == 0) continue;
          int material = mesh.submeshes[sub].material;
          var mat = material < 0 ? FALLBACK : session.archive.avatar().materials[material];
          var collector = event.getSubmitNodeCollector();
          int light = event.getLightCoords();
          AvatarRenderer.draw(
              session, mesh, part.vertices(), indices, mat, stack, collector, light, false, false);
          if (mat.number("_UseEmission", 0) > 0 && mat.texture("_EmissionMap") != null)
            AvatarRenderer.draw(
                session, mesh, part.vertices(), indices, mat, stack, collector, light, true, false);
          if (mat.number("_OutlineWidth", 0) > 0
              && mat.shader != null
              && mat.shader.toLowerCase(Locale.ROOT).contains("outline"))
            AvatarRenderer.draw(
                session, mesh, part.vertices(), indices, mat, stack, collector, light, false, true);
        }
      }
      event.setCanceled(true);
    } catch (Exception e) {
      System.getLogger("AvatarCraft")
          .log(
              System.Logger.Level.ERROR,
              "Avatar first-person rendering failed; restoring vanilla model",
              e);
      AvatarSession.active = null;
    } finally {
      stack.popPose();
    }
  }
}

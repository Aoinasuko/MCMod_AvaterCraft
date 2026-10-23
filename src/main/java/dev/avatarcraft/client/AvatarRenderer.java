package dev.avatarcraft.client;

import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import dev.avatarcraft.avatar.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class AvatarRenderer {
  private static final Avatar.Material FALLBACK = new Avatar.Material();
  private static final Set<AvatarRenderState> PREVIEWS =
      Collections.newSetFromMap(new WeakHashMap<>());

  static void markPreview(AvatarRenderState state) {
    PREVIEWS.add(state);
  }

  public static void render(RenderPlayerEvent.Pre<?> event) {
    var session = AvatarSession.active;
    var player = Minecraft.getInstance().player;
    var state = event.getRenderState();
    boolean preview = PREVIEWS.remove(state);
    if (session == null
        || player == null
        || state.id != player.getId()
        || state.isInvisible
        || state.isSpectator) return;
    try {
      submit(
          session,
          state,
          event.getPoseStack(),
          event.getSubmitNodeCollector(),
          event.getRenderer().getModel(),
          preview);
      event.setCanceled(true);
    } catch (Exception e) {
      System.getLogger("AvatarCraft")
          .log(System.Logger.Level.ERROR, "Avatar rendering failed; restoring vanilla model", e);
      AvatarSession.active = null;
    }
  }

  private static void submit(
      AvatarSession session,
      AvatarRenderState s,
      PoseStack stack,
      SubmitNodeCollector collector,
      PlayerModel itemModel,
      boolean preview) {
    var a = session.archive.avatar();
    var pose = preview ? session.previewPose : session.pose;
    double now = System.nanoTime() * 1e-9;
    var use = s.getUseItemStackForArm(s.useItemHand.asArm(s.mainArm)).getUseAnimation();
    boolean eating =
        s.isUsingItem && (use == ItemUseAnimation.EAT || use == ItemUseAnimation.DRINK);
    var weights =
        session.expressions.evaluate(
            session.config, a.defaults, now, eating, s.hasRedOverlay, session.preview);
    var movement = new Vector3f((float) s.x, (float) s.y, (float) s.z);
    float bodyYaw = s.bodyRot;
    if (a.motions != null) {
      var player = Minecraft.getInstance().player;
      var signals = new MotionSignals();
      signals.grounded = player.onGround();
      signals.sprinting = player.isSprinting();
      signals.crouching = s.isCrouching;
      signals.inWater = s.isInWater;
      signals.swimming = s.isVisuallySwimming && s.isInWater;
      signals.crawling = s.hasPose(Pose.SWIMMING) && !s.isInWater;
      signals.fallFlying = s.isFallFlying;
      signals.creativeFlying = player.getAbilities().flying;
      signals.climbing = player.onClimbable();
      signals.passenger = s.isPassenger;
      signals.sleeping = s.hasPose(Pose.SLEEPING);
      signals.dead = s.deathTime > 0;
      signals.hurt = s.hasRedOverlay;
      var velocity = player.getDeltaMovement();
      signals.speed = (float) velocity.horizontalDistance();
      signals.verticalSpeed = (float) velocity.y;
      float lookYaw = s.bodyRot + s.yRot;
      if (!preview)
        bodyYaw =
            session.facing.evaluate(
                s.bodyRot,
                lookYaw,
                signals.speed,
                !signals.passenger && !signals.sleeping,
                s.ageInTicks / 20d);
      double yaw = Math.toRadians(lookYaw);
      signals.forward = (float) (-velocity.x * Math.sin(yaw) + velocity.z * Math.cos(yaw));
      signals.side = (float) (velocity.x * Math.cos(yaw) + velocity.z * Math.sin(yaw));
      signals.headYaw = MotionFacing.wrap(lookYaw - bodyYaw);
      signals.headPitch = s.xRot;
      signals.swingProgress = s.swingAnimation;
      signals.hasItem = !s.getMainHandItemStack().isEmpty();
      signals.leftHand =
          (s.isUsingItem ? s.useItemHand.asArm(s.mainArm) : s.mainArm) == HumanoidArm.LEFT;
      signals.mining = Minecraft.getInstance().hitResult instanceof BlockHitResult;
      signals.chargedCrossbow = CrossbowItem.isCharged(s.getMainHandItemStack());
      if (s.isUsingItem)
        signals.useAction =
            switch (use) {
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
      pose.animateMotion(signals, s.ageInTicks / 20d, movement, bodyYaw);
    } else
      pose.animate(
          s.walkAnimationPos,
          s.walkAnimationSpeed,
          s.yRot,
          s.xRot,
          s.isCrouching,
          s.isVisuallySwimming || s.isFallFlying,
          s.swingAnimation,
          eating,
          now,
          movement,
          s.bodyRot);
    if (!preview) AvatarQa.record(session);
    Quaternionf leftAim = null, rightAim = null;
    boolean aiming =
        s.isUsingItem
            && Set.of(
                    ItemUseAnimation.BOW,
                    ItemUseAnimation.CROSSBOW,
                    ItemUseAnimation.TRIDENT,
                    ItemUseAnimation.SPEAR)
                .contains(use);
    boolean charged = CrossbowItem.isCharged(s.getMainHandItemStack());
    if (aiming || charged) {
      // Item display/aim conventions stay compatible; avatar bones keep their authored motions.
      float previousYaw = s.yRot;
      try {
        s.yRot = MotionFacing.wrap(s.bodyRot + previousYaw - bodyYaw);
        itemModel.setupAnim(s);
      } finally {
        s.yRot = previousYaw;
      }
      var arm = (aiming ? s.useItemHand.asArm(s.mainArm) : s.mainArm);
      var part = itemModel.getArm(arm);
      var rotation =
          new Quaternionf().rotateX((float) Math.PI).rotateZYX(part.zRot, part.yRot, part.xRot);
      if (arm == HumanoidArm.LEFT) leftAim = rotation;
      else rightAim = rotation;
    }
    stack.pushPose();
    try {
      stack.rotateDegrees(Axis.YP, -bodyYaw);
      float modelScale = 1.8f / a.height;
      stack.scale(modelScale, modelScale, modelScale);
      stack.translate(0, -a.feetOffset, 0);
      if (a.motions == null && s.isCrouching) stack.translate(0, -.15f, 0);
      if (a.motions == null && s.deathTime > 0)
        stack.rotateDegrees(Axis.ZP, Math.min(90, s.deathTime * 5));
      var ordered = new ArrayList<>(Arrays.asList(a.meshes));
      for (var mesh : ordered) {
        float[] skinned = pose.skin(mesh, weights);
        for (int sub = 0; sub < mesh.submeshes.length; sub++) {
          var mat =
              mesh.submeshes[sub].material < 0
                  ? FALLBACK
                  : a.materials[mesh.submeshes[sub].material];
          int[] indices = mesh.geometry.indices()[sub];
          draw(session, mesh, skinned, indices, mat, stack, collector, s.lightCoords, false, false);
          if (mat.number("_UseEmission", 0) > 0 && mat.texture("_EmissionMap") != null)
            draw(
                session, mesh, skinned, indices, mat, stack, collector, s.lightCoords, true, false);
          if (mat.number("_OutlineWidth", 0) > 0
              && mat.shader != null
              && mat.shader.toLowerCase(Locale.ROOT).contains("outline"))
            draw(
                session, mesh, skinned, indices, mat, stack, collector, s.lightCoords, false, true);
        }
      }
      boolean heldToEye = s.isUsingItem && !s.heldOnHead.isEmpty() && s.swingAnimation < 1e-5f;
      boolean eyeLeft = heldToEye && s.useItemHand.asArm(s.mainArm) == HumanoidArm.LEFT;
      if (!eyeLeft)
        hand(
            session,
            pose,
            "LeftHand",
            s.leftHandItemState,
            stack,
            collector,
            s.lightCoords,
            true,
            leftAim);
      if (!heldToEye || eyeLeft)
        hand(
            session,
            pose,
            "RightHand",
            s.rightHandItemState,
            stack,
            collector,
            s.lightCoords,
            false,
            rightAim);
      if (heldToEye) eye(session, pose, s.heldOnHead, stack, collector, s.lightCoords, eyeLeft);
    } finally {
      stack.popPose();
    }
  }

  private static void hand(
      AvatarSession session,
      AvatarPose pose,
      String bone,
      ItemStackRenderState item,
      PoseStack stack,
      SubmitNodeCollector collector,
      int light,
      boolean left,
      Quaternionf aim) {
    Integer b = session.archive.avatar().humanoid.get(bone);
    if (b == null || item.isEmpty()) return;
    var reflection = new Matrix4f().scaling(-1, 1, 1);
    float modelScale = 1.8f / session.archive.avatar().height;
    var transform =
        new Matrix4f(reflection).mul(pose.attachmentFrame(b, modelScale)).mul(reflection);
    if (aim != null)
      transform =
          new Matrix4f()
              .translationRotateScale(
                  transform.getTranslation(new Vector3f()), aim, 1 / modelScale);
    stack.pushPose();
    try {
      stack.mulPose(transform);
      stack.rotateDegrees(Axis.XP, -90);
      stack.rotateDegrees(Axis.YP, 180);
      stack.translate(left ? .02f : -.02f, .02f, -.06f);
      item.submit(stack, collector, light, OverlayTexture.NO_OVERLAY, 0);
    } finally {
      stack.popPose();
    }
  }

  private static void eye(
      AvatarSession session,
      AvatarPose pose,
      ItemStackRenderState item,
      PoseStack stack,
      SubmitNodeCollector collector,
      int light,
      boolean left) {
    var avatar = session.archive.avatar();
    Integer head = avatar.humanoid.get("Head"),
        hand = avatar.humanoid.get(left ? "LeftHand" : "RightHand");
    if (head == null) return;
    var reflection = new Matrix4f().scaling(-1, 1, 1);
    var frame =
        new Matrix4f(reflection)
            .mul(pose.attachmentFrame(head, 1.8f / avatar.height))
            .mul(reflection);
    if (hand != null)
      frame.setTranslation(pose.world[hand].getTranslation(new Vector3f()).mul(-1, 1, 1));
    stack.pushPose();
    try {
      stack.mulPose(frame);
      stack.rotateDegrees(Axis.YP, 180);
      stack.scale(.625f, -.625f, -.625f);
      item.submit(stack, collector, light, OverlayTexture.NO_OVERLAY, 0);
    } finally {
      stack.popPose();
    }
  }

  static void draw(
      AvatarSession session,
      Avatar.Mesh mesh,
      float[] vertices,
      int[] indices,
      Avatar.Material mat,
      PoseStack stack,
      SubmitNodeCollector collector,
      int light,
      boolean emission,
      boolean outline) {
    String slot = emission ? "_EmissionMap" : outline ? "_OutlineTex" : "_MainTex";
    var texture = session.textures.get(mat.texture(slot));
    var type = AvatarMaterials.get(mat, texture, emission, outline);
    float[] color =
        mat.color(emission ? "_EmissionColor" : outline ? "_OutlineColor" : "_Color", 1, 1, 1, 1);
    float[] scale = mat.uv(slot, "scale", 1, 1), offset = mat.uv(slot, "offset", 0, 0);
    float width = outline ? mat.number("_OutlineWidth", 0) * .001f : 0;
    // Snapshot arrays are immutable for the lifetime of deferred geometry submission.
    collector.submitCustomGeometry(
        stack,
        type,
        (pose, buffer) -> {
          var normal = new Vector3f();
          for (int t = 0; t < indices.length; t += 3)
            for (int k = 0; k < 3; k++) {
              int ix = indices[t + (outline ? k : 2 - k)], v = ix * 6, src = ix * 16;
              normal.set(vertices[v + 3], vertices[v + 4], vertices[v + 5]);
              float x = vertices[v] + normal.x * width,
                  y = vertices[v + 1] + normal.y * width,
                  z = vertices[v + 2] + normal.z * width;
              buffer
                  .addVertex(pose, x, y, z)
                  .setColor(color[0], color[1], color[2], color[3])
                  .setUv(
                      mesh.geometry.vertices()[src + 6] * scale[0] + offset[0],
                      1 - (mesh.geometry.vertices()[src + 7] * scale[1] + offset[1]))
                  .setOverlay(OverlayTexture.NO_OVERLAY)
                  .setLight(emission ? 15728880 : light)
                  .setNormal(pose, normal.x, normal.y, normal.z);
            }
        });
  }
}

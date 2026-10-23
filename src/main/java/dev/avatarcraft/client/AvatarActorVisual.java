package dev.avatarcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.avatarcraft.api.AvatarAnimation;
import dev.avatarcraft.api.AvatarHeadFit;
import dev.avatarcraft.avatar.*;
import java.util.*;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Framework implementation: independent actor animation with reference-counted GPU assets. */
public final class AvatarActorVisual implements AutoCloseable {
  private static final Map<AvatarArchive, Shared> ASSETS = new IdentityHashMap<>();
  private static final Avatar.Material FALLBACK = new Avatar.Material();

  private static final class Shared {
    final AvatarTextures textures;
    final AvatarHeadFit head;
    int users;

    Shared(AvatarArchive archive) {
      textures = new AvatarTextures(archive);
      head = AvatarHeadFit.measure(archive).orElse(null);
    }
  }

  public final AvatarAnimation animation;
  private final Shared assets;
  private boolean closed;

  public AvatarActorVisual(AvatarArchive archive, long seed) {
    animation = new AvatarAnimation(archive, seed);
    assets = ASSETS.computeIfAbsent(archive, Shared::new);
    assets.users++;
  }

  public AvatarHeadFit headFitting() {
    return assets.head;
  }

  public void submit(
      HumanoidRenderState state,
      MotionSignals signals,
      PoseStack stack,
      SubmitNodeCollector collector) {
    if (closed || state.isInvisible) return;
    animation.update(
        signals,
        state.ageInTicks / 20d,
        new Vector3f((float) state.x, (float) state.y, (float) state.z),
        state.bodyRot);
    var avatar = animation.archive.avatar();
    stack.pushPose();
    try {
      stack.rotateDegrees(Axis.YP, -state.bodyRot);
      float scale = 1.8f / avatar.height;
      stack.scale(scale, scale, scale);
      stack.translate(0, -avatar.feetOffset, 0);
      for (var mesh : avatar.meshes) {
        var vertices = animation.pose.skin(mesh, animation.weights());
        for (int sub = 0; sub < mesh.submeshes.length; sub++) {
          int material = mesh.submeshes[sub].material;
          var mat = material < 0 ? FALLBACK : avatar.materials[material];
          int[] indices = mesh.geometry.indices()[sub];
          AvatarRenderer.draw(
              assets.textures,
              mesh,
              vertices,
              indices,
              mat,
              stack,
              collector,
              state.lightCoords,
              false,
              false);
          if (mat.number("_OutlineWidth", 0) > 0
              && mat.shader != null
              && mat.shader.toLowerCase(Locale.ROOT).contains("outline"))
            AvatarRenderer.draw(
                assets.textures,
                mesh,
                vertices,
                indices,
                mat,
                stack,
                collector,
                state.lightCoords,
                false,
                true);
        }
      }
      hand("LeftHand", state.leftHandItemState, true, scale, state, stack, collector);
      hand("RightHand", state.rightHandItemState, false, scale, state, stack, collector);
    } finally {
      stack.popPose();
    }
  }

  private void hand(
      String name,
      ItemStackRenderState item,
      boolean left,
      float scale,
      HumanoidRenderState state,
      PoseStack stack,
      SubmitNodeCollector collector) {
    Integer bone = animation.archive.avatar().humanoid.get(name);
    if (bone == null || item.isEmpty()) return;
    var reflect = new Matrix4f().scaling(-1, 1, 1);
    stack.pushPose();
    try {
      stack.mulPose(
          new Matrix4f(reflect).mul(animation.pose.attachmentFrame(bone, scale)).mul(reflect));
      stack.rotateDegrees(Axis.XP, -90);
      stack.rotateDegrees(Axis.YP, 180);
      stack.translate(left ? .02f : -.02f, .02f, -.06f);
      item.submit(stack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
    } finally {
      stack.popPose();
    }
  }

  public void close() {
    if (closed) return;
    closed = true;
    if (--assets.users == 0) {
      assets.textures.close();
      ASSETS.remove(animation.archive);
    }
  }
}

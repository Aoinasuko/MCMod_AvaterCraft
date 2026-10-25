package dev.avatarcraft.client;

import com.mojang.blaze3d.vertex.*;
import dev.avatarcraft.avatar.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Vector3f;

/** Shared geometry submission for all avatar integrations. */
public final class AvatarGeometry {
  public static void draw(
      AvatarTextures textures,
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
    var texture = textures.get(mat.texture(slot));
    var type = AvatarMaterials.get(mat, texture, textures.surface(mat), emission, outline);
    float[] color =
        mat.color(emission ? "_EmissionColor" : outline ? "_OutlineColor" : "_Color", 1, 1, 1, 1);
    boolean quads = emission || MaterialAlpha.mode(mat) == MaterialAlpha.Mode.TRANSLUCENT;
    float width = outline ? mat.number("_OutlineWidth", 0) * .001f : 0;
    // Snapshot arrays are immutable for the lifetime of deferred geometry submission.
    collector.submitCustomGeometry(
        stack,
        type,
        (pose, buffer) -> {
          var normal = new Vector3f();
          for (int t = 0; t < indices.length; t += 3)
            for (int k = 0; k < (quads ? 4 : 3); k++) {
              int corner = Math.min(k, 2);
              int ix = indices[t + (outline ? corner : 2 - corner)], v = ix * 6, src = ix * 16;
              normal.set(vertices[v + 3], vertices[v + 4], vertices[v + 5]);
              float x = vertices[v] + normal.x * width,
                  y = vertices[v + 1] + normal.y * width,
                  z = vertices[v + 2] + normal.z * width;
              buffer
                  .addVertex(pose, x, y, z)
                  .setColor(color[0], color[1], color[2], color[3])
                  .setUv(mesh.geometry.vertices()[src + 6], 1 - mesh.geometry.vertices()[src + 7])
                  .setOverlay(OverlayTexture.NO_OVERLAY)
                  .setLight(emission ? 15728880 : light)
                  .setNormal(pose, normal.x, normal.y, normal.z);
            }
        });
  }
}

package dev.avatarcraft.api;

import dev.avatarcraft.avatar.*;
import java.util.*;
import org.joml.Vector3f;

/** A helmet fits the skinned head surface even when the imported Head pivot sits above the face. */
public record AvatarHeadFit(Vector3f offset, Vector3f size, int bone) {
  public Vector3f position(AvatarPose pose) {
    return pose.attachmentRotation(bone)
        .transform(new Vector3f(offset))
        .add(pose.world[bone].getTranslation(new Vector3f()));
  }

  public static Optional<AvatarHeadFit> measure(AvatarArchive archive) {
    var avatar = archive.avatar();
    var head = avatar.humanoid.get("Head");
    if (head == null) return Optional.empty();
    var family = new BitSet(avatar.bones.length);
    for (int i = 0; i < avatar.bones.length; i++) {
      for (int parent = i; parent >= 0; parent = avatar.bones[parent].parent)
        if (parent == head) {
          family.set(i);
          break;
        }
    }
    var preferred =
        Arrays.stream(avatar.meshes)
            .filter(
                mesh -> {
                  String name =
                      mesh.name.substring(mesh.name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                  return name.startsWith("body") || name.equals("face") || name.equals("head");
                })
            .toList();
    var pose = new AvatarPose(avatar);
    var value = measure(pose, preferred, family, head);
    return value.isPresent() ? value : measure(pose, Arrays.asList(avatar.meshes), family, head);
  }

  private static Optional<AvatarHeadFit> measure(
      AvatarPose pose, List<Avatar.Mesh> meshes, BitSet family, int head) {
    var low = new Vector3f(Float.POSITIVE_INFINITY);
    var high = new Vector3f(Float.NEGATIVE_INFINITY);
    var origin = pose.world[head].getTranslation(new Vector3f());
    var inverse = pose.attachmentRotation(head).conjugate();
    boolean any = false;
    for (var mesh : meshes) {
      var source = mesh.geometry.vertices();
      var skin = pose.skin(mesh, pose.avatar.defaults);
      var used = new BitSet(mesh.geometry.vertexCount());
      for (var submesh : mesh.geometry.indices()) for (int vertex : submesh) used.set(vertex);
      for (int vertex = used.nextSetBit(0); vertex >= 0; vertex = used.nextSetBit(vertex + 1)) {
        float influence = 0;
        for (int j = 0; j < 4; j++)
          if (family.get(mesh.bones[(int) source[vertex * 16 + 8 + j]]))
            influence += source[vertex * 16 + 12 + j];
        if (influence < .5f) continue;
        var point =
            new Vector3f(-skin[vertex * 6], skin[vertex * 6 + 1], skin[vertex * 6 + 2]).sub(origin);
        inverse.transform(point);
        low.min(point);
        high.max(point);
        any = true;
      }
    }
    if (!any) return Optional.empty();
    var size = new Vector3f(high).sub(low);
    if (size.x < .001f || size.y < .001f || size.z < .001f) return Optional.empty();
    return Optional.of(
        new AvatarHeadFit(
            new Vector3f((low.x + high.x) / 2, low.y, (low.z + high.z) / 2), size, head));
  }
}

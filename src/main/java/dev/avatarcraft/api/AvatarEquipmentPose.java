package dev.avatarcraft.api;

import dev.avatarcraft.avatar.AvatarPose;
import java.util.Optional;
import org.joml.*;

/** Bone pivots in vanilla humanoid model units, accounting for the renderer's root transform. */
public final class AvatarEquipmentPose {
  public record Part(Vector3f position, Quaternionf rotation) {}

  public static Optional<Part> part(AvatarPose pose, String name) {
    Integer bone = pose.avatar.humanoid.get(name);
    if (bone == null) return Optional.empty();
    float scale = 1.8f / pose.avatar.height;
    var point = pose.world[bone].getTranslation(new Vector3f());
    return Optional.of(at(pose, point, pose.attachmentRotation(bone)));
  }

  public static Part at(AvatarPose pose, Vector3f point, Quaternionf rotation) {
    float scale = 1.8f / pose.avatar.height;
    return new Part(
        new Vector3f(
            -point.x * scale * 16,
            (1.501f - (point.y - pose.avatar.feetOffset) * scale) * 16,
            -point.z * scale * 16),
        rotation);
  }

  public static float span(AvatarPose pose, String... names) {
    float length = 0;
    for (int i = 1; i < names.length; i++) {
      Integer first = pose.avatar.humanoid.get(names[i - 1]),
          second = pose.avatar.humanoid.get(names[i]);
      if (first == null || second == null) return 0;
      length +=
          pose.world[first]
              .getTranslation(new Vector3f())
              .distance(pose.world[second].getTranslation(new Vector3f()));
    }
    return length * 1.8f / pose.avatar.height;
  }

  private AvatarEquipmentPose() {}
}

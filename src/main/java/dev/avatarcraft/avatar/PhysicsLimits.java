package dev.avatarcraft.avatar;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Hard geometric bounds, applied after forces and after collision resolution. */
public final class PhysicsLimits {
  private PhysicsLimits() {}

  public static Vector3f direction(
      Vector3f desired,
      Quaternionf basis,
      String type,
      float maxAngleX,
      float maxAngleZ,
      float[] freeze) {
    Vector3f local = new Quaternionf(basis).conjugate().transform(new Vector3f(desired));
    if (local.lengthSquared() < 1e-12f) local.set(0, 1, 0);
    local.normalize();
    if (freeze != null && freeze.length == 3) {
      if (freeze[0] != 0) local.x = 0;
      if (freeze[1] != 0) local.y = 1;
      if (freeze[2] != 0) local.z = 0;
      if (local.lengthSquared() < 1e-12f) local.set(0, 1, 0);
      local.normalize();
    }
    float xLimit = (float) Math.toRadians(Math.clamp(maxAngleX, 0, 180));
    float zLimit = (float) Math.toRadians(Math.clamp(maxAngleZ, 0, 180));
    switch (type) {
      case "Angle" -> {
        float angle = (float) Math.acos(Math.clamp(local.y, -1, 1));
        if (angle > xLimit) {
          float side = (float) Math.sqrt(local.x * local.x + local.z * local.z);
          if (side < 1e-8f) {
            local.x = 1;
            local.z = 0;
            side = 1;
          }
          float sin = (float) Math.sin(xLimit);
          local.set(local.x / side * sin, (float) Math.cos(xLimit), local.z / side * sin);
        }
      }
      case "Hinge" -> {
        float pitch = Math.clamp((float) Math.atan2(local.z, local.y), -xLimit, xLimit);
        local.set(0, (float) Math.cos(pitch), (float) Math.sin(pitch));
      }
      case "Polar" -> {
        float pitch = (float) Math.atan2(local.z, Math.sqrt(local.x * local.x + local.y * local.y));
        float yaw = (float) Math.atan2(local.x, local.y);
        pitch = Math.clamp(pitch, -xLimit, xLimit);
        yaw = Math.clamp(yaw, -zLimit, zLimit);
        float cosPitch = (float) Math.cos(pitch);
        local.set(
            cosPitch * (float) Math.sin(yaw),
            cosPitch * (float) Math.cos(yaw),
            (float) Math.sin(pitch));
      }
      case "None" -> {}
      default -> throw new IllegalArgumentException("Unknown PhysBone limit: " + type);
    }
    return basis.transform(local).normalize();
  }

  public static void tail(
      Vector3f position,
      Vector3f anchor,
      Vector3f restTail,
      Quaternionf basis,
      Avatar.SpringNode node,
      String type,
      float[] freeze) {
    float restLength = anchor.distance(restTail);
    if (restLength < 1e-7f) {
      position.set(restTail);
      return;
    }
    float length =
        Math.clamp(
            anchor.distance(position),
            restLength * Math.max(.001f, 1 - node.maxSquish),
            restLength * (1 + Math.max(0, node.maxStretch)));
    Vector3f desired = new Vector3f(position).sub(anchor);
    if (desired.lengthSquared() < 1e-12f) desired.set(restTail).sub(anchor);
    position
        .set(anchor)
        .fma(length, direction(desired, basis, type, node.maxAngleX, node.maxAngleZ, freeze));
  }
}

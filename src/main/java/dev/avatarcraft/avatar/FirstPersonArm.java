package dev.avatarcraft.avatar;

import java.util.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Immutable arm geometry in Minecraft ModelPart coordinates, before its pivot/roll transform. */
public final class FirstPersonArm {
  public record Part(Avatar.Mesh mesh, float[] vertices, int[][] indices) {}

  private final List<Part> parts = new ArrayList<>();

  public FirstPersonArm(Avatar avatar, boolean left) {
    String side = left ? "Left" : "Right";
    Integer upper = avatar.humanoid.get(side + "UpperArm");
    Integer hand = avatar.humanoid.get(side + "Hand");
    if (upper == null || hand == null) return;
    var pose = new AvatarPose(avatar);
    if (avatar.motions != null) pose.animateMotion(new MotionSignals(), 0, new Vector3f(), 0);
    else pose.animate(0, 0, 0, 0, false, false, 0, false, 0, new Vector3f());
    var shoulder = reflectedPosition(pose.world[upper]);
    var wrist = reflectedPosition(pose.world[hand]);
    if (shoulder.distanceSquared(wrist) < 1e-8f) return;
    var frame = frame(shoulder, wrist, left);
    boolean[] armBones = new boolean[avatar.bones.length];
    for (int i = 0; i < armBones.length; i++) {
      for (int b = i; b >= 0; b = avatar.bones[b].parent)
        if (b == upper) {
          armBones[i] = true;
          break;
        }
    }
    for (var mesh : avatar.meshes) {
      int[][] indices = select(mesh, armBones);
      if (Arrays.stream(indices).allMatch(i -> i.length == 0)) continue;
      float[] vertices = pose.skin(mesh, avatar.defaults);
      var point = new Vector3f();
      for (int v = 0; v < vertices.length; v += 6) {
        frame.transformPosition(point.set(vertices[v], vertices[v + 1], vertices[v + 2]));
        point.get(vertices, v);
        frame.transformDirection(point.set(vertices[v + 3], vertices[v + 4], vertices[v + 5]));
        point.normalize().get(vertices, v + 3);
      }
      parts.add(new Part(mesh, vertices, indices));
    }
  }

  public List<Part> parts() {
    return Collections.unmodifiableList(parts);
  }

  private static Vector3f reflectedPosition(Matrix4f matrix) {
    var p = matrix.getTranslation(new Vector3f());
    p.x = -p.x;
    return p;
  }

  /** Anatomical basis, with stable wrist roll; imported bone scale is deliberately excluded. */
  static Matrix4f frame(Vector3f shoulder, Vector3f wrist, boolean left) {
    var y = new Vector3f(wrist).sub(shoulder);
    float length = y.length();
    y.div(length);
    var forward = new Vector3f(0, 0, -1);
    if (Math.abs(y.dot(forward)) > .95f) forward.set(0, 1, 0);
    var x = new Vector3f(y).cross(forward).normalize();
    var z = new Vector3f(x).cross(y).normalize();
    var inverseBasis =
        new Matrix4f().set(x.x, y.x, z.x, 0, x.y, y.y, z.y, 0, x.z, y.z, z.z, 0, 0, 0, 0, 1);
    return new Matrix4f()
        .translation(left ? .0625f : -.0625f, .625f, 0)
        .mul(inverseBasis)
        .scale(.75f / length)
        .translate(-wrist.x, -wrist.y, -wrist.z);
  }

  static int[][] select(Avatar.Mesh mesh, boolean[] armBones) {
    float[] source = mesh.geometry.vertices();
    boolean[] selected = new boolean[mesh.geometry.vertexCount()];
    for (int v = 0; v < selected.length; v++) {
      float weight = 0;
      for (int k = 0; k < 4; k++)
        if (armBones[mesh.bones[(int) source[v * 16 + 8 + k]]]) weight += source[v * 16 + 12 + k];
      selected[v] = weight >= .5f;
    }
    int[][] result = new int[mesh.submeshes.length][];
    for (int sub = 0; sub < result.length; sub++) {
      int[] original = mesh.geometry.indices()[sub];
      int[] kept = new int[original.length];
      int count = 0;
      for (int t = 0; t < original.length; t += 3)
        if (selected[original[t]] && selected[original[t + 1]] && selected[original[t + 2]]) {
          kept[count++] = original[t];
          kept[count++] = original[t + 1];
          kept[count++] = original[t + 2];
        }
      result[sub] = Arrays.copyOf(kept, count);
    }
    return result;
  }
}

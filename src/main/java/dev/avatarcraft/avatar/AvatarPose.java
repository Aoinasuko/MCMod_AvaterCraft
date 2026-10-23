package dev.avatarcraft.avatar;

import java.util.*;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Humanoid retargeting, sparse multi-frame morphs, and four-influence skinning. */
public final class AvatarPose {
  public final Avatar avatar;
  public final Matrix4f[] world;
  private final Quaternionf[] restWorld, neutralWorld, localRotation;
  private final Vector3f[] localPosition;
  private final SpringSolver springs;
  private final MotionPlayer motions;

  public AvatarPose(Avatar a) {
    avatar = a;
    world = new Matrix4f[a.bones.length];
    restWorld = new Quaternionf[world.length];
    neutralWorld = new Quaternionf[world.length];
    localRotation = new Quaternionf[world.length];
    localPosition = new Vector3f[world.length];
    for (int i = 0; i < world.length; i++) {
      world[i] = new Matrix4f();
      localRotation[i] = new Quaternionf();
      localPosition[i] = new Vector3f();
    }
    reset();
    for (int i = 0; i < world.length; i++)
      restWorld[i] = world[i].getUnnormalizedRotation(new Quaternionf()).normalize();
    if (a.motions != null) {
      for (var entry : a.motions.neutralPose().entrySet()) {
        Integer bone = a.humanoid.get(entry.getKey());
        if (bone != null) {
          localRotation[bone].mul(entry.getValue().rotation());
          localPosition[bone].add(entry.getValue().position());
        }
      }
    } else {
      arm("LeftUpperArm", "LeftLowerArm", true, 0);
      arm("RightUpperArm", "RightLowerArm", false, 0);
    }
    for (int i = 0; i < world.length; i++) {
      update(i);
      neutralWorld[i] = world[i].getUnnormalizedRotation(new Quaternionf()).normalize();
    }
    reset();
    springs = new SpringSolver(a);
    motions = a.motions == null ? null : new MotionPlayer(a.motions);
  }

  private void reset() {
    for (int i = 0; i < world.length; i++) {
      var b = avatar.bones[i];
      localRotation[i].set(b.rotation[0], b.rotation[1], b.rotation[2], b.rotation[3]);
      localPosition[i].set(b.position[0], b.position[1], b.position[2]);
      update(i);
    }
  }

  private void update(int i) {
    var b = avatar.bones[i];
    world[i].translationRotateScale(
        localPosition[i].x,
        localPosition[i].y,
        localPosition[i].z,
        localRotation[i].x,
        localRotation[i].y,
        localRotation[i].z,
        localRotation[i].w,
        b.scale[0],
        b.scale[1],
        b.scale[2]);
    if (b.parent >= 0) new Matrix4f(world[b.parent]).mul(world[i], world[i]);
  }

  public void animate(
      float walk,
      float speed,
      float headYaw,
      float headPitch,
      boolean crouch,
      boolean swimming,
      float attack,
      boolean eating,
      double time,
      Vector3f movement) {
    animate(walk, speed, headYaw, headPitch, crouch, swimming, attack, eating, time, movement, 0);
  }

  public void animate(
      float walk,
      float speed,
      float headYaw,
      float headPitch,
      boolean crouch,
      boolean swimming,
      float attack,
      boolean eating,
      double time,
      Vector3f movement,
      float bodyYaw) {
    reset();
    float stride = (float) Math.cos(walk * .6662f) * Math.min(speed, 1) * .9f;
    delta(
        "Head",
        new Quaternionf()
            .rotateY((float) Math.toRadians(headYaw))
            .rotateX((float) Math.toRadians(headPitch)));
    delta("LeftUpperLeg", new Quaternionf().rotateX(stride));
    delta("RightUpperLeg", new Quaternionf().rotateX(-stride));
    arm("LeftUpperArm", "LeftLowerArm", true, stride * .7f - (eating ? 1.2f : 0));
    arm("RightUpperArm", "RightLowerArm", false, -stride * .7f - (eating ? 1.2f : attack * 1.4f));
    if (crouch) {
      delta("Spine", new Quaternionf().rotateX(.35f));
      delta("LeftUpperLeg", new Quaternionf().rotateX(-.35f));
      delta("RightUpperLeg", new Quaternionf().rotateX(-.35f));
    }
    if (swimming) delta("Hips", new Quaternionf().rotateX((float) -Math.PI / 2));
    for (int i = 0; i < world.length; i++) update(i);
    springs.update(world, time, movement, bodyYaw);
  }

  public Map<String, SpringSolver.Status> physicsStatus() {
    return springs.statuses();
  }

  public void animateMotion(MotionSignals input, double time, Vector3f movement, float bodyYaw) {
    if (motions == null) throw new IllegalStateException("This avatar has no baked motions");
    reset();
    for (var entry : motions.evaluate(input, time).entrySet()) {
      Integer bone = avatar.humanoid.get(entry.getKey());
      if (bone == null) continue;
      localRotation[bone].mul(entry.getValue().rotation()).normalize();
      localPosition[bone].add(entry.getValue().position());
    }
    if (!input.dead && !input.sleeping) {
      float yaw = (float) Math.toRadians(Math.clamp(input.headYaw, -75, 75));
      float pitch = (float) Math.toRadians(Math.clamp(input.headPitch, -55, 65));
      boolean neck = avatar.humanoid.containsKey("Neck");
      if (neck) delta("Neck", new Quaternionf().rotateY(yaw * .3f).rotateX(pitch * .3f));
      delta(
          "Head",
          new Quaternionf().rotateY(yaw * (neck ? .7f : 1)).rotateX(pitch * (neck ? .7f : 1)));
    }
    for (int i = 0; i < world.length; i++) update(i);
    springs.update(world, time, movement, bodyYaw);
  }

  public String motionAction() {
    return motions == null ? "legacy" : motions.baseAction();
  }

  public String overlayMotionAction() {
    return motions == null ? "legacy" : motions.overlayAction();
  }

  /** Items already use Minecraft units; imported FBX bone scale must not resize them. */
  public Matrix4f attachmentFrame(int bone, float modelScale) {
    return new Matrix4f()
        .translationRotateScale(
            world[bone].getTranslation(new Vector3f()),
            world[bone]
                .getUnnormalizedRotation(new Quaternionf())
                .normalize()
                .mul(new Quaternionf(neutralWorld[bone]).conjugate())
                .rotateX((float) Math.PI),
            1 / modelScale);
  }

  private void delta(String name, Quaternionf rotation) {
    Integer i = avatar.humanoid.get(name);
    if (i == null) return;
    var localDelta = new Quaternionf(restWorld[i]).conjugate().mul(rotation).mul(restWorld[i]);
    localRotation[i].mul(localDelta).normalize();
  }

  private void arm(String name, String child, boolean left, float swing) {
    Integer i = avatar.humanoid.get(name), c = avatar.humanoid.get(child);
    if (i == null || c == null) return;
    var dir = world[c].getTranslation(new Vector3f()).sub(world[i].getTranslation(new Vector3f()));
    if (dir.lengthSquared() < 1e-8) return;
    var target = new Vector3f(left ? .08f : -.08f, -1, 0).normalize();
    var lower = new Quaternionf().rotationTo(dir.normalize(), target);
    delta(name, new Quaternionf().rotateX(swing).mul(lower));
  }

  public float[] skin(Avatar.Mesh mesh, Map<String, Float> weights) {
    var data = mesh.geometry;
    int n = data.vertexCount();
    var positions = new float[n * 6];
    var source = data.vertices();
    for (int v = 0; v < n; v++) {
      System.arraycopy(source, v * 16, positions, v * 6, 6);
    }
    for (var morph : data.morphs()) {
      float weight = weights.getOrDefault(mesh.name + ":" + morph.name(), 0f);
      if (weight == 0 || morph.frames().isEmpty()) continue;
      Avatar.MorphFrame lower = null, upper = morph.frames().getLast();
      for (var f : morph.frames()) {
        if (weight <= f.weight()) {
          upper = f;
          break;
        }
        lower = f;
      }
      if (lower == upper) {
        apply(positions, upper, weight / upper.weight());
        continue;
      }
      float t =
          (weight - (lower == null ? 0 : lower.weight()))
              / (upper.weight() - (lower == null ? 0 : lower.weight()));
      if (lower != null) apply(positions, lower, 1 - t);
      apply(positions, upper, t);
    }
    var matrices = new Matrix4f[mesh.bones.length];
    var normals = new Matrix3f[matrices.length];
    for (int i = 0; i < matrices.length; i++) {
      matrices[i] = new Matrix4f(world[mesh.bones[i]]).mul(new Matrix4f().set(mesh.bindPoses[i]));
      normals[i] = matrices[i].normal(new Matrix3f());
    }
    var result = new float[n * 6];
    var p = new Vector3f();
    var norm = new Vector3f();
    var skinned = new Vector3f();
    var sn = new Vector3f();
    var temp = new Vector3f();
    for (int v = 0; v < n; v++) {
      p.set(positions[v * 6], positions[v * 6 + 1], positions[v * 6 + 2]);
      norm.set(positions[v * 6 + 3], positions[v * 6 + 4], positions[v * 6 + 5]);
      skinned.zero();
      sn.zero();
      for (int k = 0; k < 4; k++) {
        float w = source[v * 16 + 12 + k];
        if (w == 0) continue;
        int b = (int) source[v * 16 + 8 + k];
        matrices[b].transformPosition(p, temp);
        skinned.fma(w, temp);
        normals[b].transform(norm, temp);
        sn.fma(w, temp);
      }
      if (sn.lengthSquared() > 1e-8) sn.normalize();
      else sn.set(0, 1, 0);
      result[v * 6] = -skinned.x;
      result[v * 6 + 1] = skinned.y;
      result[v * 6 + 2] = skinned.z;
      result[v * 6 + 3] = -sn.x;
      result[v * 6 + 4] = sn.y;
      result[v * 6 + 5] = sn.z;
    }
    return result;
  }

  private static void apply(float[] out, Avatar.MorphFrame f, float amount) {
    for (int j = 0; j < f.indices().length; j++) {
      int dst = f.indices()[j] * 6;
      for (int k = 0; k < 6; k++) out[dst + k] += f.deltas()[j * 6 + k] * amount;
    }
  }
}

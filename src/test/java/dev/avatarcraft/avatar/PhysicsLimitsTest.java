package dev.avatarcraft.avatar;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.*;

class PhysicsLimitsTest {
  private static float degrees(Vector3f a, Vector3f b) {
    return (float)
        Math.toDegrees(
            Math.acos(
                Math.clamp(new Vector3f(a).normalize().dot(new Vector3f(b).normalize()), -1, 1)));
  }

  @Test
  void coneHonorsFiveDegreeHairLimitEvenForOppositeInput() {
    for (var desired :
        List.of(new Vector3f(1, 0, 0), new Vector3f(0, -1, 0), new Vector3f(0, 0, -1))) {
      var result = PhysicsLimits.direction(desired, new Quaternionf(), "Angle", 5, 45, null);
      assertTrue(degrees(new Vector3f(0, 1, 0), result) <= 5.001f);
      assertEquals(1, result.length(), 1e-6);
    }
  }

  @Test
  void rotatedLimitAxisUsesAuthoredQuaternion() {
    var basis = new Quaternionf().rotateZ((float) Math.PI / 2);
    var center = basis.transform(new Vector3f(0, 1, 0));
    var result = PhysicsLimits.direction(new Vector3f(0, 1, 0), basis, "Angle", 10, 45, null);
    assertEquals(10, degrees(center, result), .002);
  }

  @Test
  void hingeRemovesMotionOutsideItsPlane() {
    var result =
        PhysicsLimits.direction(new Vector3f(2, -1, 3), new Quaternionf(), "Hinge", 20, 180, null);
    assertEquals(0, result.x, 1e-6);
    assertTrue(degrees(new Vector3f(0, 1, 0), result) <= 20.001);
  }

  @Test
  void polarClampsEachAxisIndependently() {
    var result =
        PhysicsLimits.direction(new Vector3f(1, -1, 2), new Quaternionf(), "Polar", 10, 25, null);
    float pitch =
        (float)
            Math.toDegrees(
                Math.atan2(result.z, Math.sqrt(result.x * result.x + result.y * result.y)));
    float yaw = (float) Math.toDegrees(Math.atan2(result.x, result.y));
    assertTrue(Math.abs(pitch) <= 10.001);
    assertTrue(Math.abs(yaw) <= 25.001);
  }

  @Test
  void lengthCannotExceedStretchAndSquishBounds() {
    var n = new Avatar.SpringNode();
    n.maxStretch = .25f;
    n.maxSquish = .1f;
    var anchor = new Vector3f();
    var rest = new Vector3f(0, 2, 0);
    var p = new Vector3f(10, 10, 10);
    PhysicsLimits.tail(p, anchor, rest, new Quaternionf(), n, "None", null);
    assertEquals(2.5, p.length(), 1e-5);
    p.set(0, .01f, 0);
    PhysicsLimits.tail(p, anchor, rest, new Quaternionf(), n, "None", null);
    assertEquals(1.8, p.length(), 1e-5);
  }

  @Test
  void zeroStretchAndSquishKeepOriginalLength() {
    var n = new Avatar.SpringNode();
    var p = new Vector3f(0, 50, 0);
    PhysicsLimits.tail(
        p, new Vector3f(), new Vector3f(0, 1, 0), new Quaternionf(), n, "Angle", null);
    assertEquals(1, p.length(), 1e-6);
  }

  private Avatar avatar() {
    var a = new Avatar();
    a.formatVersion = 1;
    a.id = "physics";
    a.name = "Physics";
    a.height = 1.8f;
    a.bones = new Avatar.Bone[3];
    for (int i = 0; i < 3; i++) {
      var b = new Avatar.Bone();
      b.name = "b" + i;
      b.parent = i - 1;
      b.position = new float[] {0, i == 0 ? 0 : 1, 0};
      b.rotation = new float[] {0, 0, 0, 1};
      b.scale = new float[] {1, 1, 1};
      a.bones[i] = b;
    }
    a.meshes = new Avatar.Mesh[0];
    a.materials = new Avatar.Material[0];
    var s = new Avatar.Spring();
    s.name = "hair";
    s.root = 1;
    s.bones = new int[] {1, 2};
    s.colliders = new int[0];
    s.endpoint = new float[3];
    s.limitType = "Angle";
    var n = new Avatar.SpringNode();
    n.bone = 1;
    n.tail = new float[] {0, 1, 0};
    n.maxAngleX = 5;
    n.pull = .2f;
    n.spring = .2f;
    s.nodes = new Avatar.SpringNode[] {n};
    a.springs = new Avatar.Spring[] {s};
    return a;
  }

  private Matrix4f[] rest(Avatar a) {
    var w = new Matrix4f[a.bones.length];
    for (int i = 0; i < w.length; i++) {
      var b = a.bones[i];
      w[i] = new Matrix4f().translation(b.position[0], b.position[1], b.position[2]);
      if (b.parent >= 0) new Matrix4f(w[b.parent]).mul(w[i], w[i]);
    }
    return w;
  }

  @Test
  void largeLocomotionNeverExceedsJointLimitOrLength() {
    var a = avatar();
    var solver = new SpringSolver(a);
    for (int frame = 0; frame < 300; frame++) {
      var w = rest(a);
      solver.update(
          w, frame / 60d, new Vector3f((float) Math.sin(frame * .2) * 2, 0, 0), frame * .5f);
      var direction = w[2].getTranslation(new Vector3f()).sub(w[1].getTranslation(new Vector3f()));
      assertTrue(degrees(new Vector3f(0, 1, 0), direction) <= 5.002f, "Angle at frame " + frame);
      assertEquals(1, direction.length(), 1e-4);
    }
  }

  @Test
  void disabledComponentCannotMoveAnyBones() {
    var a = avatar();
    a.springs[0].enabled = false;
    var solver = new SpringSolver(a);
    assertEquals(0, solver.jointCount());
    var w = rest(a);
    solver.update(w, 0, new Vector3f());
    solver.update(w, .02, new Vector3f(2, 0, 0));
    assertEquals(1, w[1].m31(), 1e-6);
  }

  @Test
  void teleportResetsWithoutNaNsOrResidualBend() {
    var a = avatar();
    var solver = new SpringSolver(a);
    solver.update(rest(a), 0, new Vector3f());
    var w = rest(a);
    solver.update(w, 1, new Vector3f(1000, 1000, 1000), 180);
    assertTrue(w[1].isFinite());
    assertEquals(
        0, degrees(new Vector3f(0, 1, 0), w[1].transformDirection(new Vector3f(0, 1, 0))), 1e-3);
  }

  @Test
  void duplicateOwnershipCannotDoubleSimulateJoint() {
    var a = avatar();
    a.springs = new Avatar.Spring[] {a.springs[0], a.springs[0]};
    assertEquals(1, new SpringSolver(a).jointCount());
  }

  @Test
  void configuredExportCarriesFiniteLimitsAndUniqueScopeMembers() throws Exception {
    var a = AvatarArchive.read(TestAvatarFixture.path()).avatar();
    assertEquals(2, a.physicsVersion);
    assertTrue(a.springs.length > 0, "Fixture must include PhysBone settings");
    for (var s : a.springs) {
      assertTrue(s.curves.has("maxAngleXCurve"));
      assertTrue(s.settings.has("version"));
      assertTrue(Float.isFinite(s.maxAngleX) && s.maxAngleX >= 0 && s.maxAngleX <= 180);
      assertTrue(Float.isFinite(s.maxStretch) && s.maxStretch >= 0);
      assertTrue(Float.isFinite(s.maxSquish) && s.maxSquish >= 0 && s.maxSquish <= 1);
      Set<Integer> seen = new HashSet<>();
      for (int b : s.bones) assertTrue(seen.add(b), "Duplicate ownership of " + b);
    }
  }
}

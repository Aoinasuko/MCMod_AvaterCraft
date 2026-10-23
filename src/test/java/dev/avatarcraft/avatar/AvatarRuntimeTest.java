package dev.avatarcraft.avatar;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class AvatarRuntimeTest {
  @TempDir Path temp;

  private Avatar fixture() {
    var a = new Avatar();
    a.formatVersion = 1;
    a.id = "test";
    a.name = "Test";
    var b = new Avatar.Bone();
    b.name = "root";
    b.parent = -1;
    b.position = new float[] {0, 2, 0};
    b.rotation = new float[] {0, 0, 0, 1};
    b.scale = new float[] {1, 1, 1};
    a.bones = new Avatar.Bone[] {b};
    var m = new Avatar.Mesh();
    m.name = "face";
    m.data = "meshes/0.bin";
    m.bones = new int[] {0};
    m.bindPoses = new float[][] {new Matrix4f().translation(0, -2, 0).get(new float[16])};
    var sub = new Avatar.Submesh();
    sub.material = -1;
    m.submeshes = new Avatar.Submesh[] {sub};
    float[] vertices = {1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0};
    var f1 = new Avatar.MorphFrame(50, new int[] {0}, new float[] {0, 1, 0, 0, 0, 0});
    var f2 = new Avatar.MorphFrame(100, new int[] {0}, new float[] {0, 3, 0, 0, 0, 0});
    m.geometry =
        new Avatar.MeshData(
            vertices, new int[][] {{0, 0, 0}}, List.of(new Avatar.Morph("blink", List.of(f1, f2))));
    a.meshes = new Avatar.Mesh[] {m};
    a.materials = new Avatar.Material[0];
    return a;
  }

  @Test
  void itemAttachmentKeepsHandPositionWithoutImportedBoneOrAvatarScale() {
    var a = fixture();
    a.bones[0].scale = new float[] {100, 100, 100};
    var pose = new AvatarPose(a);
    pose.world[0].rotateY(.7f);
    var frame = pose.attachmentFrame(0, 2);
    assertEquals(
        pose.world[0].getTranslation(new Vector3f()), frame.getTranslation(new Vector3f()));
    var inGame = new Matrix4f().scale(2).mul(frame);
    assertEquals(1, inGame.transformDirection(new Vector3f(1, 0, 0)).length(), .0001);
    var boneDirection = pose.world[0].transformDirection(new Vector3f(0, 0, -1)).normalize();
    assertTrue(
        boneDirection.equals(frame.transformDirection(new Vector3f(0, 0, 1)).normalize(), .0001f));
  }

  @Test
  void neutralAttachmentMatchesMinecraftDownwardHandBasis() throws Exception {
    var path = TestAvatarFixture.path();
    var a = AvatarArchive.read(path).avatar();
    var pose = new AvatarPose(a);
    pose.animateMotion(new MotionSignals(), 0, new Vector3f(), 0);
    int hand = a.humanoid.get("RightHand");
    // Idle adds a small elbow bend; source FBX wrist roll must not appear in the grip frame.
    var down = pose.attachmentFrame(hand, 1).transformDirection(new Vector3f(0, 1, 0)).normalize();
    assertTrue(down.dot(new Vector3f(0, -1, 0)) > .95f);
  }

  @Test
  void restSkinningCancelsBindPoseAndConvertsHandedness() {
    var a = fixture();
    var p = new AvatarPose(a);
    assertArrayEquals(new float[] {-1, 0, 0, 0, 1, 0}, p.skin(a.meshes[0], Map.of()), 1e-5f);
  }

  @Test
  void sparseMultiFrameMorphInterpolatesAroundAuthoredFrames() {
    var a = fixture();
    var p = new AvatarPose(a);
    assertEquals(.5f, p.skin(a.meshes[0], Map.of("face:blink", 25f))[1], 1e-5);
    assertEquals(2f, p.skin(a.meshes[0], Map.of("face:blink", 75f))[1], 1e-5);
    assertEquals(3f, p.skin(a.meshes[0], Map.of("face:blink", 100f))[1], 1e-5);
  }

  @Test
  void blinkWaitsTwoToFourSecondsAndHigherPriorityWins() {
    var c = new ExpressionConfig();
    c.events.get("blink").put("face:blink", 100f);
    c.events.get("eat").put("face:blink", 30f);
    c.events.get("hurt").put("face:blink", 75f);
    var expectedRandom = new Random(42);
    double first = 2 + expectedRandom.nextDouble() * 2;
    var p = new ExpressionPlayer(new Random(42));
    assertEquals(
        0f, p.evaluate(c, Map.of("face:blink", 0f), 0, false, false, null).get("face:blink"));
    assertEquals(
        0f,
        p.evaluate(c, Map.of("face:blink", 0f), first - .01, false, false, null).get("face:blink"));
    assertEquals(
        100f,
        p.evaluate(c, Map.of("face:blink", 0f), first + .08, false, false, null).get("face:blink"),
        .001);
    assertEquals(75f, p.evaluate(c, Map.of(), first + .08, true, true, null).get("face:blink"));
    assertEquals(30f, p.evaluate(c, Map.of(), first + .08, true, true, "eat").get("face:blink"));
  }

  @Test
  void expressionRejectsUnknownShapesNonFiniteWeightsAndReversedTiming() {
    var a = fixture();
    var c = new ExpressionConfig();
    c.events.get("hurt").put("unknown", 20f);
    assertThrows(IOException.class, () -> c.validate(a));
    c.events.get("hurt").clear();
    c.events.get("blink").put("face:blink", Float.NaN);
    assertThrows(IOException.class, () -> c.validate(a));
    c.events.get("blink").clear();
    c.blinkMinSeconds = 5;
    assertThrows(IOException.class, () -> c.validate(a));
  }

  @Test
  void skeletonRejectsForwardParentAndZeroScale() {
    var a = fixture();
    a.bones[0].parent = 0;
    assertThrows(IOException.class, () -> AvatarArchive.validate(a));
    a.bones[0].parent = -1;
    a.bones[0].scale[0] = 0;
    assertThrows(IOException.class, () -> AvatarArchive.validate(a));
  }

  @Test
  void rejectsTruncatedMeshAndNaN() {
    assertThrows(IOException.class, () -> AvatarArchive.mesh(new byte[7]));
    var b = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(0x31435641).putInt(1).putFloat(Float.NaN);
    assertThrows(IOException.class, () -> AvatarArchive.mesh(b.array()));
  }

  @Test
  void refusesZipTraversalBeforeParsingManifest() throws Exception {
    var p = temp.resolve("bad.avc");
    try (var z = new ZipOutputStream(Files.newOutputStream(p))) {
      z.putNextEntry(new ZipEntry("../outside"));
      z.write(1);
      z.closeEntry();
    }
    assertThrows(IOException.class, () -> AvatarArchive.read(p));
    assertFalse(Files.exists(temp.getParent().resolve("outside")));
  }

  @Test
  void rejectsWrongFormatVersion() {
    var a = fixture();
    a.formatVersion = 900;
    assertThrows(IOException.class, () -> AvatarArchive.validate(a));
  }

  @Test
  void configuredUnityExportLoadsAndSkinsWithoutNonFiniteVertices() throws Exception {
    var path = TestAvatarFixture.path();
    var archive = AvatarArchive.read(path);
    var a = archive.avatar();
    assertTrue(a.meshes.length > 0);
    assertTrue(a.humanoid.containsKey("Head"));
    var pose = new AvatarPose(a);
    pose.animate(1, .5f, 20, 10, false, false, 0, false, 0, new Vector3f());
    for (var m : a.meshes) {
      for (float f : pose.skin(m, a.defaults)) assertTrue(Float.isFinite(f));
    }
    System.out.printf(
        "Unity export: %d meshes, %d bones, %d springs, %d colliders%n",
        a.meshes.length, a.bones.length, a.springs.length, a.colliders.length);
  }
}

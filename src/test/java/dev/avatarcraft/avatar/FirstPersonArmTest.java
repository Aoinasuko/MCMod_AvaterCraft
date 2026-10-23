package dev.avatarcraft.avatar;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.joml.Vector3f;
import org.junit.jupiter.api.*;

class FirstPersonArmTest {
  @Test
  void wristAndShoulderFitMinecraftArmWithoutImportedScaleOrRoll() {
    for (float importScale : new float[] {.01f, 1, 100})
      for (boolean left : new boolean[] {false, true}) {
        var shoulder = new Vector3f(.2f, 1.2f, .1f).mul(importScale);
        var wrist = new Vector3f(.35f, .8f, .2f).mul(importScale);
        var frame = FirstPersonArm.frame(shoulder, wrist, left);
        var target = new Vector3f(left ? .0625f : -.0625f, .625f, 0);
        assertTrue(target.equals(frame.transformPosition(new Vector3f(wrist)), .0001f));
        assertTrue(
            new Vector3f(target)
                .add(0, -.75f, 0)
                .equals(frame.transformPosition(new Vector3f(shoulder)), .0001f));
        assertTrue(frame.determinant3x3() > 0, "Do not reverse winding a second time");
        var normal = frame.transformDirection(new Vector3f(1, 0, 0)).normalize();
        assertTrue(normal.isFinite());
      }
  }

  @Test
  void trianglesMustBelongToTheSameArmAtEveryVertex() {
    var mesh = mesh();
    float[] vertices = mesh.geometry.vertices();
    // Vertex 0: torso, 1: hand, 2: mixed shoulder, 3: finger, 4: opposite hand.
    vertices[16 + 8] = 1;
    vertices[32 + 8] = 1;
    vertices[32 + 9] = 0;
    vertices[32 + 12] = .6f;
    vertices[32 + 13] = .4f;
    vertices[48 + 8] = 2;
    vertices[64 + 8] = 3;
    assertArrayEquals(
        new int[] {1, 2, 3},
        FirstPersonArm.select(mesh, new boolean[] {false, true, true, false})[0]);
    vertices[32 + 12] = .4f;
    vertices[32 + 13] = .6f;
    assertEquals(
        0, FirstPersonArm.select(mesh, new boolean[] {false, true, true, false})[0].length);
  }

  private Avatar.Mesh mesh() {
    var m = new Avatar.Mesh();
    m.bones = new int[] {0, 1, 2, 3};
    m.submeshes = new Avatar.Submesh[] {new Avatar.Submesh()};
    float[] vertices = new float[5 * 16];
    for (int i = 0; i < 5; i++) vertices[i * 16 + 12] = 1;
    m.geometry =
        new Avatar.MeshData(vertices, new int[][] {{1, 2, 3, 0, 1, 2, 1, 3, 4}}, List.of());
    return m;
  }

  @Test
  void missingArmMappingLeavesVanillaFallbackAvailable() {
    assertTrue(new FirstPersonArm(new Avatar(), false).parts().isEmpty());
  }

  @Test
  void legacyArchivesWithoutBakedMotionsCanSupplyArms() throws Exception {
    var path = TestAvatarFixture.path();
    var avatar = AvatarArchive.read(path).avatar();
    avatar.motions = null;
    assertFalse(new FirstPersonArm(avatar, false).parts().isEmpty());
  }

  @Test
  void exportedAvatarSuppliesBothArmsAndExcludesHeadAndOutfitBody() throws Exception {
    var path = TestAvatarFixture.path();
    var avatar = AvatarArchive.read(path).avatar();
    for (boolean left : new boolean[] {false, true}) {
      var arm = new FirstPersonArm(avatar, left);
      assertFalse(arm.parts().isEmpty());
      int triangles = 0;
      for (var part : arm.parts()) {
        for (var indices : part.indices()) {
          triangles += indices.length / 3;
          for (int index : indices) {
            int v = index * 6;
            var p =
                new Vector3f(part.vertices()[v], part.vertices()[v + 1], part.vertices()[v + 2]);
            assertTrue(p.isFinite());
            assertTrue(
                Math.abs(p.x) < .4f && Math.abs(p.z) < .4f, "Torso/head must not leak into view");
            assertTrue(p.y < 1.1f && p.y > -.4f);
            assertEquals(
                1,
                new Vector3f(part.vertices()[v + 3], part.vertices()[v + 4], part.vertices()[v + 5])
                    .length(),
                .0001);
          }
        }
      }
      assertTrue(triangles > 100);
      System.out.println((left ? "left" : "right") + " first-person triangles: " + triangles);
    }
  }
}

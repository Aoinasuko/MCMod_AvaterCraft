package dev.avatarcraft.api;

import static org.junit.jupiter.api.Assertions.*;

import dev.avatarcraft.avatar.*;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class AvatarEquipmentPoseTest {
  @Test
  void helmetUsesTheHeadSurfaceInsteadOfAnImportedPivotAboveTheFace() {
    var avatar = new Avatar();
    avatar.height = 1.8f;
    var head = new Avatar.Bone();
    head.parent = -1;
    head.name = "Head";
    head.position = new float[] {0, 1.2f, 0};
    head.rotation = new float[] {0, 0, 0, 1};
    head.scale = new float[] {1, 1, 1};
    avatar.bones = new Avatar.Bone[] {head};
    avatar.humanoid.put("Head", 0);
    var mesh = new Avatar.Mesh();
    mesh.name = "Body";
    mesh.bones = new int[] {0};
    mesh.bindPoses = new float[][] {new org.joml.Matrix4f().get(new float[16])};
    float[] vertices = new float[48];
    float[][] points = {{-.25f, -.3f, -.15f}, {.25f, -.3f, .15f}, {0, 0, 0}};
    for (int i = 0; i < 3; i++) {
      System.arraycopy(points[i], 0, vertices, i * 16, 3);
      vertices[i * 16 + 4] = 1;
      vertices[i * 16 + 12] = 1;
    }
    mesh.geometry = new Avatar.MeshData(vertices, new int[][] {{0, 1, 2}}, java.util.List.of());
    avatar.meshes = new Avatar.Mesh[] {mesh};
    var archive = new AvatarArchive(avatar, java.util.Map.of(), new ExpressionConfig());
    var fit = AvatarHeadFit.measure(archive).orElseThrow();
    assertEquals(.9f, fit.position(new AvatarPose(avatar)).y, .00001f);
    assertEquals(.3f, fit.size().y, .00001f);
    assertEquals(.5f, fit.size().x, .00001f);
  }

  @Test
  void importedHeadPivotMapsBackToTheSameWorldPositionAsTheSkinnedAvatar() {
    var avatar = new Avatar();
    avatar.height = 1.8f;
    avatar.feetOffset = .1f;
    var head = new Avatar.Bone();
    head.parent = -1;
    head.name = "Head";
    head.position = new float[] {.2f, 1.2f, .3f};
    head.rotation = new float[] {0, 0, 0, 1};
    head.scale = new float[] {1, 1, 1};
    avatar.bones = new Avatar.Bone[] {head};
    avatar.humanoid.put("Head", 0);
    var pose = new AvatarPose(avatar);
    var part = AvatarEquipmentPose.part(pose, "Head").orElseThrow();
    var model = part.position();
    var world = new Vector3f(model.x / 16, 1.501f - model.y / 16, -model.z / 16);
    assertEquals(-.2f, world.x, .00001f);
    assertEquals(1.1f, world.y, .00001f);
    assertEquals(.3f, world.z, .00001f);
    avatar.height = .9f;
    part = AvatarEquipmentPose.part(pose, "Head").orElseThrow();
    assertEquals(2.2f, 1.501f - part.position().y / 16, .00001f);
    assertTrue(AvatarEquipmentPose.part(pose, "Missing").isEmpty());
  }
}

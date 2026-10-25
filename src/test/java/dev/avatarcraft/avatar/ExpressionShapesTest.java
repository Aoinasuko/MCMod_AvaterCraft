package dev.avatarcraft.avatar;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ExpressionShapesTest {
  @Test void mergedBodyMeshesGroupOnlyHeadInfluencedMorphsFirst() {
    var avatar = new Avatar();
    var root = new Avatar.Bone(); root.parent = -1;
    var head = new Avatar.Bone(); head.parent = 0;
    var eye = new Avatar.Bone(); eye.parent = 1;
    avatar.bones = new Avatar.Bone[] {root, head, eye}; avatar.humanoid.put("Head", 1);
    var mesh = new Avatar.Mesh(); mesh.name = "Body"; mesh.bones = new int[] {0, 1, 2};
    var vertices = new float[3 * 16];
    vertices[8] = 0; vertices[12] = 1;
    vertices[16 + 8] = 1; vertices[16 + 12] = 1;
    vertices[32 + 8] = 2; vertices[32 + 12] = 1;
    mesh.geometry = new Avatar.MeshData(vertices, new int[0][], List.of(morph("waist", 0), morph("smile", 1), morph("blink", 2)));
    avatar.meshes = new Avatar.Mesh[] {mesh};
    var collected = ExpressionShapes.collect(avatar);
    assertEquals(List.of("blink", "smile", "waist"), collected.stream().map(ExpressionShapes.Shape::name).toList());
    assertTrue(collected.get(0).head()); assertTrue(collected.get(1).head()); assertFalse(collected.get(2).head());
  }
  private Avatar.Morph morph(String name, int vertex) {
    return new Avatar.Morph(name, List.of(new Avatar.MorphFrame(100, new int[] {vertex}, new float[] {.1f, 0, 0, 0, 0, 0})));
  }
  @Test void bundledTortobuFaceGroupsExcludeNailControls() throws Exception {
    var archive = AvatarArchive.read(java.nio.file.Path.of("src/main/resources/assets/bep_3davater_loader/avatars/tortobu.avc"));
    var shapes = ExpressionShapes.collect(archive.avatar());
    assertEquals(archive.expressions().shapes(archive.avatar()).size(), shapes.size());
    assertTrue(shapes.stream().anyMatch(s -> s.head() && s.name().equals("lid_全閉じ_L")));
    assertTrue(shapes.stream().anyMatch(s -> s.head() && s.name().equals("v_aa")));
    assertTrue(shapes.stream().anyMatch(s -> !s.head() && s.name().toLowerCase(Locale.ROOT).contains("nail")));
  }
}

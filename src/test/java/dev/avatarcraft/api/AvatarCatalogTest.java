package dev.avatarcraft.api;

import static org.junit.jupiter.api.Assertions.*;

import dev.avatarcraft.avatar.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AvatarCatalogTest {
  @TempDir Path directory;

  private void fixture(String filename, String id) throws Exception {
    var avatar = new Avatar();
    avatar.formatVersion = 1;
    avatar.id = id;
    avatar.name = "Fixture";
    var bone = new Avatar.Bone();
    bone.name = "Root";
    bone.parent = -1;
    bone.position = new float[] {0, 0, 0};
    bone.rotation = new float[] {0, 0, 0, 1};
    bone.scale = new float[] {1, 1, 1};
    avatar.bones = new Avatar.Bone[] {bone};
    avatar.meshes = new Avatar.Mesh[0];
    avatar.materials = new Avatar.Material[0];
    try (var zip = new ZipOutputStream(Files.newOutputStream(directory.resolve(filename)))) {
      zip.putNextEntry(new ZipEntry("avatar.json"));
      zip.write(AvatarArchive.GSON.toJson(avatar).getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
  }

  @Test
  void resolvesCanonicalIdsAndFilenameAliasesFromOneSnapshot() throws Exception {
    fixture("companion.avc", "model_123");
    var catalog = new AvatarCatalog(directory);
    catalog.reload();
    assertSame(catalog.find("model_123").orElseThrow(), catalog.find("companion").orElseThrow());
    assertEquals(1, catalog.entries().size());
    assertTrue(catalog.problems().isEmpty());
    assertTrue(catalog.find("../model_123").isEmpty());
    assertTrue(catalog.find(null).isEmpty());
  }

  @Test
  void canonicalIdentityTakesPriorityOverAnotherFilesAlias() throws Exception {
    fixture("first.avc", "second");
    fixture("second.avc", "third");
    var catalog = new AvatarCatalog(directory);
    catalog.reload();
    assertEquals("second", catalog.find("second").orElseThrow().archive().avatar().id);
    assertEquals(2, catalog.entries().size());
    assertFalse(catalog.problems().isEmpty());
  }

  @Test
  void failedArchiveDoesNotPreventOtherActorsFromLoading() throws Exception {
    fixture("valid.avc", "valid_id");
    Files.writeString(directory.resolve("broken.avc"), "bad archive");
    var catalog = new AvatarCatalog(directory);
    catalog.reload();
    assertTrue(catalog.find("valid_id").isPresent());
    assertEquals(1, catalog.problems().size());
    Files.delete(directory.resolve("valid.avc"));
    catalog.reload();
    assertTrue(catalog.find("valid_id").isEmpty());
  }

  @Test
  void separateActorsKeepTheirOwnPoseAndExpressionClock() throws Exception {
    fixture("actor.avc", "actor");
    var archive = AvatarArchive.read(directory.resolve("actor.avc"));
    var first = new AvatarAnimation(archive, 1);
    var second = new AvatarAnimation(archive, 2);
    first.pose.world[0].translate(1, 0, 0);
    assertEquals(0, second.pose.world[0].m30());
    assertSame(first.archive, second.archive);
    assertNotSame(first.pose, second.pose);
  }
}

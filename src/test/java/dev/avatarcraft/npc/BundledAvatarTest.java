package dev.avatarcraft.npc;

import static org.junit.jupiter.api.Assertions.*;

import dev.avatarcraft.avatar.AvatarArchive;
import dev.avatarcraft.api.BundledAvatar;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BundledAvatarTest {
  @TempDir Path root;

  @Test
  void standardModelLoadsAndNeverOverwritesTheUsersExternalFile() throws Exception {
    BundledAvatar.install(root);
    var file = root.resolve("tortobu.avc");
    var archive = AvatarArchive.read(file);
    assertEquals("tortobu", archive.avatar().name);
    assertTrue(archive.avatar().meshes.length > 0);
    assertTrue(archive.avatar().motions != null);
    Files.writeString(file, "user replacement");
    BundledAvatar.install(root);
    assertEquals("user replacement", Files.readString(file));
    try (var files = Files.list(root)) {
      assertEquals(1, files.count());
    }
  }
}

package dev.avatarcraft.npc;

import java.io.*;
import java.nio.file.*;

/** Installs the authorized standard model without replacing an avatar supplied by the user. */
public final class BundledAvatar {
  public static void install(Path avatars) throws IOException {
    Files.createDirectories(avatars);
    Path target = avatars.resolve("tortobu.avc");
    if (Files.exists(target)) return;
    var temp = Files.createTempFile(avatars, ".tortobu-", ".tmp");
    try (var source =
        BundledAvatar.class.getResourceAsStream("/assets/avatarcraftnpc/avatars/tortobu.avc")) {
      if (source == null) throw new IOException("Bundled tortobu is missing");
      Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
      try {
        Files.move(temp, target);
      } catch (FileAlreadyExistsException ignored) {
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private BundledAvatar() {}
}

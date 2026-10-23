package dev.avatarcraft.avatar;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

/** Optional integration fixture supplied by the developer; never bundled with the repository. */
final class TestAvatarFixture {
  private TestAvatarFixture() {}

  static Path path() {
    String configured = System.getenv("AVATARCRAFT_TEST_AVATAR");
    assumeTrue(
        configured != null && !configured.isBlank(),
        "Set AVATARCRAFT_TEST_AVATAR to run exported-avatar integration checks");
    var path = Path.of(configured);
    assertTrue(Files.isRegularFile(path), "Configured avatar fixture must be a readable .avc file");
    return path;
  }
}

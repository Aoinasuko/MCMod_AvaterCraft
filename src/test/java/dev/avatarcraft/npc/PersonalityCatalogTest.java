package dev.avatarcraft.npc;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersonalityCatalogTest {
  @TempDir Path root;

  @Test
  void externalProfilesAreIndependentOfTheStandardModelAndInvalidFilesAreIgnored()
      throws Exception {
    var custom = Personality.preset("tortobu");
    custom.id = "custom";
    custom.name = "Custom";
    custom.avatar = "external-model";
    Files.writeString(root.resolve("custom.json"), custom.json());
    Files.writeString(root.resolve("broken.json"), "{broken");
    Files.writeString(root.resolve("wrong-name.json"), custom.json());
    var profiles = PersonalityCatalog.load(root);
    assertEquals(2, profiles.size());
    assertEquals("tortobu", profiles.getFirst().id);
    assertEquals("external-model", profiles.get(1).avatar);
  }
}

package dev.avatarcraft.npc;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PersonalityTest {
  @Test
  void standardCharacterUsesOnlyTheRedistributableAvatar() {
    var standard = Personality.preset("tortobu");
    assertEquals("tortobu", standard.avatar);
    assertEquals(60, standard.workSpeechSeconds);
    assertEquals(standard.instructions, Personality.parse(standard.json()).instructions);
    assertThrows(IllegalArgumentException.class, () -> Personality.preset("clonka"));
    assertThrows(IllegalArgumentException.class, () -> Personality.preset("yilnel"));
  }

  @Test
  void serverRejectsOutOfBoundsSkillsAndOversizedPersonalities() {
    var personality = Personality.preset("tortobu");
    personality.allowedSkills.add("operator_command");
    assertThrows(IllegalArgumentException.class, personality::json);
    assertThrows(IllegalArgumentException.class, () -> Personality.parse(" ".repeat(16385)));
    assertThrows(IllegalArgumentException.class, () -> Personality.parse("{\"name\":null}"));
  }

  @Test
  void avatarReferencesCannotEscapeCatalogAndSpeechIntervalCannotBeDisabled() {
    var personality = Personality.preset("tortobu");
    personality.avatar = "../../outside";
    assertThrows(IllegalArgumentException.class, personality::validate);
    personality.avatar = "custom_avatar";
    personality.workSpeechSeconds = 0;
    assertThrows(IllegalArgumentException.class, personality::validate);
  }
}

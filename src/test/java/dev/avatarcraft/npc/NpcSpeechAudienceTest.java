package dev.avatarcraft.npc;

import static org.junit.jupiter.api.Assertions.*;

import dev.avatarcraft.npc.ai.NpcService;
import org.junit.jupiter.api.Test;

class NpcSpeechAudienceTest {
  @Test
  void normalConversationUses64BlockSphereAndSameDimension() {
    var near = NpcService.SpeechAudience.NEARBY;
    assertTrue(near.canHear(false, true, 64 * 64));
    assertFalse(near.canHear(true, true, 64 * 64 + .001));
    assertFalse(near.canHear(true, false, 0));
  }

  @Test
  void importantNoticesReachOnlyTheOwnerRegardlessOfDistanceOrDimension() {
    var important = NpcService.SpeechAudience.OWNER_IMPORTANT;
    assertTrue(important.canHear(true, false, 1000000));
    assertFalse(important.canHear(false, true, 1));
  }
}

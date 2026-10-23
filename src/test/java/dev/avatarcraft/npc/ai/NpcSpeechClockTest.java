package dev.avatarcraft.npc.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NpcSpeechClockTest {
  @Test
  void minuteReportsStayIndependentOfConversationAndAdvanceWithoutDrifting() {
    var clock = new NpcSpeechClock(100);
    assertFalse(clock.due(1299, true));
    assertTrue(clock.due(1300, true));
    assertFalse(clock.due(1300, false));
    clock.reported(1400);
    assertFalse(clock.due(2499, true));
    assertTrue(clock.due(2500, true));
    clock.reported(6000);
    assertFalse(clock.due(6099, true));
    assertTrue(clock.due(6100, true));
    clock.reset(6100);
    assertFalse(clock.due(7299, true));
    assertTrue(clock.due(7300, true));
  }
}

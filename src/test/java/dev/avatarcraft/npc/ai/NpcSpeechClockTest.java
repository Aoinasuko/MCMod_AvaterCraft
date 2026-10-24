package dev.avatarcraft.npc.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NpcSpeechClockTest {
  @Test
  void configuredSpeechStaysWithinThirtyToSixtySecondsAndIgnoresActionTiming() {
    for (int seconds : new int[] {30, 45, 60}) {
      var clock = new NpcSpeechClock(100, seconds);
      assertFalse(clock.due(100 + seconds * 20 - 1, true));
      assertTrue(clock.due(100 + seconds * 20, true));
      clock.reported(100 + seconds * 20);
      assertFalse(clock.due(100 + seconds * 40 - 1, true));
      assertTrue(clock.due(100 + seconds * 40, true));
    }
    assertThrows(IllegalArgumentException.class, () -> new NpcSpeechClock(0, 29));
    assertThrows(IllegalArgumentException.class, () -> new NpcSpeechClock(0, 61));
  }

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

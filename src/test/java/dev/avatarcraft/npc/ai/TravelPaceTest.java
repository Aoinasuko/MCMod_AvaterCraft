package dev.avatarcraft.npc.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TravelPaceTest {
  @Test
  void distantTravelSprintsAndUsesHysteresisBeforeWalkingAgain() {
    var pace = new TravelPace();
    pace.update(64, true, false);
    assertTrue(pace.sprinting());
    assertEquals(2.2, pace.speed());
    pace.update(36, true, false);
    assertTrue(pace.sprinting());
    pace.update(16, true, false);
    assertFalse(pace.sprinting());
    assertEquals(1.6, pace.speed());
    pace.update(36, true, false);
    assertFalse(pace.sprinting());
  }

  @Test
  void preciseMovementAndHungerPreventSprintAndCancellationResetsIt() {
    var pace = new TravelPace();
    pace.update(100, true, true);
    assertFalse(pace.sprinting());
    pace.update(100, false, false);
    assertFalse(pace.sprinting());
    pace.update(100, true, false);
    pace.reset();
    assertFalse(pace.sprinting());
  }
}

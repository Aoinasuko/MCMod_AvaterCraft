package dev.avatarcraft.npc.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FollowPaceTest {
  @Test
  void catchesUpAndStopsWithoutOscillatingAtThresholds() {
    var pace = new FollowPace();
    pace.update(3 * 3);
    assertFalse(pace.moving());
    pace.update(4 * 4);
    assertTrue(pace.moving());
    assertFalse(pace.sprinting());
    double walk = pace.speed();
    pace.update(6 * 6);
    assertTrue(pace.sprinting());
    assertTrue(pace.speed() > walk);
    pace.update(4.5 * 4.5);
    assertTrue(pace.sprinting());
    pace.update(3 * 3);
    assertTrue(pace.moving());
    assertFalse(pace.sprinting());
    pace.update(1.5 * 1.5);
    assertFalse(pace.moving());
    assertFalse(pace.sprinting());
    pace.update(3 * 3);
    assertFalse(pace.moving());
    pace.update(6 * 6);
    assertTrue(pace.sprinting());
    pace.reset();
    assertFalse(pace.moving());
    assertFalse(pace.sprinting());
  }
}

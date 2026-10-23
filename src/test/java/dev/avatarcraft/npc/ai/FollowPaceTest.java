package dev.avatarcraft.npc.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FollowPaceTest {
  @Test
  void catchesUpAndStopsWithoutOscillatingAtThresholds() {
    var pace = new FollowPace();
    pace.update(5 * 5);
    assertFalse(pace.moving());
    pace.update(7 * 7);
    assertTrue(pace.moving());
    assertFalse(pace.sprinting());
    double walk = pace.speed();
    pace.update(10 * 10);
    assertTrue(pace.sprinting());
    assertTrue(pace.speed() > walk);
    pace.update(7 * 7);
    assertTrue(pace.sprinting());
    pace.update(4 * 4);
    assertTrue(pace.moving());
    assertFalse(pace.sprinting());
    pace.update(2 * 2);
    assertFalse(pace.moving());
    assertFalse(pace.sprinting());
    pace.update(4 * 4);
    assertFalse(pace.moving());
    pace.update(9 * 9);
    assertTrue(pace.sprinting());
    pace.reset();
    assertFalse(pace.moving());
    assertFalse(pace.sprinting());
  }
}

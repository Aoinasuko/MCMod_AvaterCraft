package dev.avatarcraft.npc;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NpcVitalsTest {
  @Test
  void exhaustedWorkConsumesSaturationBeforeFoodAndNeverCreatesFood() {
    var food = new NpcVitals();
    food.exhaust(40);
    for (int i = 0; i < 6; i++) food.tick(20, 20, 2, true);
    assertEquals(0, food.saturation());
    assertEquals(19, food.food());
    food.eat(8, 12);
    assertEquals(20, food.food());
    assertEquals(12, food.saturation());
  }

  @Test
  void regenerationUsesTheVanillaTenAndEightyTickIntervals() {
    var food = new NpcVitals();
    for (int i = 0; i < 9; i++) assertEquals(0, food.tick(10, 20, 2, true).healing());
    assertEquals(5f / 6, food.tick(10, 20, 2, true).healing());
    food.restore(18, 0, 0, 0);
    for (int i = 0; i < 79; i++) assertEquals(0, food.tick(10, 20, 2, true).healing());
    assertEquals(1, food.tick(10, 20, 2, true).healing());
    assertEquals(6, food.exhaustion());
  }

  @Test
  void starvationRespectsDifficultyAndRegenRule() {
    for (int difficulty = 0; difficulty <= 3; difficulty++) {
      var food = new NpcVitals();
      food.restore(0, 0, 0, 79);
      assertEquals(difficulty == 3, food.tick(1, 20, difficulty, true).starvation());
    }
    var food = new NpcVitals();
    for (int i = 0; i < 100; i++) assertEquals(0, food.tick(1, 20, 3, false).healing());
    food.restore(-10, Float.NaN, Float.POSITIVE_INFINITY, 1000);
    assertEquals(0, food.food());
    assertEquals(0, food.saturation());
    assertEquals(0, food.exhaustion());
  }
}

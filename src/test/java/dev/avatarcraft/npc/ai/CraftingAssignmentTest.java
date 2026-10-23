package dev.avatarcraft.npc.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class CraftingAssignmentTest {
  @Test
  void overlappingIngredientAlternativesUseDifferentActualMaterials() {
    int[] counts = {1, 1};
    int[] assignment =
        CraftingAssignment.find(List.of(new int[] {0, 1}, new int[] {0}), counts).orElseThrow();
    assertArrayEquals(new int[] {1, 0}, assignment);
    assertArrayEquals(new int[] {1, 1}, counts);
  }

  @Test
  void repeatedIngredientsCannotConsumeTheSameItemTwice() {
    assertTrue(
        CraftingAssignment.find(List.of(new int[] {0}, new int[] {0}), new int[] {1}).isEmpty());
    assertArrayEquals(
        new int[] {0, 0},
        CraftingAssignment.find(List.of(new int[] {0}, new int[] {0}), new int[] {2})
            .orElseThrow());
  }

  @Test
  void impossibleRecipeAndInvalidSlotFailWithoutMutatingInventory() {
    assertTrue(
        CraftingAssignment.find(List.of(new int[] {}, new int[] {0}), new int[] {64}).isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> CraftingAssignment.find(List.of(new int[] {36}), new int[36]));
  }
}

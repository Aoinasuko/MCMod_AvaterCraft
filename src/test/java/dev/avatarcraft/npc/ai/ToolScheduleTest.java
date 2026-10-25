package dev.avatarcraft.npc.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.*;
import org.junit.jupiter.api.Test;

class ToolScheduleTest {
  static ToolProgram.Node node(String id, int priority, String... after) {
    return new ToolProgram.Node(
        id,
        "observe",
        new JsonObject(),
        priority,
        List.of(after),
        new JsonObject(),
        new JsonObject(),
        true,
        "",
        new AreaWorkRegion.Cell(0, 1, 0));
  }

  @Test
  void newlyReadyHigherPriorityRunsBeforeUnrelatedLowerPriority() {
    var nodes = List.of(node("plant", 100, "till"), node("till", 0), node("other", 0));
    var s = new ToolSchedule(nodes, new BitSet());
    assertEquals(1, s.next());
    s.complete(1);
    assertEquals(0, s.next());
    s.complete(0);
    assertEquals(2, s.next());
    s.complete(2);
    assertEquals(-1, s.next());
  }

  @Test
  void failedDependenciesNeverReleaseChildrenAndNoProgressDoesNotLoop() {
    var s =
        new ToolSchedule(
            List.of(node("dependent", 100, "parent"), node("parent", 0)), new BitSet());
    assertEquals(1, s.next());
    s.defer(1);
    assertEquals(-1, s.next());
    assertEquals(1, s.pass());
  }

  @Test
  void stableTiesCheckpointAndWorldChangesAllowBoundedRetry() {
    var done = new BitSet();
    done.set(0);
    var s =
        new ToolSchedule(List.of(node("old", 0), node("first", 5, "old"), node("second", 5)), done);
    assertEquals(1, s.next());
    s.defer(1);
    assertEquals(2, s.next());
    s.complete(2);
    assertEquals(1, s.next());
    assertEquals(2, s.pass());
    s.complete(1);
    assertEquals(-1, s.next());
  }
}

package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class NpcWorkRouteContractTest {
  @Test
  void workRouteRequiresBoundedExplicitIntentAndRealGamePermissions() {
    var args = object("x", 4, "y", 1, "z", 2, "goal", "reach", "mode", "both", "max_blocks", 96);
    assertDoesNotThrow(
        () -> NpcTools.validate("work_route", args, List.of("move_to", "dig", "place", "observe")));
    assertThrows(
        IllegalArgumentException.class,
        () -> NpcTools.validate("work_route", args, List.of("observe")));
    args.addProperty("max_blocks", 129);
    assertThrows(
        IllegalArgumentException.class,
        () -> NpcTools.validate("work_route", args, NpcTools.names()));
    args.addProperty("max_blocks", 1);
    args.addProperty("mode", "teleport");
    assertThrows(
        IllegalArgumentException.class,
        () -> NpcTools.validate("work_route", args, NpcTools.names()));
  }

  @Test
  void instructionContractExposesDiggingAndBridgeCommands() {
    assertTrue(TaskPlan.instructions().contains("こっちまで橋を架けて"));
    assertTrue(TaskPlan.instructions().contains("dig through and come here"));
    var s =
        new TaskPlan.Step(
            "tool",
            "avatarcraftnpc:work_route",
            "",
            1,
            -1,
            -1,
            "main",
            10,
            List.of(),
            "",
            object("x", 4, "y", 1, "z", 2, "goal", "reach", "mode", "dig", "max_blocks", 16)
                .toString());
    var p = new TaskPlan(1, "掘ってこちらへ", "", List.of(s));
    assertEquals(p, TaskPlan.parse(new com.google.gson.Gson().toJson(p)));
  }
}

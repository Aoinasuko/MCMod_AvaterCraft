package dev.avatarcraft.npc.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class NpcPlanningTierTest {
  @Test
  void routesJapaneseEnglishComplexMissionsToAdvancedPlanner() {
    for (String text :
        List.of(
            "仲間と協力して道具を作ってダイヤを採掘して",
            "複数NPCでネザー要塞を探して",
            "エンダードラゴンを倒して",
            "Work together to mine a diamond",
            "Explore a nether fortress",
            "Defeat the ender dragon")) assertTrue(NpcPlanningTier.advanced(text), text);
    assertFalse(NpcPlanningTier.advanced("石炭を1個渡して"));
    assertFalse(NpcPlanningTier.advanced("Give me one coal"));
  }

  @Test
  void atomicPlansRejectUnknownToolsMalformedArgumentsAndExtraFields() {
    var valid =
        new TaskPlan.Step(
            "tool",
            "avatarcraftnpc:look_at",
            "",
            1,
            -1,
            -1,
            "main",
            1,
            List.of(),
            "",
            "{\"x\":0,\"y\":1,\"z\":2}");
    var plan = new TaskPlan(1, "Aim", "", List.of(valid));
    assertEquals(plan, TaskPlan.parse(new com.google.gson.Gson().toJson(plan)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TaskPlan.Step(
                "tool", "avatarcraftnpc:shell", "", 1, -1, -1, "main", 1, List.of(), "", "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TaskPlan.Step(
                "tool",
                "avatarcraftnpc:jump",
                "",
                1,
                -1,
                -1,
                "main",
                1,
                List.of(),
                "",
                "{\"operator\":true}"));
    assertFalse(NpcTools.permitted("delegate_task", List.of("observe")));
    assertFalse(NpcTools.permitted("acquire", List.of("observe")));
  }

  @Test
  void boundsObservationCopiesWithoutMutatingRealToolOutput() {
    var entities = new com.google.gson.JsonArray();
    for (int i = 0; i < 100; i++)
      entities.add(CodexBridge.object("entity_id", i, "state", "x".repeat(200)));
    var result = CodexBridge.object("ok", true, "entities", entities);
    var bounded = NpcObservations.bounded(result, 1500);
    assertTrue(bounded.toString().length() <= 1500);
    assertTrue(bounded.get("ok").getAsBoolean());
    assertTrue(bounded.get("truncated").getAsBoolean());
    assertEquals(100, result.getAsJsonArray("entities").size());
    assertEquals(
        0, bounded.getAsJsonArray("entities").get(0).getAsJsonObject().get("entity_id").getAsInt());
  }

  @Test
  void providerEmptyArgumentsAreHarmlessButExtraInstructionsAreRejected() {
    var empty = new TaskPlan.Step("wait", "", "", 1, -1, -1, "main", 1, List.of(), "", "{}");
    assertEquals("", empty.arguments());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TaskPlan.Step(
                "wait", "", "", 1, -1, -1, "main", 1, List.of(), "", "{\"seconds\":60}"));
    var next = new TaskPlan.Step("plan_next", "", "", 1, -1, -1, "main", 1, List.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> new TaskPlan(1, "invalid order", "", List.of(next, empty)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            NpcTools.validate("set_sprinting", CodexBridge.object("enabled", 2), NpcTools.names()));
  }

  @Test
  void delegatedAtomicPlansMustRespectActualToolBoundsBeforeAssignment() {
    var scan =
        new TaskPlan.Step(
            "tool",
            "avatarcraftnpc:inspect_structures",
            "",
            1,
            -1,
            -1,
            "main",
            1,
            List.of(),
            "",
            "{\"radius\":0}");
    String valid =
        new com.google.gson.Gson()
            .toJson(new TaskPlan(1, "Scan loaded references", "", List.of(scan)));
    NpcTools.validate(
        "delegate_task", CodexBridge.object("entity_id", -1, "plan_json", valid), NpcTools.names());
    String invalid = valid.replace("radius\\\":0", "radius\\\":128");
    assertNotEquals(valid, invalid);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            NpcTools.validate(
                "delegate_task",
                CodexBridge.object("entity_id", -1, "plan_json", invalid),
                NpcTools.names()));
    assertTrue(NpcTools.compactReference().contains("radius:integer[0..1]"));
  }
}

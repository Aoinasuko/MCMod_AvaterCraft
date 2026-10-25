package dev.avatarcraft.npc.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskPlanDiagnosticsTest {
  @Test
  void continuationCannotHideNaturalLanguageInResourceFields() {
    var invalid =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new TaskPlan.Step(
                    "plan_next", "次は残りをチェストに入れる", "", 1, -1, -1, "main", 10, List.of()));
    assertTrue(invalid.getMessage().contains("target"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TaskPlan.Step(
                "plan_next", "minecraft:diamond", "", 1, -1, -1, "main", 10, List.of()));
    var valid =
        new TaskPlan(
            1,
            "残りの仕事を継続",
            "",
            List.of(new TaskPlan.Step("plan_next", "", "", 1, -1, -1, "main", 10, List.of())));
    assertEquals(valid, TaskPlan.parse(new Gson().toJson(valid)));
    var alternatives =
        TaskPlan.schema()
            .getAsJsonObject("properties")
            .getAsJsonObject("steps")
            .getAsJsonObject("items")
            .getAsJsonArray("anyOf");
    assertEquals(
        "",
        alternatives
            .get(1)
            .getAsJsonObject()
            .getAsJsonObject("properties")
            .getAsJsonObject("target")
            .getAsJsonArray("enum")
            .get(0)
            .getAsString());
  }

  @Test
  void acceptedPlansStayWithinJournalLimitEvenWhenPlanItselfIsLarger() {
    var normal = LocalTaskRunnerTest.plan(LocalTaskRunnerTest.step("wait", "", 1));
    assertEquals(normal, TaskPlan.parse(normal.journalDetails().get("plan").toString()));
    String resource = "test:" + "x".repeat(120);
    var step =
        new TaskPlan.Step(
            "mine", resource, resource, 1, -1, -1, "main", 1, List.of(), "x".repeat(32));
    var large = new TaskPlan(1, "Large valid plan", "", Collections.nCopies(24, step));
    String json = new Gson().toJson(large);
    assertTrue(json.length() > 8192 && json.length() <= 16384);
    assertEquals(large, TaskPlan.parse(json));
    var details = large.journalDetails();
    assertTrue(details.toString().length() <= 8192);
    assertEquals(24, details.get("steps").getAsInt());
    assertTrue(details.get("planOmittedForSize").getAsBoolean());
  }
}

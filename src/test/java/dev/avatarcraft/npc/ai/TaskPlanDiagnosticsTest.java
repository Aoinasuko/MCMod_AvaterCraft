package dev.avatarcraft.npc.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskPlanDiagnosticsTest {
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

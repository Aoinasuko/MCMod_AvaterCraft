package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NpcResourceFamilyTest {
  @Test
  void genericLogsRetainAllSpeciesWhileExplicitSpeciesStayExact() {
    var s =
        new TaskPlan.Step(
            "collect", "minecraft:logs", "minecraft:oak_log", 8, -1, -1, "main", 10, List.of());
    assertEquals("#minecraft:logs", s.target());
    assertEquals("#minecraft:logs", NpcResources.search(s));
    var spruce =
        object(
            "item", "minecraft:spruce_log", "tags", JsonParser.parseString("[\"minecraft:logs\"]"));
    assertTrue(NpcResources.matches(spruce, s.target()));
    assertFalse(NpcResources.matches(spruce, "minecraft:oak_log"));
    assertFalse(NpcResources.matches(object("item", "minecraft:spruce_log"), "#minecraft:logs"));
    assertEquals("minecraft:spruce_log", NpcResources.normalize("minecraft:spruce_log"));
    assertEquals(
        s,
        TaskPlan.parse(new Gson().toJson(new TaskPlan(1, "logs", "", List.of(s))))
            .steps()
            .getFirst());
  }

  @Test
  void bilingualExamplesGuideTagsAndUnresolvedReferences() {
    assertTrue(NpcInstructionExamples.relevant("原木を少し集めて").contains("#minecraft:logs"));
    assertTrue(NpcInstructionExamples.relevant("get some wood").contains("#minecraft:logs"));
    assertTrue(NpcInstructionExamples.relevant("あれを壊して").contains("確認"));
    assertEquals("[]", NpcInstructionExamples.relevant("zzzzzzzzzz"));
  }

  @Test
  void routingSelectsOneExpertWithoutGrowingPerDecisionWork() {
    var x = LocalActionPolicy.features("execute", false, 0, 0, 20, 0, 1);
    assertEquals(0, LocalActionPolicy.expert(x));
    x[27] = 1;
    assertEquals(4, LocalActionPolicy.expert(x));
    x[44] = 1;
    assertEquals(3, LocalActionPolicy.expert(x));
    x[40] = 1;
    assertEquals(1, LocalActionPolicy.expert(x));
    x[32] = 1;
    assertEquals(2, LocalActionPolicy.expert(x));
  }
}

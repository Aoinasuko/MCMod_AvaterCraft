package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NpcExecutionMemoryTest {
  @Test
  void emptyResourceCoveragePersistsAcrossAcquisitionRestartsButNotNewGoals(){
    var memory=new NpcExecutionMemory();
    memory.searched("minecraft:overworld","find_blocks",object("block","#minecraft:logs","radius",16,
        "origin",new Gson().toJsonTree(List.of(-1,64,-17))),object("ok",true,"blocks",new JsonArray()),10);
    var loaded=new NpcExecutionMemory();loaded.restore(memory.save());
    assertTrue(loaded.searchedEmpty("minecraft:overworld","#minecraft:logs",0,65,-16));
    assertFalse(loaded.searchedEmpty("minecraft:overworld","#minecraft:logs",24,64,-17));
    assertFalse(loaded.searchedEmpty("minecraft:overworld","minecraft:coal_ore",0,65,-16));
    loaded.newGoal();assertFalse(loaded.searchedEmpty("minecraft:overworld","#minecraft:logs",0,65,-16));
  }
  private static final String DIM = "minecraft:overworld";

  @Test
  void failedTargetSurvivesExecutorRestartAndDropJitter() {
    var memory = new NpcExecutionMemory();
    memory.failed(DIM, "move_precisely", object("x", -3.2, "y", 4, "z", 8), "No walking path", 20);
    var loaded = new NpcExecutionMemory();
    loaded.restore(memory.save());
    assertTrue(loaded.blocked(DIM, "move_to", object("z", 8, "x", -3.7, "y", 4), 21));
    assertFalse(
        loaded.blocked("minecraft:the_nether", "move_to", object("x", -3.7, "y", 4, "z", 8), 21));
    assertFalse(loaded.blocked(DIM, "move_to", object("x", -3.7, "y", 4, "z", 8), 421));
  }

  @Test
  void geometryChangeOnlyReopensNearbyTargets() {
    var m = new NpcExecutionMemory();
    var near = object("x", 2, "y", 1, "z", 0);
    var far = object("x", 30, "y", 1, "z", 0);
    m.failed(DIM, "move_to", near, "path blocked", 0);
    m.failed(DIM, "move_to", far, "path blocked", 0);
    m.succeeded(DIM, "dig", object("x", 1, "y", 1, "z", 0));
    assertFalse(m.blocked(DIM, "move_to", near, 1));
    assertTrue(m.blocked(DIM, "move_to", far, 1));
  }

  @Test
  void cooldownRejectionDoesNotExtendCooldownForever() {
    var m = new NpcExecutionMemory();
    var p = object("x", 2, "y", 1, "z", 0);
    m.failed(DIM, "move_to", p, "No path", 0);
    m.failed(DIM, "move_to", p, "TARGET_COOLDOWN: unchanged target", 399);
    assertFalse(m.blocked(DIM, "move_to", p, 401));
  }

  @Test
  void searchFindingsKeepEmptyResultsAndAllWoodSpeciesAcrossReload() {
    var m = new NpcExecutionMemory();
    m.searched(
        DIM,
        "find_blocks",
        object("block", "minecraft:spruce_log", "radius", 16),
        object("ok", true, "blocks", JsonParser.parseString("[[1,2,3],[1,3,3]]")),
        1);
    for (String id :
        List.of("oak", "birch", "jungle", "acacia", "cherry", "mangrove", "dark_oak", "pale_oak"))
      m.searched(
          DIM,
          "find_blocks",
          object("block", "minecraft:" + id + "_log", "radius", 16),
          object("ok", true, "blocks", new JsonArray()),
          2);
    var loaded = new NpcExecutionMemory();
    loaded.restore(m.save());
    var results = loaded.observation(DIM, 3).getAsJsonArray("searches");
    assertEquals(9, results.size());
    assertEquals(
        2,
        results.get(8).getAsJsonObject().getAsJsonObject("result").getAsJsonArray("blocks").size());
    assertTrue(
        results
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("result")
            .getAsJsonArray("blocks")
            .isEmpty());
  }

  @Test
  void reverseTrailCanReturnSeventyTwoBlocksUpAndPersists() {
    var m = new NpcExecutionMemory();
    for (int y = 76; y >= 4; y--) m.walked(DIM, 76 - y, y, 0);
    var loaded = new NpcExecutionMemory();
    loaded.restore(m.save());
    var route = loaded.returnPath(DIM, 72, 4, 0, 0, 76, 0);
    assertEquals(73, route.size());
    assertEquals(List.of(0, 76, 0), route.getLast());
    assertTrue(loaded.returnPath("minecraft:the_nether", 72, 4, 0, 0, 76, 0).isEmpty());
  }

  @Test
  void teleportsAndLongFallsNeverBecomeInventedWalkingEdges() {
    var m = new NpcExecutionMemory();
    m.walked(DIM, 0, 76, 0);
    m.walked(DIM, 0, 4, 0);
    assertTrue(m.returnPath(DIM, 0, 4, 0, 0, 76, 0).isEmpty());
    assertTrue(m.returnCheckpoints(DIM,0,4,0,0,76,0).isEmpty());
  }

  @Test
  void smallDropsAreOnlyReturnCheckpointsAndChestMayBeBesideTheTrail() {
    var m=new NpcExecutionMemory();
    m.walked(DIM,0,70,0);m.walked(DIM,1,68,0);m.walked(DIM,2,67,0);
    assertTrue(m.returnPath(DIM,2,67,0,0,70,2).isEmpty());
    assertEquals(List.of(List.of(2,67,0),List.of(0,70,0)),m.returnCheckpoints(DIM,2,67,0,0,70,2));
  }

  @Test
  void confirmedOutcomePersistsAndNewGoalClearsIt() {
    var m = new NpcExecutionMemory();
    m.completed("Store logs", object("moved", 64));
    var loaded = new NpcExecutionMemory();
    loaded.restore(m.save());
    assertTrue(loaded.hasCompletedGoal());
    assertEquals(
        "SUCCEEDED",
        loaded
            .observation(DIM, 0)
            .getAsJsonObject("lastVerifiedOutcome")
            .get("state")
            .getAsString());
    loaded.newGoal();
    assertFalse(loaded.hasCompletedGoal());
  }

  static TaskPlan.Step waitStep() {
    return new TaskPlan.Step("wait", "", "", 1, -1, -1, "main", 1, List.of());
  }

  @Test
  void repairReservationRunsThreeToFiveSlotsThenReplansOriginalMission() {
    for (int count = 3; count <= 5; count++) {
      var p =
          NpcRecoveryQueue.schedule(
              new TaskPlan(1, "Repair", "", Collections.nCopies(count, waitStep())));
      assertEquals(count + 1, p.steps().size());
      assertEquals("plan_next", p.steps().getLast().action());
      TaskPlan.parse(new Gson().toJson(p));
    }
    for (int count : List.of(1, 2, 6))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              NpcRecoveryQueue.schedule(
                  new TaskPlan(1, "Bad repair", "", Collections.nCopies(count, waitStep()))));
  }

  @Test
  void signsUseObservationPermissionAndRejectExcessiveRadius() {
    NpcTools.validate("read_signs", object("radius", 16), List.of("observe"));
    assertThrows(
        IllegalArgumentException.class,
        () -> NpcTools.validate("read_signs", object("radius", 17), List.of("observe")));
    assertThrows(
        IllegalArgumentException.class,
        () -> NpcTools.validate("read_signs", object("radius", 8), List.of("move_to")));
  }
}

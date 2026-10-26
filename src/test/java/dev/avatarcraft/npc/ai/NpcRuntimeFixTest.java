package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NpcRuntimeFixTest {
  @Test void spatialToolsInheritObservationOnlyAndHaveFixedRanges(){
    for(String tool:List.of("observe_terrain","observe_entities")){
      assertTrue(NpcTools.names().contains(tool));
      assertTrue(NpcTools.permitted(tool,List.of("observe")));
      assertFalse(NpcTools.permitted(tool,List.of("move_to")));
      assertDoesNotThrow(()->NpcTools.validate(tool,object(),List.of("observe")));
      assertThrows(IllegalArgumentException.class,()->NpcTools.validate(tool,object("radius",64),List.of("observe")));
    }
  }
  @Test
  void fullInventorySurvivesTagHeavyContextAndSmallOptionalBudget() {
    var inventory = new JsonArray();
    for (int i = 0; i < 42; i++)
      inventory.add(
          object(
              "slot",
              i,
              "item",
              i == 27
                  ? "minecraft:raw_gold"
                  : i == 41 ? "minecraft:iron_pickaxe" : "minecraft:cobblestone",
              "count",
              i >= 36 ? 1 : 8,
              "tags",
              new Gson().toJsonTree(Collections.nCopies(80, "minecraft:huge_irrelevant_tag")),
              "equipment",
              i < 36 ? "bag" : "gear_" + i,
              "damage",
              i == 41 ? 162 : 0,
              "max_damage",
              i == 41 ? 250 : 0));
    var original =
        object(
            "ok",
            true,
            "inventory",
            inventory,
            "history",
            new Gson().toJsonTree(Collections.nCopies(100, "x".repeat(500))));
    var reference=object("speaker",object("blockPosition",new Gson().toJsonTree(List.of(-1,64,-17)),
        "chunk",object("x",-1,"z",-2,"minX",-16,"maxX",-1,"minZ",-32,"maxZ",-17)));
    original.add("command_context",reference);
    for (int budget : List.of(1000, 8000, 10000)) {
      var planned = NpcObservations.planning(original, budget);
      assertEquals(42, planned.getAsJsonArray("inventory").size());
      assertEquals(reference,planned.getAsJsonObject("command_context"));
      assertTrue(planned.get("inventory_complete").getAsBoolean());
      assertEquals(8, planned.getAsJsonObject("item_counts").get("minecraft:raw_gold").getAsInt());
      var hand = planned.getAsJsonArray("inventory").get(41).getAsJsonObject();
      assertEquals(162, hand.get("damage").getAsInt());
      assertEquals(250, hand.get("max_damage").getAsInt());
      assertFalse(hand.has("tags"));
      assertEquals(0, planned.get("free_bag_slots").getAsInt());
    }
    var logged = NpcObservations.bounded(original, 6000);
    assertEquals(42, logged.getAsJsonArray("inventory").size());
    assertTrue(logged.toString().length() <= 6000);
    assertTrue(inventory.get(41).getAsJsonObject().has("tags"));
  }

  @Test
  void oscillatingMovementExpiresButLongDetoursContinue() {
    var stuck = new NpcMoveProgress(0);
    boolean stalled = false;
    for (int t = 0; t < 450; t++) stalled = stuck.stalled(t, .5 + (t % 2) * .8, 1, .5, 10, 1, 0);
    assertTrue(stalled);
    var detour = new NpcMoveProgress(0);
    for (int t = 0; t < 2000; t++) assertFalse(detour.stalled(t, .5 + t / 10., 1, .5, 0, 1, 0));
    assertTrue(detour.visitedCells() > 100);
  }

  @Test
  void searchAndVerifiedEditsPauseWatchdog() {
    var progress = new NpcMoveProgress(0);
    progress.stalled(0, .5, 1, .5, 10, 1, 0);
    progress.pauseForSearch(399);
    assertFalse(progress.stalled(700, .5, 1, .5, 10, 1, 0));
    progress.edited(700);
    assertFalse(progress.stalled(1099, .5, 1, .5, 10, 1, 0));
    assertTrue(progress.stalled(1101, .5, 1, .5, 10, 1, 0));
  }

  @Test
  void reservationsCover512BlocksIncludingDiagonalAndVerticalLegs() {
    for (var target : List.of(List.of(512, 0, 0), List.of(300, 100, 350), List.of(0, 512, 0))) {
      var points =
          NpcTravelReservations.straight(0, 0, 0, target.get(0), target.get(1), target.get(2));
      var previous = List.of(0, 0, 0);
      assertTrue(points.size() >= 4);
      for (var p : points) {
        double d = 0;
        for (int j = 0; j < 3; j++) d += Math.pow(p.get(j) - previous.get(j), 2.);
        assertTrue(d <= 128 * 128);
        previous = p;
      }
      assertEquals(target, points.getLast());
    }
    assertThrows(
        IllegalArgumentException.class, () -> NpcTravelReservations.straight(0, 0, 0, 513, 0, 0));
  }
}

package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.avatarcraft.npc.Personality;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class LocalTaskRunnerTest {
  static TaskPlan.Step step(String action, String target, int count) {
    return new TaskPlan.Step(
        action,
        target,
        action.equals("collect") ? "minecraft:stone" : "",
        count,
        -1,
        -1,
        "main",
        1,
        List.of());
  }

  static TaskPlan plan(TaskPlan.Step... steps) {
    return new TaskPlan(1, "test", "", List.of(steps));
  }

  static final class World implements LocalTaskRunner.World {
    final JsonObject state =
        object(
            "using_item",
            false,
            "food",
            20,
            "inventory",
            new JsonArray(),
            "position",
            JsonParser.parseString("[0,1,0]"),
            "nearby",
            new JsonArray(),
            "owner",
            object("entity_id", 8, "position", JsonParser.parseString("[1,1,0]")));
    final List<String> calls = new ArrayList<>();
    final List<JsonObject> arguments = new ArrayList<>();
    final List<JsonObject> selections = new ArrayList<>();
    CompletableFuture<JsonObject> held;
    boolean failTransfers, equipped, consumeFood = true;
    int moved, mining, foodSlot = -1;

    void stack(int slot, String item, int count) {
      var inv = state.getAsJsonArray("inventory");
      for (int i = inv.size() - 1; i >= 0; i--)
        if (inv.get(i).getAsJsonObject().get("slot").getAsInt() == slot) inv.remove(i);
      if (count > 0) inv.add(object("slot", slot, "item", item, "count", count));
    }

    public JsonObject observe() {
      return state.deepCopy();
    }

    public int bestTool(JsonArray p) {
      return equipped ? -1 : 0;
    }

    public int foodSlot() {
      return foodSlot;
    }

    public JsonObject approach(JsonArray p) {
      return object("x", 1, "y", 1, "z", 0);
    }

    public void record(JsonObject o) {
      selections.add(o.deepCopy());
    }

    public CompletionStage<JsonObject> execute(String tool, JsonObject args) {
      NpcTools.validate(tool, args, Personality.preset("tortobu").allowedSkills);
      calls.add(tool);
      arguments.add(args.deepCopy());
      if (held != null) return held;
      switch (tool) {
        case "find_blocks":
          return CompletableFuture.completedFuture(
              object("ok", true, "blocks", JsonParser.parseString("[[2.5,1.5,0.5]]")));
        case "equip":
          equipped = true;
          break;
        case "dig":
          mining++;
          stack(2, "minecraft:cobblestone", mining);
          break;
        case "find_chests":
          return CompletableFuture.completedFuture(
              object(
                  "ok",
                  true,
                  "chests",
                  JsonParser.parseString(
                      "[{\"x\":1,\"y\":1,\"z\":0,\"items\":[{\"slot\":0,\"item\":\"minecraft:cobblestone\",\"count\":64}]}]")));
        case "deposit", "withdraw", "give_item":
          if (failTransfers)
            return CompletableFuture.completedFuture(object("ok", false, "moved", 0));
          int amount = Math.min(2, args.get("count").getAsInt());
          moved += amount;
          return CompletableFuture.completedFuture(object("ok", true, "moved", amount));
        case "eat":
          if (consumeFood) {
            state.addProperty("food", 20);
            foodSlot = -1;
          }
          break;
        default:
          break;
      }
      return CompletableFuture.completedFuture(object("ok", true));
    }
  }

  static LocalTaskRunner runner(World world, TaskPlan.Step... steps) {
    return new LocalTaskRunner(plan(steps), world, Personality.preset("tortobu").allowedSkills);
  }

  static void finish(LocalTaskRunner runner) {
    for (int tick = 0; tick < 1000 && !runner.complete() && !runner.needsReplan(); tick++)
      runner.tick(tick);
  }

  @Test
  void learnedPolicyLoadsOnceAndPrefersUrgencyAndNearerApproach() {
    var m = LocalActionPolicy.bundled();
    assertSame(m, LocalActionPolicy.bundled());
    assertTrue(
        m.score(LocalActionPolicy.features("eat", true, 0, 0, 8, 0, 1))
            > m.score(LocalActionPolicy.features("execute", false, 0, 0, 8, 0, 1)));
    assertTrue(
        m.score(LocalActionPolicy.features("approach", false, 2, 0, 20, 0, 1))
            > m.score(LocalActionPolicy.features("approach", false, 15, 0, 20, 0, 1)));
    assertThrows(IllegalArgumentException.class, () -> m.score(new double[] {Double.NaN}));
    assertThrows(IllegalArgumentException.class, () -> new LocalActionPolicy("{}"));
  }

  @Test
  void localMiningEquipsThenUsesIntegerCoordinatesAndTransfersExactCount() {
    var w = new World();
    var r =
        runner(w, step("mine", "minecraft:stone", 2), step("deposit", "minecraft:cobblestone", 3));
    finish(r);
    assertTrue(r.complete(), r.problem());
    assertEquals(2, w.mining);
    assertEquals(3, w.moved);
    assertTrue(w.calls.indexOf("equip") < w.calls.indexOf("dig"));
    assertFalse(w.selections.isEmpty());
    assertTrue(w.selections.stream().allMatch(s -> s.has("candidates") && s.has("chosen")));
  }

  @Test
  void collectionUsesInventoryGoalRatherThanNumberOfBrokenBlocks() {
    var w = new World();
    w.stack(4, "minecraft:cobblestone", 4);
    var r = runner(w, step("collect", "minecraft:cobblestone", 4));
    finish(r);
    assertTrue(r.complete());
    assertTrue(w.calls.isEmpty());
  }

  @Test
  void emergencyFoodAndTaskContinueWithoutAnyProvider() {
    var w = new World();
    w.state.addProperty("food", 8);
    w.foodSlot = 5;
    var r = runner(w, step("wait", "", 1));
    finish(r);
    assertEquals(List.of("eat", "wait"), w.calls);
    assertTrue(r.complete());
  }

  @Test
  void pendingActionsAreNotRepeatedAndClosedResultsCannotAdvanceTask() {
    var w = new World();
    w.held = new CompletableFuture<>();
    var r = runner(w, step("wait", "", 1));
    for (int tick = 0; tick < 200; tick++) r.tick(tick);
    assertEquals(1, w.calls.size());
    assertTrue(r.busy());
    r.close();
    w.held.complete(object("ok", true));
    assertFalse(r.complete());
    assertEquals(0, r.progress().get("verifiedCount").getAsInt());
  }

  @Test
  void deniedToolsNeverExecuteAndFailureDoesNotBecomeCompletion() {
    var w = new World();
    var r = new LocalTaskRunner(plan(step("mine", "minecraft:stone", 1)), w, List.of("observe"));
    finish(r);
    assertTrue(r.needsReplan());
    assertFalse(r.complete());
    assertTrue(w.calls.isEmpty());
    var broken = new World();
    broken.failTransfers = true;
    broken.stack(2, "minecraft:cobblestone", 3);
    var failed = runner(broken, step("deposit", "minecraft:cobblestone", 3));
    finish(failed);
    assertTrue(failed.needsReplan());
    assertEquals(0, failed.progress().get("verifiedCount").getAsInt());
  }

  @Test
  void checkpointPreservesPartialTransferAndNeverReplaysCompletedSteps() {
    var w = new World();
    w.stack(2, "minecraft:cobblestone", 8);
    var r = runner(w, step("deposit", "minecraft:cobblestone", 5), step("wait", "", 1));
    for (int tick = 0; tick < 5; tick++) r.tick(tick);
    assertEquals(2, w.moved);
    var saved = r.checkpoint();
    assertEquals(3, saved.steps().getFirst().count());
    r.close();
    var resumed = new LocalTaskRunner(saved, w, Personality.preset("tortobu").allowedSkills);
    finish(resumed);
    assertTrue(resumed.complete());
    assertEquals(5, w.moved);
    assertNull(resumed.checkpoint());
  }

  @Test
  void staleSlotsAreResolvedByItemAndFullInventoryDoesNotKeepMining() {
    var w = new World();
    w.stack(0, "minecraft:dirt", 1);
    w.stack(4, "minecraft:coal", 1);
    var r =
        runner(w, new TaskPlan.Step("equip", "minecraft:coal", "", 1, -1, 0, "main", 1, List.of()));
    finish(r);
    assertTrue(r.complete());
    assertEquals(4, w.arguments.getFirst().get("slot").getAsInt());
    var full = new World();
    for (int i = 0; i < 36; i++) full.stack(i, "minecraft:cobblestone", 64);
    var blocked = runner(full, step("collect", "minecraft:coal", 1));
    finish(blocked);
    assertTrue(blocked.needsReplan());
    assertTrue(full.calls.isEmpty());
  }

  @Test
  void eatingMustActuallyConsumeOrRestoreHungerBeforeCompletion() {
    var w = new World();
    w.stack(5, "minecraft:apple", 1);
    w.state.addProperty("food", 10);
    w.consumeFood = false;
    var r =
        runner(w, new TaskPlan.Step("eat", "minecraft:apple", "", 1, -1, -1, "main", 1, List.of()));
    r.tick(0);
    assertFalse(r.complete());
    assertNotNull(r.checkpoint());
    finish(r);
    assertTrue(r.needsReplan());
    assertFalse(r.complete());
    assertEquals(0, r.progress().get("verifiedCount").getAsInt());
  }

  @Test
  void versionTwoHasRealMegabyteWeightsAndRejectsCorruption() throws Exception {
    byte[] weights =
        getClass().getResourceAsStream("/data/avatarcraftnpc/ai/action-policy.bin").readAllBytes();
    String json =
        new String(
            getClass()
                .getResourceAsStream("/data/avatarcraftnpc/ai/action-policy.json")
                .readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(4204004, weights.length);
    assertEquals(1051001, LocalActionPolicy.PARAMETERS);
    assertEquals(48, LocalActionPolicy.FEATURES.size());
    assertDoesNotThrow(() -> new LocalActionPolicy(json, weights));
    weights[100] ^= 1;
    assertThrows(IllegalArgumentException.class, () -> new LocalActionPolicy(json, weights));
  }

  @Test
  void namedLocationsResolveAndCrossDimensionTargetsCannotExecute() {
    var w = new World();
    w.state.addProperty("dimension", "minecraft:overworld");
    w.state.add(
        "locations",
        JsonParser.parseString(
            "[{\"name\":\"home\",\"dimension\":\"minecraft:the_nether\",\"x\":1,\"y\":64,\"z\":2,\"kind\":\"chest\"}]"));
    var r =
        runner(w, new TaskPlan.Step("move_to", "", "", 1, -1, -1, "main", 1, List.of(), "home"));
    finish(r);
    assertTrue(r.needsReplan());
    assertTrue(w.calls.isEmpty());
    assertEquals(
        "#minecraft:diamond_ores",
        NpcResources.search(
            new TaskPlan.Step(
                "collect",
                "minecraft:diamond",
                "minecraft:deepslate_diamond_ore",
                1,
                -1,
                -1,
                "main",
                1,
                List.of())));
  }

  @Test
  void validPlansRoundTripAndRejectUnknownFieldsAndFractionalCounts() {
    String json = new Gson().toJson(plan(step("mine", "minecraft:stone", 1)));
    assertEquals(plan(step("mine", "minecraft:stone", 1)), TaskPlan.parse(json));
    assertThrows(
        IllegalArgumentException.class,
        () -> TaskPlan.parse(json.replace("\"count\":1", "\"count\":1.5")));
    assertThrows(
        IllegalArgumentException.class,
        () -> TaskPlan.parse(json.replace("\"count\":1", "\"count\":1,\"shell\":\"run\"")));
    assertThrows(IllegalArgumentException.class, () -> plan());
    assertThrows(
        IllegalArgumentException.class,
        () -> new TaskPlan(1, "", "", Collections.nCopies(25, step("wait", "", 1))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TaskPlan.Step(
                "move_to", "", "", 1, -1, -1, "main", 1, List.of(Double.NaN, 0., 0.)));
    assertThrows(IllegalArgumentException.class, () -> step("say", "", 1));
    assertDoesNotThrow(() -> new TaskPlan(1, "依頼を確認", "Smelting is not supported", List.of()));
  }
}

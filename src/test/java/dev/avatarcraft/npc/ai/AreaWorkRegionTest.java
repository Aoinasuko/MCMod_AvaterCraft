package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AreaWorkRegionTest {
  static JsonObject args(String operation) {
    return object(
        "operation",
        operation,
        "x1",
        8,
        "y1",
        4,
        "z1",
        6,
        "x2",
        6,
        "y2",
        2,
        "z2",
        5,
        "block",
        "",
        "item",
        operation.equals("fill")
            ? "minecraft:dirt"
            : operation.equals("plant") ? "minecraft:wheat_seeds" : "");
  }

  @Test
  void inclusiveReversedCornersProduceUniqueSerpentineLayers() {
    var r = AreaWorkRegion.from(args("dig"));
    assertEquals(18, r.volume());
    assertEquals(new AreaWorkRegion.Cell(6, 4, 5), r.cell(0, true));
    assertEquals(new AreaWorkRegion.Cell(8, 4, 6), r.cell(3, true));
    assertEquals(new AreaWorkRegion.Cell(6, 3, 5), r.cell(6, true));
    assertEquals(new AreaWorkRegion.Cell(6, 2, 5), r.cell(0, false));
    var unique = new HashSet<AreaWorkRegion.Cell>();
    for (int i = 0; i < r.volume(); i++) assertTrue(unique.add(r.cell(i, true)));
    assertEquals(r.volume(), unique.size());
    assertThrows(IndexOutOfBoundsException.class, () -> r.cell(18, false));
  }

  @Test
  void limitsApplyToInclusiveVolumeAndLongArithmetic() {
    assertEquals(4096, new AreaWorkRegion(-8, 0, -8, 7, 15, 7).volume());
    assertThrows(IllegalArgumentException.class, () -> new AreaWorkRegion(0, 0, 0, 32, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new AreaWorkRegion(0, 0, 0, 31, 31, 31));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AreaWorkRegion(Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE, 0, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new AreaWorkRegion(0, -2049, 0, 0, -2049, 0));
  }

  static JsonObject program(String op) {
    var a = args(op);
    a.remove("operation");
    a.remove("block");
    a.remove("item");
    a.addProperty("order", op.equals("dig") ? "top_down" : "bottom_up");
    a.addProperty(
        "steps_json",
        ToolProgram.example(
                op,
                "",
                op.equals("fill")
                    ? "minecraft:dirt"
                    : op.equals("plant") ? "minecraft:wheat_seeds" : "")
            .toString());
    return a;
  }

  @Test
  void templatesRequireRealChildPermissionsAndSafeBindings() {
    for (var op : AreaWorkRegion.OPERATIONS)
      assertDoesNotThrow(() -> NpcTools.validate("area_work", program(op), NpcTools.names()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            NpcTools.validate(
                "area_work", program("fill"), List.of("area_work", "move_to", "observe", "dig")));
    var a = program("dig");
    a.addProperty("x1", 3.5);
    final var fractional = a;
    assertThrows(
        IllegalArgumentException.class,
        () -> NpcTools.validate("area_work", fractional, NpcTools.names()));
    var templates = ToolProgram.example("dig", "", "");
    var node = templates.get(0).getAsJsonObject();
    node.getAsJsonObject("arguments").addProperty("x", "$x+99");
    a = program("dig");
    a.addProperty("steps_json", templates.toString());
    final var escaping = a;
    assertThrows(
        IllegalArgumentException.class,
        () -> NpcTools.validate("area_work", escaping, NpcTools.names()));
    var cycle = ToolProgram.example("dig", "", "");
    cycle.get(0).getAsJsonObject().add("after", JsonParser.parseString("[\"dig\"]"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolProgram.expand(new AreaWorkRegion(0, 1, 0, 0, 1, 0), "top_down", cycle.toString()));
    assertTrue(TaskPlan.instructions().contains("instead of guessing a cuboid"));
  }

  @Test
  void regionToolRoundTripsThroughRealTaskPlanSchema() {
    var step =
        new TaskPlan.Step(
            "tool",
            "bep_3davater_companion:area_work",
            "",
            1,
            -1,
            -1,
            "main",
            10,
            List.of(),
            "",
            program("dig").toString());
    var plan = new TaskPlan(1, "矩形を整える", "", List.of(step));
    assertEquals(plan, TaskPlan.parse(new Gson().toJson(plan)));
    var schema =
        NpcTools.definitions(NpcTools.names()).asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(o -> o.get("name").getAsString().equals("npc_area_work"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        2,
        schema
            .getAsJsonObject("inputSchema")
            .getAsJsonObject("properties")
            .getAsJsonObject("order")
            .getAsJsonArray("enum")
            .size());
  }

  @Test
  void negativeFractionalTargetsCannotEscapeTheRegion() {
    var templates = ToolProgram.example("dig", "", "");
    templates.get(0).getAsJsonObject().getAsJsonObject("arguments").addProperty("x", -1.1);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolProgram.expand(
                new AreaWorkRegion(-1, 1, 0, -1, 1, 0), "top_down", templates.toString()));
  }
}

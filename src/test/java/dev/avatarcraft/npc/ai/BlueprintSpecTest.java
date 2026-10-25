package dev.avatarcraft.npc.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BlueprintSpecTest {
  private static String house() {
    return """
    {"name":"house","size":[5,4,5],"parts":[{"from":[0,0,0],"to":[4,3,4],"block":"minecraft:oak_planks"},{"from":[1,1,1],"to":[3,2,3],"block":"minecraft:air"},{"from":[2,1,0],"to":[2,2,0],"block":"minecraft:air"}]}
    """;
  }

  @Test
  void compactGeometryPreservesRoomAndDoorOpenings() {
    var p = BlueprintSpec.parse(house());
    assertEquals(80, p.blocks().size());
    assertEquals(80, p.materials().get("minecraft:oak_planks"));
    assertFalse(p.blocks().containsKey(new BlueprintSpec.Cell(2, 1, 0)));
    assertTrue(p.blocks().containsKey(new BlueprintSpec.Cell(2, 0, 0)));
    assertThrows(UnsupportedOperationException.class, () -> p.blocks().clear());
  }

  @Test
  void blueprintCannotEscapeItsFootprintOrBlowUpExpansion() {
    var json = house();
    assertThrows(
        IllegalArgumentException.class,
        () -> BlueprintSpec.parse(json.replace("[5,4,5]", "[32,32,32]")));
    assertThrows(
        IllegalArgumentException.class,
        () -> BlueprintSpec.parse(json.replace("[4,3,4]", "[5,3,4]")));
    assertThrows(
        IllegalArgumentException.class,
        () -> BlueprintSpec.parse(json.replace("minecraft:oak_planks", "#minecraft:planks")));
    assertThrows(
        IllegalArgumentException.class,
        () -> BlueprintSpec.parse(json.replace("[0,0,0]", "[0.5,0,0]")));
  }
}

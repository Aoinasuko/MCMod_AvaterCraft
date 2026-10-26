package dev.avatarcraft.npc;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NpcLocationMemoryTest {
  @Test
  void completionClearReleasesEverySlotAndSelectiveDeletionPreservesTheRest() {
    var memory = new NpcLocationMemory();
    for (int i=0;i<8;i++) memory.remember(new NpcLocationMemory.Place("p"+i,"minecraft:overworld",i,64,0,"point"));
    assertTrue(memory.forget("p3"));
    assertNotNull(memory.get("p4"));
    assertNull(memory.get("p3"));
    assertFalse(memory.forget("p3"));
    assertTrue(memory.remember(new NpcLocationMemory.Place("destination","minecraft:overworld",30,64,0,"chest")));
    assertEquals(8,memory.clear());
    assertEquals("[]",memory.json());
    assertEquals(0,memory.clear());
    assertTrue(memory.remember(new NpcLocationMemory.Place("new","minecraft:overworld",1,64,0,"chest")));
  }
  @Test
  void eightNamedPlacesPersistAndNinthDoesNotEvictAnything() {
    var memory = new NpcLocationMemory();
    for (int i = 0; i < 8; i++)
      assertTrue(
          memory.remember(
              new NpcLocationMemory.Place("場所" + i, "minecraft:overworld", i, 64, -i, "point")));
    String before = memory.json();
    assertFalse(
        memory.remember(
            new NpcLocationMemory.Place("ninth", "minecraft:overworld", 9, 64, 9, "point")));
    assertEquals(before, memory.json());
    assertNull(memory.chest("minecraft:overworld", 30, 64, 30));
    assertEquals(before, memory.json());
    var loaded = new NpcLocationMemory();
    loaded.load(before);
    assertEquals(memory.entries(), loaded.entries());
    assertTrue(
        loaded.remember(
            new NpcLocationMemory.Place("場所0", "minecraft:the_nether", 1, 30, 2, "point")));
    assertEquals(8, loaded.entries().size());
    assertTrue(loaded.forget("場所3"));
    assertNotNull(loaded.chest("minecraft:overworld", 30, 64, 30));
    assertEquals(8, loaded.entries().size());
  }

  @Test
  void chestDiscoveryIsStableAndBadMemoriesAreRejected() {
    var memory = new NpcLocationMemory();
    var home = memory.chest("minecraft:overworld", 5, 64, 6);
    assertEquals(home, memory.chest("minecraft:overworld", 5, 64, 6));
    assertEquals(1, memory.entries().size());
    assertThrows(RuntimeException.class, () -> memory.load("[" + home.toString() + "]"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NpcLocationMemory.Place("x\n", "minecraft:overworld", 1, 2, 3, "point"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NpcLocationMemory.Place("x", "minecraft:overworld", 30000001, 2, 3, "point"));
  }
}

package dev.avatarcraft.npc;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AnchorIndexTest {
  private AnchorIndex.Entry entry(String name, int x) {
    return new AnchorIndex.Entry(name, "minecraft:overworld", x, 64, 3);
  }

  @Test
  void namesAreSharedUniqueAndRenamesAreAtomic() {
    var index = new AnchorIndex();
    index.put(entry("拠点の倉庫", 10));
    index.put(entry("畑", 20));
    assertThrows(IllegalArgumentException.class, () -> index.put(entry("拠点の倉庫", 20)));
    assertEquals(20, index.get("畑").x());
    index.put(entry(" 新倉庫 ", 10));
    assertNull(index.get("拠点の倉庫"));
    assertEquals(10, index.get("新倉庫").x());
    assertEquals(index.entries(), AnchorIndex.parse(index.json()).entries());
    assertTrue(index.removeAt("minecraft:overworld", 10, 64, 3));
    assertNull(index.get("新倉庫"));
  }

  @Test
  void normalizationAndBoundsMatchNpcLocationContract() {
    assertEquals("が", AnchorIndex.normalize("か\u3099"));
    assertThrows(IllegalArgumentException.class, () -> AnchorIndex.normalize("x".repeat(33)));
    assertThrows(IllegalArgumentException.class, () -> AnchorIndex.normalize("hidden\ncommand"));
    assertThrows(
        IllegalArgumentException.class, () -> AnchorIndex.normalize("hidden\u202ecommand"));
    assertThrows(IllegalArgumentException.class, () -> AnchorIndex.parse("[null]"));
    var index = new AnchorIndex();
    index.put(entry("one", 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AnchorIndex.parse(
                "["
                    + index.json().substring(1, index.json().length() - 1)
                    + ","
                    + index.json().substring(1, index.json().length() - 1)
                    + "]"));
  }
}

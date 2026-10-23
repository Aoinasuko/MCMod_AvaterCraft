package dev.avatarcraft.npc.memory;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NpcJournalTest {
  @TempDir Path root;

  @Test
  void japaneseConversationPagesBackwardWithoutDuplicatesAndSurvivesRestart() throws Exception {
    var owner = UUID.randomUUID();
    var actor = UUID.randomUUID();
    var journal =
        new NpcJournal(root, Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC));
    for (int i = 0; i < 47; i++)
      journal.append(owner, actor, "clonka", null, "speech", "進んだよ！ " + i, new JsonObject());
    journal = new NpcJournal(root);
    var seen = new HashSet<String>();
    String cursor = "";
    int count = 0;
    do {
      var page = journal.read(owner, actor, cursor, 20);
      for (var entry : page.entries()) {
        assertTrue(seen.add(entry.id()));
        assertTrue(entry.text().contains("進んだよ！"));
        count++;
      }
      cursor = page.nextCursor();
    } while (!cursor.isEmpty());
    assertEquals(47, count);
    assertEquals(List.of(actor), journal.actors(owner));
  }

  @Test
  void ownersAreIsolatedAndCursorCannotSelectArbitraryPaths() throws Exception {
    var owner = UUID.randomUUID();
    var actor = UUID.randomUUID();
    var journal = new NpcJournal(root);
    journal.append(owner, actor, "yilnel", null, "speech", "お手伝いしますね。", new JsonObject());
    assertTrue(journal.read(UUID.randomUUID(), actor, "", 20).entries().isEmpty());
    assertThrows(
        IllegalArgumentException.class, () -> journal.read(owner, actor, "../secret:0", 20));
    assertThrows(IllegalArgumentException.class, () -> journal.read(owner, actor, "", 100));
  }

  @Test
  void largeUtf8RecordsAcrossReadWindowBoundariesAreNotLost() throws Exception {
    var owner = UUID.randomUUID();
    var actor = UUID.randomUUID();
    var journal = new NpcJournal(root);
    var details = new JsonObject();
    details.addProperty("description", "あ".repeat(8000));
    for (int i = 0; i < 36; i++)
      journal.append(owner, actor, "fixture", null, "speech", "語".repeat(4096), details);
    String cursor = "";
    var seen = new HashSet<String>();
    for (int page = 0; page < 10; page++) {
      var result = journal.read(owner, actor, cursor, 30);
      for (var entry : result.entries()) assertTrue(seen.add(entry.id()));
      cursor = result.nextCursor();
      if (cursor.isEmpty()) break;
    }
    assertEquals(36, seen.size());
  }
}

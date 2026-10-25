package dev.avatarcraft.npc.memory;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalStatisticsTest {
  @TempDir Path root;
  private final UUID owner = UUID.randomUUID(), actor = UUID.randomUUID();
  private static final long NOW = Instant.parse("2026-10-26T02:30:15Z").toEpochMilli();
  private NpcJournal journal() { return new NpcJournal(root.resolve("journal"), Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)); }
  private void add(NpcJournal j, long time, String kind, String text, JsonObject details) throws Exception {
    j.appendAt(owner, actor, "tortobu", null, kind, text, details, time);
  }
  @Test void recentHundredAreIndependentOfActionNoiseAndExcludeOtherOwners() throws Exception {
    var j = journal();
    for (int i = 0; i < 125; i++) {
      add(j, NOW - 1000 + i, i % 2 == 0 ? "speech" : "death", "会話 " + i, new JsonObject());
      for (int k = 0; k < 3; k++) add(j, NOW - 1000 + i, "tool_start", "dig", new JsonObject());
    }
    var conversation = j.recent(owner, actor, true);
    assertEquals(100, conversation.size()); assertEquals("会話 124", conversation.getFirst().text());
    assertEquals("会話 25", conversation.getLast().text());
    assertTrue(conversation.stream().allMatch(e -> NpcJournal.conversation(e.kind())));
    assertEquals(100, j.recent(owner, actor, false).size());
    assertEquals("tool_start", j.recent(owner, actor, false).getFirst().kind());
    assertTrue(j.recent(UUID.randomUUID(), actor, false).isEmpty());
  }
  @Test void exactlySixtyMinutesIncludeZeroRowsAndCountRealStartsFailuresRetriesAndHandoffs() throws Exception {
    var j = journal(); var failed = new JsonObject(); failed.addProperty("ok", false);
    var ok = new JsonObject(); ok.addProperty("ok", true);
    add(j, NOW - JournalStatistics.HOUR - 1, "tool_start", "too_old", ok);
    add(j, NOW - JournalStatistics.HOUR, "planning_start", "plan", ok);
    add(j, NOW - 60_000, "tool_start", "dig", ok);
    add(j, NOW - 59_999, "tool_result", "dig", failed);
    add(j, NOW - 59_998, "tool_result", "dig", ok);
    add(j, NOW - 59_997, "local_retry", "retry", ok);
    add(j, NOW - 59_996, "planning_retry", "retry", ok);
    add(j, NOW, "ai_handoff", "recovery", ok);
    var report = JournalStatistics.read(new NpcJournal(root.resolve("journal")), owner, actor, NOW);
    assertEquals(60, report.minutes().size()); assertEquals(NOW - JournalStatistics.HOUR, report.from());
    assertEquals(new JournalStatistics.Counts(1, 1, 1, 2, 1), report.total());
    assertEquals(1, report.minutes().getFirst().counts().planning());
    assertEquals(1, report.minutes().getLast().counts().handoffs());
    assertEquals(0, report.minutes().get(12).counts().executions());
    assertEquals(new JournalStatistics.Command(1, 1), report.commands().get("dig"));
    assertFalse(report.commands().containsKey("too_old"));
  }
  @Test void exportContainsFullHourBeyondUiLimitAndSixtyCsvRows() throws Exception {
    var j = journal();
    add(j, NOW - JournalStatistics.HOUR - 1, "speech", "excluded", new JsonObject());
    String full = "詳細".repeat(1500);
    for (int i = 0; i < 145; i++) add(j, NOW - 145 + i, "tool_start", full, new JsonObject());
    try (var file = JournalArchive.create(j, owner, actor, root.resolve("exports")); var zip = new ZipFile(file.path().toFile())) {
      String events = new String(zip.getInputStream(zip.getEntry("actions.jsonl")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      assertEquals(145, events.lines().count()); assertFalse(events.contains("excluded")); assertTrue(events.contains(full));
      String csv = new String(zip.getInputStream(zip.getEntry("statistics.csv")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      assertEquals(61, csv.lines().count());
      var summary = JsonParser.parseString(new String(zip.getInputStream(zip.getEntry("summary.json")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
      assertEquals(145, summary.get("entries").getAsInt());
      assertEquals(145, summary.getAsJsonObject("statistics").getAsJsonObject("total").get("executions").getAsInt());
      assertEquals(64, file.sha256().length()); assertTrue(file.bytes() > 0);
    }
    try (var files = Files.list(root.resolve("exports"))) { assertEquals(0, files.count()); }
  }
}

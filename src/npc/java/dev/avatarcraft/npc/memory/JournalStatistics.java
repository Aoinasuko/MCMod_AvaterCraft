package dev.avatarcraft.npc.memory;

import java.util.*;

/** Sixty real-time, one-minute buckets, reconstructed from durable timestamped events. */
public final class JournalStatistics {
  public static final long MINUTE = 60_000, HOUR = 60 * MINUTE;
  public record Counts(long planning, long executions, long failures, long retries, long handoffs) {}
  public record Minute(long from, long to, Counts counts) {}
  public record Command(long executions, long failures) {}
  public record Report(long from, long to, Counts total, List<Minute> minutes, Map<String, Command> commands) {}
  private final long from, to;
  private final long[][] buckets = new long[60][5];
  private final Map<String, long[]> commands = new TreeMap<>();

  public JournalStatistics(long to) { this.to = to; this.from = to - HOUR; }
  public void accept(NpcJournal.Entry entry) {
    if (entry.timestamp() < from || entry.timestamp() > to) return;
    int index = (int) Math.min(59, (entry.timestamp() - from) / MINUTE);
    int metric = switch (entry.kind()) {
      case "planning_start" -> 0;
      case "tool_start" -> 1;
      case "tool_result" -> failed(entry) ? 2 : -1;
      case "local_retry", "planning_retry" -> 3;
      case "ai_handoff" -> 4;
      default -> -1;
    };
    if (metric >= 0) buckets[index][metric]++;
    if (entry.kind().equals("tool_start") || entry.kind().equals("tool_result") && failed(entry)) {
      var count = commands.computeIfAbsent(entry.text(), ignored -> new long[2]);
      count[entry.kind().equals("tool_start") ? 0 : 1]++;
    }
  }
  private static boolean failed(NpcJournal.Entry e) {
    try { return e.details().has("ok") && !e.details().get("ok").getAsBoolean(); }
    catch (RuntimeException ignored) { return false; }
  }
  public Report report() {
    long[] total = new long[5];
    var minutes = new ArrayList<Minute>();
    for (int i = 0; i < 60; i++) {
      for (int c = 0; c < 5; c++) total[c] += buckets[i][c];
      minutes.add(new Minute(from + i * MINUTE, from + (i + 1) * MINUTE, counts(buckets[i])));
    }
    var perCommand = new TreeMap<String, Command>();
    commands.forEach((key, value) -> perCommand.put(key, new Command(value[0], value[1])));
    return new Report(from, to, counts(total), List.copyOf(minutes), Collections.unmodifiableMap(perCommand));
  }
  public static Report read(NpcJournal journal, UUID owner, UUID actor, long to) throws java.io.IOException {
    var stats = new JournalStatistics(to);
    journal.visit(owner, actor, entry -> {
      if (entry.timestamp() < to - HOUR) return false;
      stats.accept(entry); return true;
    });
    return stats.report();
  }
  private static Counts counts(long[] c) { return new Counts(c[0], c[1], c[2], c[3], c[4]); }
  private JournalStatistics() { throw new AssertionError(); }
}

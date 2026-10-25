package dev.avatarcraft.npc.memory;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Streaming export: full events (not the 100-entry UI), exact same window and minute CSV. */
public final class JournalArchive {
  public static final long MAX_BYTES = 64L << 20, MAX_RAW_BYTES = 256L << 20;
  public record File(Path path, long bytes, String sha256, long from, long to) implements AutoCloseable {
    public void close() throws IOException { Files.deleteIfExists(path); }
  }
  public static File create(NpcJournal journal, UUID owner, UUID actor, Path temporary) throws IOException {
    Files.createDirectories(temporary);
    Path target = Files.createTempFile(temporary, "journal-", ".zip");
    long now = journal.now();
    var statistics = new JournalStatistics(now);
    var gson = new Gson();
    long[] raw = {0}, entries = {0};
    try {
      try (var zip = new ZipOutputStream(Files.newOutputStream(target))) {
        zip.putNextEntry(new ZipEntry("actions.jsonl"));
        journal.visit(owner, actor, entry -> {
          if (entry.timestamp() < now - JournalStatistics.HOUR) return false;
          if (entry.timestamp() > now) return true;
          byte[] bytes = (gson.toJson(entry) + "\n").getBytes(StandardCharsets.UTF_8);
          raw[0] += bytes.length;
          if (raw[0] > MAX_RAW_BYTES) throw new UncheckedIOException(new IOException("Export exceeds 256 MiB"));
          try { zip.write(bytes); }
          catch (IOException error) { throw new UncheckedIOException(error); }
          entries[0]++; statistics.accept(entry); return true;
        });
        zip.closeEntry();
        var report = statistics.report();
        zip.putNextEntry(new ZipEntry("statistics.csv"));
        zip.write(csv(report).getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        zip.putNextEntry(new ZipEntry("summary.json"));
        zip.write(gson.toJson(Map.of("schema", 1, "owner", owner, "actor", actor,
            "order", "newest-first", "entries", entries[0], "statistics", report,
            "definitions", Map.of("planning", "planning_start", "executions", "tool_start",
                "failures", "tool_result where ok=false", "retries", "local_retry + planning_retry",
                "handoffs", "ai_handoff: actual recovery planning request"))).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      long size = Files.size(target);
      if (size > MAX_BYTES) throw new IOException("Export exceeds 64 MiB");
      return new File(target, size, digest(target), now - JournalStatistics.HOUR, now);
    } catch (Exception error) {
      Files.deleteIfExists(target);
      if (error instanceof UncheckedIOException e) throw e.getCause();
      if (error instanceof IOException e) throw e;
      throw new IOException("Cannot export journal", error);
    }
  }
  public static String csv(JournalStatistics.Report report) {
    var csv = new StringBuilder("\ufeffminute_start_utc,minute_end_utc,planning,executions,failures,retries,handoffs\r\n");
    for (var minute : report.minutes()) {
      var c = minute.counts();
      csv.append(java.time.Instant.ofEpochMilli(minute.from())).append(',')
          .append(java.time.Instant.ofEpochMilli(minute.to())).append(',')
          .append(c.planning()).append(',').append(c.executions()).append(',').append(c.failures()).append(',')
          .append(c.retries()).append(',').append(c.handoffs()).append("\r\n");
    }
    return csv.toString();
  }
  private static String digest(Path file) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(file)) {
      byte[] buffer = new byte[32768]; int size;
      while ((size = input.read(buffer)) >= 0) digest.update(buffer, 0, size);
    }
    return HexFormat.of().formatHex(digest.digest());
  }
  private JournalArchive() {}
}

package dev.avatarcraft.npc.memory;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Per-owner, per-actor JSONL journal. Bounded reverse paging retains records across restarts. */
public final class NpcJournal {
  private static final Gson GSON = new Gson();
  private static final long SEGMENT_SIZE = 8L << 20;
  private final Path root;
  private final Clock clock;

  public record Entry(
      String id,
      long timestamp,
      UUID owner,
      UUID actor,
      String actorName,
      UUID listener,
      String kind,
      String text,
      JsonObject details) {}

  public record Page(List<Entry> entries, String nextCursor) {}

  public NpcJournal(Path root) {
    this(root, Clock.systemUTC());
  }

  public NpcJournal(Path root, Clock clock) {
    this.root = root.toAbsolutePath().normalize();
    this.clock = clock;
  }

  private Path directory(UUID owner, UUID actor) throws IOException {
    Objects.requireNonNull(owner);
    Objects.requireNonNull(actor);
    Files.createDirectories(root);
    Path path = root.resolve(owner.toString()).resolve(actor.toString());
    Files.createDirectories(path);
    if (!path.toRealPath().startsWith(root.toRealPath()))
      throw new IOException("Journal directory escapes root");
    return path;
  }

  public synchronized Entry append(
      UUID owner,
      UUID actor,
      String actorName,
      UUID listener,
      String kind,
      String text,
      JsonObject details)
      throws IOException {
    if (actorName == null
        || actorName.length() > 64
        || kind == null
        || !kind.matches("[a-z_]{1,32}")
        || text == null
        || text.length() > 4096
        || details == null
        || details.toString().length() > 8192)
      throw new IllegalArgumentException("Journal entry exceeds its bounds");
    var entry =
        new Entry(
            UUID.randomUUID().toString(),
            clock.millis(),
            owner,
            actor,
            actorName,
            listener,
            kind,
            text,
            details.deepCopy());
    byte[] bytes = (GSON.toJson(entry) + "\n").getBytes(StandardCharsets.UTF_8);
    Path directory = directory(owner, actor);
    String date = LocalDate.now(clock).toString();
    for (int part = 0; part < 10000; part++) {
      Path file =
          directory.resolve(date + "-" + String.format(Locale.ROOT, "%05d", part) + ".jsonl");
      if (Files.isSymbolicLink(file))
        throw new IOException("Journal segments cannot be symbolic links");
      if (!Files.exists(file) || Files.size(file) + bytes.length <= SEGMENT_SIZE) {
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return entry;
      }
    }
    throw new IOException("Daily journal segment limit reached");
  }

  public synchronized List<UUID> actors(UUID owner) throws IOException {
    Path path = root.resolve(owner.toString());
    if (!Files.isDirectory(path)) return List.of();
    if (!path.toRealPath().startsWith(root.toRealPath()))
      throw new IOException("Journal directory escapes root");
    try (var children = Files.list(path)) {
      return children
          .filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
          .map(p -> p.getFileName().toString())
          .filter(n -> n.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
          .limit(256)
          .map(UUID::fromString)
          .toList();
    }
  }

  public synchronized Page read(UUID owner, UUID actor, String cursor, int size)
      throws IOException {
    if (size < 1 || size > 30)
      throw new IllegalArgumentException("Journal page size must be 1..30");
    Path directory = directory(owner, actor);
    List<Path> files;
    try (var paths = Files.list(directory)) {
      files =
          paths
              .filter(
                  p ->
                      p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}-\\d{5}\\.jsonl")
                          && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
              .sorted(Comparator.reverseOrder())
              .limit(10000)
              .toList();
    }
    String filename = "";
    long offset = -1;
    if (cursor != null && !cursor.isEmpty()) {
      if (!cursor.matches("\\d{4}-\\d{2}-\\d{2}-\\d{5}\\.jsonl:[0-9]{1,12}"))
        throw new IllegalArgumentException("Invalid journal cursor");
      int separator = cursor.indexOf(':');
      filename = cursor.substring(0, separator);
      offset = Long.parseLong(cursor.substring(separator + 1));
    }
    int index = 0;
    if (!filename.isEmpty()) {
      final String expected = filename;
      index = -1;
      for (int i = 0; i < files.size(); i++)
        if (files.get(i).getFileName().toString().equals(expected)) {
          index = i;
          break;
        }
      if (index < 0)
        throw new IllegalArgumentException("Journal cursor refers to a missing segment");
    }
    var entries = new ArrayList<Entry>();
    int visited = 0;
    while (index < files.size() && entries.size() < size && visited++ < 32) {
      Path file = files.get(index);
      long end = offset >= 0 ? offset : Files.size(file);
      if (end < 0 || end > Files.size(file))
        throw new IllegalArgumentException("Journal cursor offset is outside segment");
      if (end == 0) {
        index++;
        offset = -1;
        continue;
      }
      long start = Math.max(0, end - (1 << 20));
      byte[] data;
      try (var input = new RandomAccessFile(file.toFile(), "r")) {
        input.seek(start);
        data = new byte[(int) (end - start)];
        input.readFully(data);
      }
      int stop = data.length;
      while (stop > 0 && entries.size() < size) {
        if (data[stop - 1] == '\n') stop--;
        if (stop == 0) {
          end = start;
          break;
        }
        int begin = stop - 1;
        while (begin >= 0 && data[begin] != '\n') begin--;
        if (begin < 0 && start > 0) {
          end = stop < 65536 ? start + stop : start;
          break;
        }
        int lineStart = begin + 1;
        if (stop - lineStart <= 65536) {
          try {
            var entry =
                GSON.fromJson(
                    new String(data, lineStart, stop - lineStart, StandardCharsets.UTF_8),
                    Entry.class);
            if (entry != null && owner.equals(entry.owner()) && actor.equals(entry.actor()))
              entries.add(entry);
          } catch (RuntimeException ignored) {
            /* A partial/corrupt record does not hide older entries. */
          }
        }
        end = start + lineStart;
        stop = lineStart;
      }
      if (entries.size() == size || end > 0)
        return new Page(List.copyOf(entries), file.getFileName() + ":" + end);
      index++;
      offset = -1;
    }
    String next =
        index < files.size()
            ? files.get(index).getFileName() + ":" + Files.size(files.get(index))
            : "";
    return new Page(List.copyOf(entries), next);
  }
}

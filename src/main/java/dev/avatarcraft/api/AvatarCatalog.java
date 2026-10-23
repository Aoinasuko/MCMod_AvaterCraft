package dev.avatarcraft.api;

import dev.avatarcraft.avatar.AvatarArchive;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Thread-safe snapshot catalog shared by integrations. File IO is explicit, never part of a tick.
 */
public final class AvatarCatalog {
  public record Entry(Path path, AvatarArchive archive) {}

  public record Problem(String file, String message) {}

  private final Path directory;
  private volatile Map<String, Entry> entries = Map.of();
  private volatile List<Problem> problems = List.of();

  public AvatarCatalog(Path directory) {
    this.directory = directory.toAbsolutePath().normalize();
  }

  public synchronized void reload() throws IOException {
    Files.createDirectories(directory);
    var next = new LinkedHashMap<String, Entry>();
    var errors = new ArrayList<Problem>();
    try (var paths = Files.list(directory)) {
      for (var path :
          paths
              .filter(p -> p.getFileName().toString().endsWith(".avc"))
              .sorted()
              .limit(256)
              .toList()) {
        try {
          if (!path.toRealPath().startsWith(directory.toRealPath()))
            throw new IOException("Avatar symlink escapes catalog directory");
          var archive = AvatarArchive.read(path);
          var entry = new Entry(path, archive);
          String id = archive.avatar().id;
          if (next.putIfAbsent(id, entry) != null) throw new IOException("Duplicate avatar ID");
        } catch (Exception e) {
          errors.add(new Problem(path.getFileName().toString(), e.getMessage()));
        }
      }
    }
    var canonical = Map.copyOf(next);
    for (var entry : canonical.values()) {
      String alias = entry.path().getFileName().toString().replaceFirst("\\.avc$", "");
      if (!validId(alias) || alias.equals(entry.archive().avatar().id)) continue;
      if (canonical.containsKey(alias)) {
        errors.add(
            new Problem(
                entry.path().getFileName().toString(),
                "Filename alias collides with canonical ID"));
        continue;
      }
      next.put(alias, entry);
    }
    entries = Collections.unmodifiableMap(next);
    problems = List.copyOf(errors);
  }

  public Optional<Entry> find(String id) {
    return validId(id) ? Optional.ofNullable(entries.get(id)) : Optional.empty();
  }

  public List<Entry> entries() {
    return entries.values().stream().distinct().toList();
  }

  public List<Problem> problems() {
    return problems;
  }

  public static boolean validId(String id) {
    return id != null && id.matches("[a-z0-9_-]{1,64}");
  }
}

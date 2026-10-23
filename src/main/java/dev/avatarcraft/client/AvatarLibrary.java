package dev.avatarcraft.client;

import dev.avatarcraft.api.AvatarCatalog;
import dev.avatarcraft.avatar.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.neoforged.fml.loading.FMLPaths;

/** Archive IO occurs on a worker. GPU resources are created lazily when drawing. */
public final class AvatarLibrary {
  public static final Path ROOT = FMLPaths.CONFIGDIR.get().resolve("avatarcraft");

  public record Entry(Path path, AvatarArchive archive, String error) {
    public String name() {
      return archive == null ? path.getFileName().toString() : archive.avatar().name;
    }
  }

  private static volatile List<Entry> entries = List.of();
  private static volatile CompletableFuture<List<Entry>> pending;

  public static List<Entry> entries() {
    return entries;
  }

  public static Entry resolve(String id) {
    if (!AvatarCatalog.validId(id)) return null;
    var canonical =
        entries.stream()
            .filter(e -> e.archive() != null && e.archive().avatar().id.equals(id))
            .findFirst();
    return canonical.orElseGet(
        () ->
            entries.stream()
                .filter(
                    e ->
                        e.archive() != null
                            && e.path().getFileName().toString().equals(id + ".avc"))
                .findFirst()
                .orElse(null));
  }

  public static boolean loading() {
    return pending != null && !pending.isDone();
  }

  public static synchronized void reloadAfterCurrent() {
    if (loading()) pending.whenComplete((entries, error) -> reload());
    else reload();
  }

  public static synchronized void reload() {
    if (loading()) return;
    pending =
        CompletableFuture.supplyAsync(
            () -> {
              var list = new ArrayList<Entry>();
              try {
                Files.createDirectories(ROOT.resolve("avatars"));
                Files.createDirectories(ROOT.resolve("expressions"));
                var catalog = new AvatarCatalog(ROOT.resolve("avatars"));
                catalog.reload();
                for (var entry : catalog.entries())
                  list.add(new Entry(entry.path(), entry.archive(), null));
                for (var problem : catalog.problems())
                  list.add(
                      new Entry(
                          ROOT.resolve("avatars").resolve(problem.file()),
                          null,
                          problem.message()));
              } catch (IOException e) {
                list.add(new Entry(ROOT, null, e.getMessage()));
              }
              entries = List.copyOf(list);
              return entries;
            });
  }

  public static void atomicWrite(Path target, String content) throws IOException {
    Files.createDirectories(target.getParent());
    var tmp = Files.createTempFile(target.getParent(), ".avatarcraft-", ".tmp");
    try {
      Files.writeString(tmp, content);
      try {
        Files.move(
            tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  public static Path expressionPath(String id) {
    if (!id.matches("[a-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid avatar ID");
    return ROOT.resolve("expressions").resolve(id + ".json");
  }
}

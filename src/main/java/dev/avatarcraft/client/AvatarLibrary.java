package dev.avatarcraft.client;

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

  public static boolean loading() {
    return pending != null && !pending.isDone();
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
                try (var paths = Files.list(ROOT.resolve("avatars"))) {
                  for (var p :
                      paths
                          .filter(p -> p.toString().endsWith(".avc"))
                          .sorted()
                          .limit(256)
                          .toList()) {
                    try {
                      list.add(new Entry(p, AvatarArchive.read(p), null));
                    } catch (Exception e) {
                      list.add(new Entry(p, null, e.getMessage()));
                    }
                  }
                }
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

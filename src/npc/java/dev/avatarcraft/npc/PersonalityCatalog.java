package dev.avatarcraft.npc;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Bounded host-side optional profiles; the model itself remains a separate external .avc. */
public final class PersonalityCatalog {
  public static List<Personality> load(Path root) throws IOException {
    Files.createDirectories(root);
    var result = new LinkedHashMap<String, Personality>();
    result.put("tortobu", Personality.preset("tortobu"));
    try (var files = Files.list(root)) {
      for (var path :
          files
              .filter(
                  p ->
                      !Files.isSymbolicLink(p)
                          && Files.isRegularFile(p)
                          && p.getFileName().toString().endsWith(".json"))
              .sorted()
              .limit(12)
              .toList()) {
        try {
          if (Files.size(path) > 16384) continue;
          var value = Personality.parse(Files.readString(path));
          if (path.getFileName().toString().equals(value.id + ".json")) result.put(value.id, value);
        } catch (IOException | RuntimeException ignored) {
          /* An invalid optional profile must not hide the standard one. */
        }
      }
    }
    return List.copyOf(result.values());
  }

  private PersonalityCatalog() {}
}

package dev.avatarcraft.npc.ai;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Read-only bilingual retrieval for the existing instruction AI, never a task executor. */
public final class NpcInstructionExamples {
  private static final List<JsonObject> EXAMPLES = load();

  private static List<JsonObject> load() {
    try (var in =
        NpcInstructionExamples.class.getResourceAsStream(
            "/data/bep_3davater_companion/ai/instruction-examples.json")) {
      if (in == null) throw new IOException("Missing instruction curriculum");
      byte[] bytes = in.readNBytes(1048577);
      if (bytes.length > 1048576) throw new IOException("Instruction curriculum too large");
      var all = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonArray();
      if (all.size() > 1024) throw new IOException("Too many instruction examples");
      return all.asList().stream().map(JsonElement::getAsJsonObject).toList();
    } catch (IOException e) {
      throw new IllegalStateException("Cannot load instruction curriculum", e);
    }
  }

  public static String relevant(String instruction) {
    String text = instruction.toLowerCase(Locale.ROOT);
    var selected =
        EXAMPLES.stream()
            .filter(e -> score(e, text) > 0)
            .sorted(Comparator.comparingInt((JsonObject e) -> score(e, text)).reversed())
            .limit(3)
            .toList();
    var out = new JsonArray();
    for (var entry : selected) {
      var example = entry.deepCopy();
      example.remove("aliases");
      if (out.toString().length() + example.toString().length() > 6000) break;
      out.add(example);
    }
    return out.toString();
  }

  private static int score(JsonObject e, String text) {
    int score = 0;
    var keys = new ArrayList<String>();
    keys.add(e.get("ja").getAsString());
    keys.add(e.get("en").getAsString());
    for (var key : e.getAsJsonArray("aliases")) keys.add(key.getAsString());
    for (var key : keys)
      if (text.contains(key.toLowerCase(Locale.ROOT))) score = Math.max(score, key.length());
    return score;
  }
}

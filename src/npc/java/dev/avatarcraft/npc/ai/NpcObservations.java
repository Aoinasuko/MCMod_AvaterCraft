package dev.avatarcraft.npc.ai;

import com.google.gson.*;

/** Bounded copies for journals/prompts; actual tool arguments/results remain untouched. */
public final class NpcObservations {
  public static JsonObject bounded(JsonObject original, int limit) {
    if (limit < 256) throw new IllegalArgumentException("Observation limit must be at least 256");
    var copy = original.deepCopy();
    int attempts = 0;
    while (copy.toString().length() > limit && attempts++ < 512) {
      var array = largestArray(copy, null);
      if (array != null && !array.isEmpty()) {
        int remove = Math.max(1, array.size() / 4);
        for (int i = 0; i < remove; i++) array.remove(array.size() - 1);
        copy.addProperty("truncated", true);
        continue;
      }
      if (trimString(copy)) {
        copy.addProperty("truncated", true);
        continue;
      }
      var fallback = new JsonObject();
      fallback.addProperty("truncated", true);
      if (original.has("ok")) fallback.add("ok", original.get("ok"));
      fallback.addProperty(
          "message", "Observation exceeded the bounded context; inspect a specific entity/block");
      return fallback;
    }
    if (copy.toString().length() > limit) {
      var fallback = new JsonObject();
      fallback.addProperty("truncated", true);
      fallback.addProperty("message", "Observation omitted for size");
      return fallback;
    }
    return copy;
  }

  private static JsonArray largestArray(JsonElement value, JsonArray best) {
    if (value.isJsonArray()) {
      var a = value.getAsJsonArray();
      if (!a.isEmpty() && (best == null || a.toString().length() > best.toString().length()))
        best = a;
      for (var e : a) best = largestArray(e, best);
    } else if (value.isJsonObject())
      for (var e : value.getAsJsonObject().entrySet()) best = largestArray(e.getValue(), best);
    return best;
  }

  private static boolean trimString(JsonObject value) {
    for (var e : value.entrySet()) {
      if (e.getValue().isJsonPrimitive()
          && e.getValue().getAsJsonPrimitive().isString()
          && e.getValue().getAsString().length() > 256) {
        value.addProperty(
            e.getKey(), e.getValue().getAsString().substring(0, 256) + " [omitted for size]");
        return true;
      }
      if (e.getValue().isJsonObject() && trimString(e.getValue().getAsJsonObject())) return true;
    }
    return false;
  }

  private NpcObservations() {}
}

package dev.avatarcraft.npc.ai;

import com.google.gson.*;
import java.util.*;

/** Bounded copies for journals/prompts; actual tool arguments/results remain untouched. */
public final class NpcObservations {
  public static JsonObject bounded(JsonObject original, int limit) {
    if (limit < 256) throw new IllegalArgumentException("Observation limit must be at least 256");
    var copy = compact(original);
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
      if (original.has("inventory")) fallback.addProperty("inventory_complete", false);
      if (original.has("ok")) fallback.add("ok", original.get("ok"));
      fallback.addProperty(
          "message", "Observation exceeded the bounded context; inspect a specific entity/block");
      return fallback;
    }
    if (copy.toString().length() > limit) {
      var fallback = new JsonObject();
      fallback.addProperty("truncated", true);
      if (original.has("inventory")) fallback.addProperty("inventory_complete", false);
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
      for (var e : value.getAsJsonObject().entrySet())
        if (!e.getKey().equals("inventory")) best = largestArray(e.getValue(), best);
    return best;
  }

  /** Essential inventory is outside the optional-context budget; it is never a partial list. */
  public static JsonObject planning(JsonObject original, int limit) {
    var copy = compact(original);
    var essential = new JsonObject();
    for (String key :
        List.of(
            "inventory", "item_counts", "equipped_slots", "free_bag_slots", "inventory_complete", "command_context"))
      if (copy.has(key)) essential.add(key, copy.remove(key));
    var other = bounded(copy, Math.max(256, limit - essential.toString().length() - 32));
    essential.entrySet().forEach(e -> other.add(e.getKey(), e.getValue()));
    return other;
  }

  private static JsonObject compact(JsonObject original) {
    var copy = original.deepCopy();
    compactInventories(copy);
    return copy;
  }

  private static void compactInventories(JsonElement value) {
    if (value.isJsonArray()) {
      for (var e : value.getAsJsonArray()) compactInventories(e);
    } else if (value.isJsonObject()) {
      var o = value.getAsJsonObject();
      if (o.has("inventory") && o.get("inventory").isJsonArray()) {
        var items = new JsonArray();
        var counts = new JsonObject();
        var equipped = new JsonObject();
        var occupied = new HashSet<Integer>();
        for (var e : o.getAsJsonArray("inventory")) {
          var item = e.getAsJsonObject();
          var row = new JsonObject();
          for (String k : List.of("slot", "item", "count", "equipment", "damage", "max_damage"))
            if (item.has(k)
                && (!k.equals("equipment") || !item.get(k).getAsString().equals("bag"))
                && (!Set.of("damage", "max_damage").contains(k)
                    || item.has("max_damage") && item.get("max_damage").getAsInt() > 0))
              row.add(k, item.get(k).deepCopy());
          items.add(row);
          int slot = item.get("slot").getAsInt(), count = item.get("count").getAsInt();
          String id = item.get("item").getAsString();
          counts.addProperty(id, (counts.has(id) ? counts.get(id).getAsInt() : 0) + count);
          if (slot < 36 && count > 0) occupied.add(slot);
          if (slot >= 36)
            equipped.addProperty(
                item.has("equipment") ? item.get("equipment").getAsString() : "slot_" + slot, slot);
        }
        o.add("inventory", items);
        o.add("item_counts", counts);
        o.add("equipped_slots", equipped);
        o.addProperty("free_bag_slots", 36 - occupied.size());
        o.addProperty("inventory_complete", true);
      }
      for (var e : new ArrayList<>(o.entrySet()))
        if (!e.getKey().equals("inventory")) compactInventories(e.getValue());
    }
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

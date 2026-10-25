package dev.avatarcraft.npc;

import com.google.gson.*;
import java.text.Normalizer;
import java.util.*;

/** Shared, bounded world locations. No owner restriction and no per-NPC memory slots. */
public final class AnchorIndex {
  public static final int MAX_IDS = 4096;
  public static final int CHEST_RADIUS = 8;
  private static final Gson GSON = new Gson();

  public record Entry(String name, String dimension, int x, int y, int z) {
    public Entry {
      name = normalize(name);
      if (name.isEmpty()
          || dimension == null
          || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
          || dimension.length() > 128
          || Math.abs((long) x) > 30000000
          || Math.abs((long) z) > 30000000
          || y < -2048
          || y > 4096) throw new IllegalArgumentException("Invalid anchor position");
    }

    public boolean at(String dim, int bx, int by, int bz) {
      return dimension.equals(dim) && x == bx && y == by && z == bz;
    }

    public JsonObject observation() {
      var o = GSON.toJsonTree(this).getAsJsonObject();
      o.addProperty("kind", "anchor");
      o.addProperty("chest_radius", CHEST_RADIUS);
      return o;
    }
  }

  private final NavigableMap<String, Entry> entries = new TreeMap<>();

  public static String normalize(String name) {
    if (name == null) throw new IllegalArgumentException("anchor.invalid_id");
    var id = Normalizer.normalize(name.strip(), Normalizer.Form.NFC);
    if (id.length() > 32
        || id.codePoints()
            .anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT))
      throw new IllegalArgumentException("anchor.invalid_id");
    return id;
  }

  public Entry get(String id) {
    return entries.get(normalize(id));
  }

  public List<Entry> entries() {
    return List.copyOf(entries.values());
  }

  public void put(Entry value) {
    var old = entries.get(value.name);
    if (old != null && !old.at(value.dimension, value.x, value.y, value.z))
      throw new IllegalArgumentException("anchor.duplicate_id");
    boolean samePlace =
        entries.values().stream().anyMatch(e -> e.at(value.dimension, value.x, value.y, value.z));
    if (old == null && !samePlace && entries.size() >= MAX_IDS)
      throw new IllegalArgumentException("anchor.limit");
    removeAt(value.dimension, value.x, value.y, value.z);
    entries.put(value.name, value);
  }

  public boolean removeAt(String dim, int x, int y, int z) {
    return entries.values().removeIf(e -> e.at(dim, x, y, z));
  }

  public String json() {
    return GSON.toJson(entries.values());
  }

  public static AnchorIndex parse(String json) {
    if (json == null || json.length() > 2097152)
      throw new IllegalArgumentException("Invalid anchor index size");
    var a = JsonParser.parseString(json).getAsJsonArray();
    if (a.size() > MAX_IDS) throw new IllegalArgumentException("Too many anchors");
    var index = new AnchorIndex();
    for (var row : a) {
      var e = GSON.fromJson(row, Entry.class);
      if (e == null
          || index.get(e.name) != null
          || index.entries.values().stream().anyMatch(old -> old.at(e.dimension, e.x, e.y, e.z)))
        throw new IllegalArgumentException("Duplicate saved anchor");
      index.put(e);
    }
    return index;
  }
}

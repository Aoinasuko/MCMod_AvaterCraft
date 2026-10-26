package dev.avatarcraft.npc;

import com.google.gson.*;
import java.util.*;

/** Eight named, last-known locations per NPC. Independent of chat and provider credentials. */
public final class NpcLocationMemory {
  public static final int CAPACITY = 8;

  public record Place(String name, String dimension, int x, int y, int z, String kind) {
    public Place {
      if (name == null
          || name.isBlank()
          || name.length() > 32
          || name.codePoints().anyMatch(Character::isISOControl)
          || dimension == null
          || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
          || dimension.length() > 128
          || Math.abs((long) x) > 30000000
          || Math.abs((long) z) > 30000000
          || y < -2048
          || y > 4096
          || !Set.of("point", "chest").contains(kind))
        throw new IllegalArgumentException("Invalid remembered location");
    }
  }

  private static final Gson GSON = new Gson();
  private final LinkedHashMap<String, Place> places = new LinkedHashMap<>();

  public boolean remember(Place place) {
    if (!places.containsKey(place.name()) && places.size() >= CAPACITY) return false;
    places.put(place.name(), place);
    return true;
  }

  public Place chest(String dimension, int x, int y, int z) {
    for (var place : places.values())
      if (place.dimension.equals(dimension)
          && place.x == x
          && place.y == y
          && place.z == z
          && place.kind.equals("chest")) return place;
    for (int i = 1; i <= CAPACITY; i++)
      if (!places.containsKey("chest_" + i)) {
        var place = new Place("chest_" + i, dimension, x, y, z, "chest");
        return remember(place) ? place : null;
      }
    return null;
  }

  public Place get(String name) {
    return places.get(name);
  }

  public boolean forget(String name) {
    return places.remove(name) != null;
  }

  public int clear() {
    int count = places.size();
    places.clear();
    return count;
  }

  public List<Place> entries() {
    return List.copyOf(places.values());
  }

  public JsonArray snapshot() {
    return GSON.toJsonTree(entries()).getAsJsonArray();
  }

  public String json() {
    return snapshot().toString();
  }

  public void load(String json) {
    if (json == null || json.length() > 8192)
      throw new IllegalArgumentException("Oversized location memory");
    var array = JsonParser.parseString(json).getAsJsonArray();
    if (array.size() > CAPACITY) throw new IllegalArgumentException("Too many locations");
    var parsed = new LinkedHashMap<String, Place>();
    for (var e : array) {
      var p = GSON.fromJson(e, Place.class);
      if (p == null || parsed.putIfAbsent(p.name, p) != null)
        throw new IllegalArgumentException("Duplicate location name");
    }
    places.clear();
    places.putAll(parsed);
  }
}

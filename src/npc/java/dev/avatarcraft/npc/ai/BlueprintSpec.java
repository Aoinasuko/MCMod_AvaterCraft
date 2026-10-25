package dev.avatarcraft.npc.ai;

import com.google.gson.*;
import java.util.*;

/**
 * Compact AI-authored geometry; boxes expand deterministically, later parts override earlier ones.
 */
public record BlueprintSpec(
    String name, int width, int height, int depth, Map<Cell, String> blocks) {
  public record Cell(int x, int y, int z) {}

  public static BlueprintSpec parse(String json) {
    if (json.length() > 8192)
      throw new IllegalArgumentException("Blueprint JSON exceeds 8192 characters");
    var o = JsonParser.parseString(json).getAsJsonObject();
    if (!o.keySet().equals(Set.of("name", "size", "parts")))
      throw new IllegalArgumentException("Blueprint needs name,size,parts");
    String name = o.get("name").getAsString();
    if (name.isBlank() || name.length() > 64)
      throw new IllegalArgumentException("Blueprint name 1..64 required");
    var size = point(o.getAsJsonArray("size"));
    int w = size.x, h = size.y, d = size.z;
    if (w < 1 || h < 1 || d < 1 || w > 32 || h > 32 || d > 32 || (long) w * h * d > 4096)
      throw new IllegalArgumentException("Blueprint sides <=32 and volume <=4096");
    var parts = o.getAsJsonArray("parts");
    if (parts.isEmpty() || parts.size() > 64)
      throw new IllegalArgumentException("Blueprint requires 1..64 parts");
    var cells = new LinkedHashMap<Cell, String>();
    for (var entry : parts) {
      var p = entry.getAsJsonObject();
      if (!p.keySet().equals(Set.of("from", "to", "block")))
        throw new IllegalArgumentException("Part requires from,to,block");
      var a = point(p.getAsJsonArray("from"));
      var b = point(p.getAsJsonArray("to"));
      String block = p.get("block").getAsString();
      if (!block.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
        throw new IllegalArgumentException("Exact block ID required");
      for (var c : List.of(a, b))
        if (c.x < 0 || c.x >= w || c.y < 0 || c.y >= h || c.z < 0 || c.z >= d)
          throw new IllegalArgumentException("Part exceeds designed footprint");
      for (int y = Math.min(a.y, b.y); y <= Math.max(a.y, b.y); y++)
        for (int z = Math.min(a.z, b.z); z <= Math.max(a.z, b.z); z++)
          for (int x = Math.min(a.x, b.x); x <= Math.max(a.x, b.x); x++) {
            var c = new Cell(x, y, z);
            if (block.equals("minecraft:air")) cells.remove(c);
            else cells.put(c, block);
          }
    }
    if (cells.isEmpty()) throw new IllegalArgumentException("Blueprint contains no blocks");
    return new BlueprintSpec(name, w, h, d, Collections.unmodifiableMap(cells));
  }

  public Map<String, Integer> materials() {
    var m = new TreeMap<String, Integer>();
    blocks.values().forEach(id -> m.merge(id, 1, Integer::sum));
    return m;
  }

  private static Cell point(JsonArray a) {
    if (a.size() != 3) throw new IllegalArgumentException("Three integer coordinates required");
    int[] p = new int[3];
    for (int i = 0; i < 3; i++) {
      var v = a.get(i);
      if (!v.isJsonPrimitive()
          || !v.getAsJsonPrimitive().isNumber()
          || !Double.isFinite(v.getAsDouble())
          || v.getAsDouble() != Math.rint(v.getAsDouble())
          || Math.abs(v.getAsDouble()) > 30000000)
        throw new IllegalArgumentException("Integer coordinate required");
      p[i] = v.getAsInt();
    }
    return new Cell(p[0], p[1], p[2]);
  }
}

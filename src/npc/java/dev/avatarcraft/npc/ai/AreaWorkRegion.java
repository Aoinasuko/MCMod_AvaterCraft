package dev.avatarcraft.npc.ai;

import com.google.gson.JsonObject;
import java.util.Set;

/** A bounded inclusive cuboid and a deterministic layer/serpentine traversal, independent of MC. */
public record AreaWorkRegion(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
  public static final Set<String> OPERATIONS = Set.of("dig", "fill", "till", "plant");

  public record Cell(int x, int y, int z) {}

  public AreaWorkRegion {
    long x = (long) maxX - minX + 1, y = (long) maxY - minY + 1, z = (long) maxZ - minZ + 1;
    if (x < 1
        || y < 1
        || z < 1
        || x > 32
        || y > 32
        || z > 32
        || x * y * z > 4096
        || Math.abs((long) minX) > 30000000
        || Math.abs((long) maxX) > 30000000
        || Math.abs((long) minZ) > 30000000
        || Math.abs((long) maxZ) > 30000000
        || minY < -2048
        || maxY > 4096)
      throw new IllegalArgumentException(
          "AREA_BOUNDS: inclusive sides <=32 and volume <=4096 required");
  }

  public static AreaWorkRegion from(JsonObject args) {
    String op = args.has("operation") ? args.get("operation").getAsString() : "";
    if (op.isEmpty()) return bounds(args);
    String block = args.get("block").getAsString(), item = args.get("item").getAsString();
    if (!OPERATIONS.contains(op)) throw new IllegalArgumentException("Unknown area operation");
    if (!block.matches("|#?[a-z0-9_.-]+:[a-z0-9_./-]+")
        || block.length() > 128
        || !item.matches("|[a-z0-9_.-]+:[a-z0-9_./-]+")
        || item.length() > 128
        || !op.equals("dig") && !block.isEmpty()
        || Set.of("dig", "till").contains(op) && !item.isEmpty()
        || Set.of("fill", "plant").contains(op) && item.isEmpty())
      throw new IllegalArgumentException(
          "Area dig uses block; fill/plant use item; unused text must be empty");
    return bounds(args);
  }

  public static AreaWorkRegion bounds(JsonObject args) {
    return new AreaWorkRegion(
        Math.min(args.get("x1").getAsInt(), args.get("x2").getAsInt()),
        Math.min(args.get("y1").getAsInt(), args.get("y2").getAsInt()),
        Math.min(args.get("z1").getAsInt(), args.get("z2").getAsInt()),
        Math.max(args.get("x1").getAsInt(), args.get("x2").getAsInt()),
        Math.max(args.get("y1").getAsInt(), args.get("y2").getAsInt()),
        Math.max(args.get("z1").getAsInt(), args.get("z2").getAsInt()));
  }

  public int volume() {
    return (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
  }

  public Cell cell(int index, boolean topDown) {
    if (index < 0 || index >= volume()) throw new IndexOutOfBoundsException(index);
    int width = maxX - minX + 1, depth = maxZ - minZ + 1;
    int layer = index / (width * depth), row = index / width % depth, column = index % width;
    return new Cell(
        row % 2 == 0 ? minX + column : maxX - column,
        topDown ? maxY - layer : minY + layer,
        minZ + row);
  }
}

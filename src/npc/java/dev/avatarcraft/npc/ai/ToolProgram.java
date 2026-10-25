package dev.avatarcraft.npc.ai;

import com.google.gson.*;
import java.util.*;
import java.util.regex.Pattern;

/** Data-only tool templates. No evaluated expressions and no scheduler recursion. */
public final class ToolProgram {
  public record Node(
      String id,
      String tool,
      JsonObject arguments,
      int priority,
      List<String> after,
      JsonObject when,
      JsonObject expect,
      boolean required,
      String item,
      AreaWorkRegion.Cell cell) {}

  public static final Set<String> ORDERS = Set.of("top_down", "bottom_up");
  private static final Pattern VARIABLE = Pattern.compile("\\$([xyz])([+-][0-9]{1,2})?");
  private static final Set<String> SCHEDULERS =
      Set.of(
          "area_work",
          "create_blueprint",
          "build_blueprint",
          "gather_blueprint",
          "cancel_blueprint");

  public static List<Node> expand(AreaWorkRegion region, String order, String json) {
    if (!ORDERS.contains(order) || json.length() > 8192)
      throw new IllegalArgumentException("Invalid program order or size");
    var templates = JsonParser.parseString(json).getAsJsonArray();
    if (templates.isEmpty()
        || templates.size() > 16
        || (long) templates.size() * region.volume() > 8192)
      throw new IllegalArgumentException(
          "Program requires 1..16 templates and <=8192 expanded nodes");
    var ids = new HashSet<String>();
    for (var t : templates) {
      var o = t.getAsJsonObject();
      if (!Set.of(
                  "id",
                  "tool",
                  "arguments",
                  "priority",
                  "after",
                  "when",
                  "expect",
                  "required",
                  "item")
              .containsAll(o.keySet())
          || !o.has("id")
          || !o.has("tool")
          || !o.has("arguments"))
        throw new IllegalArgumentException("Unknown/missing program fields");
      String id = o.get("id").getAsString(), tool = o.get("tool").getAsString();
      if (o.has("required")
          && (!o.get("required").isJsonPrimitive()
              || !o.getAsJsonPrimitive("required").isBoolean()))
        throw new IllegalArgumentException("required must be a boolean");
      if (o.has("item")
          && (!o.get("item").isJsonPrimitive()
              || !o.getAsJsonPrimitive("item").isString()
              || !o.get("item").getAsString().matches("|[a-z0-9_.-]+:[a-z0-9_./-]+")))
        throw new IllegalArgumentException("item must be an exact item ID or empty");
      if (!id.matches("[a-zA-Z0-9_-]{1,32}")
          || !ids.add(id)
          || !NpcTools.names().contains(tool)
          || SCHEDULERS.contains(tool))
        throw new IllegalArgumentException("Unknown, duplicate or recursive program node");
    }
    var nodes = new ArrayList<Node>();
    for (int i = 0; i < region.volume(); i++) {
      var cell = region.cell(i, order.equals("top_down"));
      for (var t : templates) {
        var o = t.getAsJsonObject();
        var args = bind(o.getAsJsonObject("arguments"), cell, false);
        var probe = bind(o.getAsJsonObject("arguments"), cell, true);
        String tool = o.get("tool").getAsString();
        NpcTools.validate(tool, probe, NpcTools.names());
        if (args.has("x") && args.has("y") && args.has("z")) {
          int x = (int) Math.floor(args.get("x").getAsDouble()),
              y = (int) Math.floor(args.get("y").getAsDouble()),
              z = (int) Math.floor(args.get("z").getAsDouble());
          if (x < region.minX()
              || x > region.maxX()
              || y < region.minY()
              || y > region.maxY()
              || z < region.minZ()
              || z > region.maxZ())
            throw new IllegalArgumentException("Program coordinate escapes its explicit region");
        }
        var after = new ArrayList<String>();
        if (o.has("after"))
          for (var a : o.getAsJsonArray("after")) {
            if (!ids.contains(a.getAsString()))
              throw new IllegalArgumentException("Unknown dependency");
            after.add(i + ":" + a.getAsString());
          }
        int priority = o.has("priority") ? integer(o.get("priority")) : 0;
        if (Math.abs((long) priority) > 1000)
          throw new IllegalArgumentException("Priority -1000..1000 required");
        nodes.add(
            new Node(
                i + ":" + o.get("id").getAsString(),
                tool,
                args,
                priority,
                List.copyOf(after),
                condition(o, "when"),
                condition(o, "expect"),
                !o.has("required") || o.get("required").getAsBoolean(),
                o.has("item") ? o.get("item").getAsString() : "",
                cell));
      }
    }
    validateDependencies(nodes);
    nodes.sort(Comparator.comparingInt(Node::priority).reversed());
    return List.copyOf(nodes);
  }

  public static void validateDependencies(List<Node> nodes) {
    var map = new HashMap<String, Node>();
    for (var n : nodes)
      if (map.put(n.id, n) != null) throw new IllegalArgumentException("Duplicate node ID");
    var done = new HashSet<String>();
    var visiting = new HashSet<String>();
    for (var n : nodes) visit(n, map, done, visiting);
  }

  private static void visit(
      Node n, Map<String, Node> nodes, Set<String> done, Set<String> visiting) {
    if (done.contains(n.id)) return;
    if (!visiting.add(n.id)) throw new IllegalArgumentException("Program dependency cycle");
    for (var a : n.after) {
      var p = nodes.get(a);
      if (p == null) throw new IllegalArgumentException("Unknown dependency");
      visit(p, nodes, done, visiting);
    }
    visiting.remove(n.id);
    done.add(n.id);
  }

  private static int integer(JsonElement e) {
    if (!e.isJsonPrimitive()
        || !e.getAsJsonPrimitive().isNumber()
        || e.getAsDouble() != Math.rint(e.getAsDouble())
        || Math.abs(e.getAsDouble()) > 30000000)
      throw new IllegalArgumentException("Integer program argument required");
    return e.getAsInt();
  }

  private static JsonObject condition(JsonObject o, String key) {
    var c = o.has(key) ? o.getAsJsonObject(key).deepCopy() : new JsonObject();
    if (!Set.of("block", "above").containsAll(c.keySet()))
      throw new IllegalArgumentException("Unknown state predicate");
    for (var p : c.entrySet())
      if (!p.getValue().isJsonPrimitive()
          || !p.getValue().getAsJsonPrimitive().isString()
          || !p.getValue().getAsString().matches("#?[a-z0-9_.-]+:[a-z0-9_./-]+"))
        throw new IllegalArgumentException("Invalid block predicate");
    return c;
  }

  private static JsonObject bind(JsonObject raw, AreaWorkRegion.Cell c, boolean probe) {
    var args = raw.deepCopy();
    for (var e : args.entrySet())
      if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isString()) {
        var s = e.getValue().getAsString();
        var m = VARIABLE.matcher(s);
        if (m.matches()) {
          int base =
              switch (m.group(1)) {
                case "x" -> c.x();
                case "y" -> c.y();
                default -> c.z();
              };
          args.addProperty(
              e.getKey(), base + (m.group(2) == null ? 0 : Integer.parseInt(m.group(2))));
        } else if (Set.of("$item", "$hoe").contains(s)) {
          if (!e.getKey().equals("slot"))
            throw new IllegalArgumentException("Inventory binding only allowed for slot");
          if (probe) args.addProperty(e.getKey(), 0);
        } else if (s.startsWith("$")) throw new IllegalArgumentException("Unknown variable");
      }
    return args;
  }

  private ToolProgram() {}

  /** Examples compile to the same public template contract; execution has no operation switch. */
  public static JsonArray example(String operation, String block, String item) {
    var args = new JsonObject();
    args.addProperty("x", "$x");
    args.addProperty("y", "$y");
    args.addProperty("z", "$z");
    var when = new JsonObject();
    var expect = new JsonObject();
    String tool;
    switch (operation) {
      case "dig" -> {
        tool = "dig";
        if (!block.isEmpty()) when.addProperty("block", block);
        expect.addProperty("block", "minecraft:air");
      }
      case "fill" -> {
        tool = "place";
        args.addProperty("slot", "$item");
        expect.addProperty("block", item);
      }
      case "till" -> {
        tool = "use_on_block";
        args.addProperty("slot", "$hoe");
        args.addProperty("face", "up");
        expect.addProperty("block", "minecraft:farmland");
      }
      case "plant" -> {
        tool = "use_on_block";
        args.addProperty("slot", "$item");
        args.addProperty("face", "up");
        when.addProperty("block", "minecraft:farmland");
        String crop =
            switch (item) {
              case "minecraft:wheat_seeds" -> "minecraft:wheat";
              case "minecraft:carrot" -> "minecraft:carrots";
              case "minecraft:potato" -> "minecraft:potatoes";
              case "minecraft:beetroot_seeds" -> "minecraft:beetroots";
              case "minecraft:pumpkin_seeds" -> "minecraft:pumpkin_stem";
              case "minecraft:melon_seeds" -> "minecraft:melon_stem";
              default ->
                  throw new IllegalArgumentException(
                      "Specify the desired crop predicate explicitly");
            };
        expect.addProperty("above", crop);
      }
      default -> throw new IllegalArgumentException("Unknown example");
    }
    var node = new JsonObject();
    node.addProperty("id", operation);
    node.addProperty("tool", tool);
    node.add("arguments", args);
    node.addProperty("priority", 0);
    node.add("after", new JsonArray());
    node.add("when", when);
    node.add("expect", expect);
    node.addProperty("required", !operation.equals("dig") || block.isEmpty());
    node.addProperty("item", item);
    var out = new JsonArray();
    out.add(node);
    return out;
  }
}

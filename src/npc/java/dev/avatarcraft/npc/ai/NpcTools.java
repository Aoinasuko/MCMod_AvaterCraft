package dev.avatarcraft.npc.ai;

import com.google.gson.*;
import java.util.*;

/** One shared tool contract for Codex, argument validation, and game execution. */
public final class NpcTools {
  private record Spec(String description, Map<String, String> properties) {}

  private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

  static {
    add(
        "observe",
        "Inspect own health, inventory, equipped items, nearby actors and current task.");
    add(
        "move_to",
        "Walk to a loaded position within 64 blocks. Arrival means within 0.6 blocks of its bottom"
            + " center; reports actual position or failure.",
        "x:number",
        "y:number",
        "z:number");
    add(
        "follow",
        "Follow a nearby entity for bounded seconds, sprinting to catch up. The owner's entity ID"
            + " is also available in observe.owner without line of sight.",
        "entity_id:integer",
        "seconds:integer");
    add(
        "equip",
        "Swap a bag slot (0..35) with main or off hand. Choose the correct tool before mining or"
            + " combat. An empty bag slot puts the held item away. Cannot swap while consuming an"
            + " item.",
        "slot:integer",
        "hand:string");
    add(
        "find_blocks",
        "Find matching blocks within radius 16 in loaded chunks, nearest first.",
        "block:string",
        "radius:integer");
    add(
        "dig",
        "Walk to and mine one reachable block using the equipped tool, collecting normal drops.",
        "x:integer",
        "y:integer",
        "z:integer");
    add(
        "place",
        "Place a carried block at an empty reachable position. Consumes the actual carried item.",
        "x:integer",
        "y:integer",
        "z:integer",
        "slot:integer");
    add(
        "attack",
        "Approach and attack a nearby hostile entity using normal equipped attributes.",
        "entity_id:integer");
    add(
        "deposit",
        "Put items from a bag slot into a nearby accessible chest (within four blocks). Use"
            + " find_chests first. Reports exact moved count and preserves overflow.",
        "x:integer",
        "y:integer",
        "z:integer",
        "slot:integer",
        "count:integer");
    add(
        "withdraw",
        "Take items from a nearby accessible chest slot into own bag. Does not drop overflow. Use"
            + " find_chests to inspect chest slot numbers.",
        "x:integer",
        "y:integer",
        "z:integer",
        "slot:integer",
        "count:integer");
    add(
        "give_item",
        "Give items from a bag slot to another NPC or player within four blocks and line of sight."
            + " Reports exact accepted count; excess stays in own bag.",
        "entity_id:integer",
        "slot:integer",
        "count:integer");
    add(
        "find_chests",
        "Inspect accessible loaded nearby chests, their positions and nonempty slot contents,"
            + " nearest first. Radius 1..16.",
        "radius:integer");
    add(
        "craft",
        "Craft one registered recipe from carried materials; requires a nearby crafting table for"
            + " large recipes.",
        "recipe:string");
    add(
        "eat",
        "Equip and start eating food from a carried slot. Observe using_item and food after waiting"
            + " to verify completion.",
        "slot:integer");
    add(
        "say",
        "Say a short Japanese message to nearby actors. A target entity ID of -1 means no"
            + " particular listener.",
        "text:string",
        "target_id:integer");
    add(
        "wait",
        "Wait a bounded number of seconds without performing another action.",
        "seconds:integer");
    add("stop", "Stop current movement and action.");
  }

  private static void add(String name, String description, String... fields) {
    var properties = new LinkedHashMap<String, String>();
    for (String field : fields) {
      var pair = field.split(":");
      properties.put(pair[0], pair[1]);
    }
    SPECS.put(name, new Spec(description, properties));
  }

  public static JsonArray definitions(Collection<String> allowed) {
    var tools = new JsonArray();
    for (String name : allowed) {
      var spec = SPECS.get(name);
      if (spec == null) throw new IllegalArgumentException("Unknown skill");
      var tool = new JsonObject();
      tool.addProperty("type", "function");
      tool.addProperty("name", "npc_" + name);
      tool.addProperty("description", spec.description());
      var schema = new JsonObject();
      schema.addProperty("type", "object");
      schema.addProperty("additionalProperties", false);
      var properties = new JsonObject();
      var required = new JsonArray();
      spec.properties()
          .forEach(
              (key, type) -> {
                var property = new JsonObject();
                property.addProperty("type", type);
                properties.add(key, property);
                required.add(key);
              });
      schema.add("properties", properties);
      schema.add("required", required);
      tool.add("inputSchema", schema);
      tools.add(tool);
    }
    return tools;
  }

  public static void validate(String name, JsonObject args, Collection<String> allowed) {
    var spec = SPECS.get(name);
    if (spec == null
        || !allowed.contains(name)
        || !args.keySet().equals(spec.properties().keySet()))
      throw new IllegalArgumentException("Skill or arguments are not allowed");
    spec.properties()
        .forEach(
            (key, type) -> {
              var value = args.get(key);
              if (value == null || !value.isJsonPrimitive())
                throw new IllegalArgumentException("Invalid argument type");
              var primitive = value.getAsJsonPrimitive();
              if (type.equals("string")) {
                if (!primitive.isString() || value.getAsString().length() > 512)
                  throw new IllegalArgumentException("Invalid skill text");
              } else {
                if (!primitive.isNumber()
                    || !Double.isFinite(value.getAsDouble())
                    || Math.abs(value.getAsDouble()) > 30000000)
                  throw new IllegalArgumentException("Invalid numeric argument");
                if (type.equals("integer") && value.getAsDouble() != Math.rint(value.getAsDouble()))
                  throw new IllegalArgumentException("Integer argument required");
              }
            });
  }

  public static String localName(String remote) {
    if (remote == null || !remote.startsWith("npc_") || !SPECS.containsKey(remote.substring(4)))
      throw new IllegalArgumentException("Unknown NPC tool");
    return remote.substring(4);
  }

  private NpcTools() {}
}

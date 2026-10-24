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
        "Move on foot to a loaded position within 64 blocks, sprinting for distant travel and"
            + " walking near arrival. Arrival means within 0.8 blocks of its bottom center; reports"
            + " actual position or failure.",
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
    add(
        "acquire",
        "Prepare an item from real resources, crafting and furnace smelting, including missing"
            + " tools.",
        "item:string",
        "count:integer");
    add(
        "smelt",
        "Smelt carried ingredients in an accessible furnace, wait for vanilla cooking and collect"
            + " actual output.",
        "x:integer",
        "y:integer",
        "z:integer",
        "item:string",
        "count:integer");
    add(
        "delegate",
        "Assign acquisition and delivery to an available same-owner NPC. entity_id=-1 selects a"
            + " worker.",
        "entity_id:integer",
        "item:string",
        "count:integer");
    add(
        "delegate_task",
        "Assign a validated finite TaskPlan JSON to an idle same-owner teammate. No nested"
            + " delegation. entity_id=-1 selects automatically.",
        "entity_id:integer",
        "plan_json:string");
    add(
        "team_status",
        "Read actual persisted status of this leader's jobs. entity_id=-1 means all workers.",
        "entity_id:integer");
    add(
        "await_teammate",
        "Wait for verified completion of assigned work (not a spoken promise). entity_id=-1 means"
            + " all jobs.",
        "entity_id:integer");
    add(
        "hunt",
        "Approach and perform one attack against a living non-player, non-pet target or boss.",
        "entity_id:integer");
    add(
        "move_precisely",
        "Walk to a loaded block center within 64 blocks, arriving within 0.2 blocks; useful for"
            + " item pickup and narrow routes.",
        "x:number",
        "y:number",
        "z:number");
    add(
        "place_workstation",
        "Place a carried chest, crafting_table or furnace at a verified nearby empty supported"
            + " site; report actual coordinates. Chest placement also records its home location.",
        "item:string");
    add(
        "travel_dimension",
        "Walk into a real observed active portal and use vanilla dimension transfer. No teleport"
            + " shortcuts or portal generation.",
        "dimension:string");
    add(
        "inspect_structures",
        "Inspect actual structure references in loaded chunks only, radius 0..1 chunks.",
        "radius:integer");
    add(
        "explore_structure",
        "Physically explore walkable loaded terrain for a registered structure, with a persisted"
            + " 256-waypoint budget.",
        "structure:string");
    add(
        "look_at",
        "Aim at a world coordinate (within 128 blocks), including ranged shots.",
        "x:number",
        "y:number",
        "z:number");
    add("jump", "Request one normal jump; does not fly or teleport.");
    add("set_sneaking", "Set crouching. enabled=0 or 1.", "enabled:integer");
    add("set_sprinting", "Set sprinting. enabled=0 or 1.", "enabled:integer");
    add(
        "equip_armor",
        "Swap a carried wearable item with its matching armor/equipment slot.",
        "slot:integer");
    add("drop_item", "Drop actual carried items into the world.", "slot:integer", "count:integer");
    add(
        "use_item",
        "Use a carried/mainhand item: ticks=0 for instant use, 1..1200 for charge/hold. Supports"
            + " consumables, projectiles and shields. One empty bag slot is needed for simulated"
            + " player use.",
        "slot:integer",
        "ticks:integer");
    add("release_item", "Stop the current item use/hold.");
    add(
        "use_on_block",
        "Use a carried item on a visible accessible block face: flint and steel, buckets, eye of"
            + " ender, crops and other vanilla item behavior.",
        "x:integer",
        "y:integer",
        "z:integer",
        "slot:integer",
        "face:string");
    add(
        "interact_block",
        "Right-click a visible accessible block with the currently held item.",
        "x:integer",
        "y:integer",
        "z:integer",
        "face:string");
    add(
        "interact_entity",
        "One vanilla right-click interaction with a nearby non-player entity.",
        "entity_id:integer");
    add("mount", "Mount a nearby entity using normal riding checks.", "entity_id:integer");
    add("dismount", "Leave the current vehicle.");
    add(
        "inspect_entities",
        "Inspect actual nearby entity types, positions, velocities, visibility and health. Radius"
            + " 1..64; maximum 64 results.",
        "radius:integer");
    add(
        "inspect_entity",
        "Inspect one present entity or dragon body part, including actual position, velocity,"
            + " visibility and health. Maximum distance 128.",
        "entity_id:integer");
    add(
        "inspect_block",
        "Inspect a loaded block/state and its accessible container contents. Maximum observation"
            + " distance 32; container access still requires reach and permission.",
        "x:integer",
        "y:integer",
        "z:integer");
    add(
        "container_put",
        "Put carried items into one accessible container slot. Respects item restrictions, locks,"
            + " permissions and furnace leases.",
        "x:integer",
        "y:integer",
        "z:integer",
        "slot:integer",
        "container_slot:integer",
        "count:integer");
    add(
        "container_take",
        "Take actual items from one accessible container slot without dropping overflow.",
        "x:integer",
        "y:integer",
        "z:integer",
        "container_slot:integer",
        "count:integer");
    add(
        "furnace_put",
        "Insert actual bag items into an accessible furnace input(0) or fuel(1) slot, respecting"
            + " batch leases.",
        "x:integer",
        "y:integer",
        "z:integer",
        "slot:integer",
        "furnace_slot:integer",
        "count:integer");
    add(
        "furnace_take",
        "Take actual items from an accessible furnace slot 0..2, respecting batch leases.",
        "x:integer",
        "y:integer",
        "z:integer",
        "furnace_slot:integer",
        "count:integer");
    add(
        "remember_location",
        "Remember one of eight named, observed local points or chests.",
        "name:string",
        "x:integer",
        "y:integer",
        "z:integer");
    add(
        "forget_location",
        "Forget a named location, freeing one of eight memory slots.",
        "name:string");
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
                var limits = bounds(name, key);
                if (limits != null) {
                  property.addProperty("minimum", limits[0]);
                  property.addProperty("maximum", limits[1]);
                }
                if (key.equals("face"))
                  property.add(
                      "enum",
                      new Gson()
                          .toJsonTree(List.of("up", "down", "north", "south", "east", "west")));
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
        || !permitted(name, allowed)
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
                if (!primitive.isString()
                    || value.getAsString().length()
                        > (name.equals("delegate_task") && key.equals("plan_json") ? 8192 : 512))
                  throw new IllegalArgumentException("Invalid skill text");
              } else {
                if (!primitive.isNumber()
                    || !Double.isFinite(value.getAsDouble())
                    || Math.abs(value.getAsDouble()) > 30000000)
                  throw new IllegalArgumentException("Invalid numeric argument");
                if (type.equals("integer") && value.getAsDouble() != Math.rint(value.getAsDouble()))
                  throw new IllegalArgumentException("Integer argument required");
              }
              var limits = bounds(name, key);
              if (limits != null
                  && (value.getAsDouble() < limits[0] || value.getAsDouble() > limits[1]))
                throw new IllegalArgumentException(
                    name + "." + key + " must be " + limits[0] + ".." + limits[1]);
            });
    if (Set.of("set_sneaking", "set_sprinting").contains(name)
        && args.get("enabled").getAsInt() != 0
        && args.get("enabled").getAsInt() != 1)
      throw new IllegalArgumentException("enabled must be 0 or 1");
    if (args.has("face")
        && !Set.of("up", "down", "north", "south", "east", "west")
            .contains(args.get("face").getAsString()))
      throw new IllegalArgumentException("Unknown block face");
    if (name.equals("delegate_task")) {
      var raw = JsonParser.parseString(args.get("plan_json").getAsString()).getAsJsonObject();
      if (!raw.has("steps") || raw.getAsJsonArray("steps").size() > 24)
        throw new IllegalArgumentException("Finite delegated steps required");
      for (var element : raw.getAsJsonArray("steps")) {
        var step = element.getAsJsonObject();
        String action = step.get("action").getAsString();
        if (Set.of("delegate", "plan_next").contains(action)
            || action.equals("tool")
                && Set.of("avatarcraftnpc:delegate", "avatarcraftnpc:delegate_task")
                    .contains(step.get("target").getAsString()))
          throw new IllegalArgumentException("Delegated plans cannot delegate or advance phases");
      }
      if (!TaskPlan.parse(raw.toString()).blocked().isEmpty())
        throw new IllegalArgumentException("Delegated plan is blocked");
    }
  }

  private static int[] bounds(String name, String key) {
    if (key.equals("radius"))
      return new int[] {
        name.equals("inspect_structures") ? 0 : 1,
        name.equals("inspect_structures") ? 1 : name.equals("inspect_entities") ? 64 : 16
      };
    if (key.equals("count")) return new int[] {1, name.equals("acquire") ? 256 : 64};
    if (key.equals("seconds")) return new int[] {1, 60};
    if (key.equals("ticks")) return new int[] {0, 1200};
    if (key.equals("slot"))
      return new int[] {0, name.equals("use_item") ? 41 : name.equals("withdraw") ? 4095 : 35};
    if (key.equals("furnace_slot")) return new int[] {0, name.equals("furnace_put") ? 1 : 2};
    if (key.equals("container_slot")) return new int[] {0, 30000000};
    if (key.equals("enabled")) return new int[] {0, 1};
    if (key.equals("entity_id") || key.equals("target_id")) return new int[] {-1, 30000000};
    return null;
  }

  /** Local observation memory inherits observe; it cannot modify blocks, inventory or ownership. */
  public static boolean permitted(String name, Collection<String> allowed) {
    return allowed.contains(name)
        || name.equals("inspect_entity") && allowed.contains("observe")
        || name.equals("move_precisely") && allowed.contains("move_to")
        || name.equals("place_workstation") && allowed.contains("place")
        || name.equals("inspect_block") && allowed.contains("observe")
        || name.equals("container_put") && allowed.contains("deposit")
        || name.equals("container_take") && allowed.contains("withdraw")
        || Set.of("delegate_task", "await_teammate", "team_status").contains(name)
            && allowed.containsAll(List.of("give_item", "observe", "craft"))
        || Set.of("travel_dimension", "explore_structure").contains(name)
            && allowed.contains("move_to")
        || name.equals("inspect_structures") && allowed.contains("observe")
        || Set.of("look_at", "jump", "set_sneaking", "set_sprinting", "mount", "dismount")
                .contains(name)
            && allowed.contains("move_to")
        || Set.of("use_item", "release_item", "equip_armor").contains(name)
            && allowed.containsAll(List.of("equip", "attack"))
        || Set.of("use_on_block", "interact_block", "interact_entity").contains(name)
            && allowed.containsAll(List.of("place", "observe"))
        || Set.of("furnace_put", "furnace_take").contains(name)
            && allowed.containsAll(List.of("craft", "place"))
        || name.equals("inspect_entities") && allowed.contains("observe")
        || name.equals("drop_item") && allowed.contains("give_item")
        || name.equals("acquire") && allowed.containsAll(List.of("dig", "craft"))
        || name.equals("smelt") && allowed.containsAll(List.of("craft", "place"))
        || name.equals("delegate") && allowed.containsAll(List.of("give_item", "observe", "craft"))
        || name.equals("hunt") && allowed.contains("attack")
        || Set.of("remember_location", "forget_location").contains(name)
            && allowed.contains("observe");
  }

  public static Set<String> names() {
    return Set.copyOf(SPECS.keySet());
  }

  public static List<String> effective(Collection<String> allowed) {
    return SPECS.keySet().stream().filter(name -> permitted(name, allowed)).toList();
  }

  public static String compactReference() {
    var out = new StringBuilder();
    SPECS.forEach(
        (name, spec) -> {
          out.append(name).append('(');
          spec.properties()
              .forEach(
                  (key, type) -> {
                    out.append(key).append(':').append(type);
                    var limit = bounds(name, key);
                    if (limit != null)
                      out.append('[').append(limit[0]).append("..").append(limit[1]).append(']');
                    out.append(',');
                  });
          out.append(") ").append(spec.description()).append("; ");
        });
    return out.toString();
  }

  public static String localName(String remote) {
    if (remote == null || !remote.startsWith("npc_") || !SPECS.containsKey(remote.substring(4)))
      throw new IllegalArgumentException("Unknown NPC tool");
    return remote.substring(4);
  }

  private NpcTools() {}
}

package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.*;
import java.util.*;

/** Server-thread, per-actor blackboard shared by all nested executors. No provider credentials. */
public final class NpcExecutionMemory {
  private record Failure(
      String dimension, String tool, JsonObject args, String code, int count, long until) {}

  private final LinkedHashMap<String, Failure> failures = new LinkedHashMap<>();
  private final LinkedHashMap<String, JsonObject> searches = new LinkedHashMap<>();
  private final LinkedHashMap<String, List<List<Integer>>> trails = new LinkedHashMap<>();
  private JsonObject outcome = new JsonObject();
  private final LinkedHashMap<String,JsonArray> emptyAreas=new LinkedHashMap<>();

  public static String failureCode(String error) {
    String s = error.toLowerCase(Locale.ROOT);
    if (s.contains("fuel_insufficient")) return "FUEL_INSUFFICIENT";
    if (s.contains("container_full")) return "CONTAINER_FULL";
    if (s.contains("movement_stalled")) return "MOVEMENT_STALLED";
    if (s.contains("travel_too_far")) return "BOUNDS";
    if (s.contains("cycle") || s.contains("循環")) return "RESOURCE_CYCLE";
    if (s.contains("depth_limit")) return "DEPTH_LIMIT";
    if (s.contains("vertical_limit")) return "VERTICAL_LIMIT";
    if (s.contains("unloaded") || s.contains("search incomplete")) return "SEARCH_INCOMPLETE";
    if (s.contains("path") || s.contains("route") || s.contains("approach")) return "UNREACHABLE";
    if (s.contains("budget") || s.contains("outside") || s.contains("bounds")) return "BOUNDS";
    return "ACTION_FAILED";
  }

  private static String key(String dimension, String tool, JsonObject args) {
    // Arrival variants must share failures; argument order and fractional drop jitter do not
    // make a physically failed target a new candidate.
    var canonical = new TreeMap<String, String>();
    for (var e : args.entrySet()) {
      if (e.getKey().startsWith("_")) continue;
      canonical.put(
          e.getKey(),
          Set.of("x", "y", "z").contains(e.getKey())
              ? Long.toString((long) Math.floor(e.getValue().getAsDouble()))
              : e.getValue().toString());
    }
    return dimension + ":" + (tool.equals("move_precisely") ? "move_to" : tool) + canonical;
  }

  public boolean blocked(String dimension, String tool, JsonObject args, long now) {
    var f = failures.get(key(dimension, tool, args));
    return f != null && now < f.until;
  }

  public void failed(String dimension, String tool, JsonObject args, String error, long now) {
    if (args.toString().length() > 1024) return;
    if (error
        .toLowerCase(Locale.ROOT)
        .matches(".*(cancel|stopped|offline|target_cooldown|not allowed|still in progress|消失).*"))
      return;
    String k = key(dimension, tool, args);
    var previous = failures.remove(k);
    int count = previous == null ? 1 : Math.min(20, previous.count + 1);
    failures.put(
        k,
        new Failure(
            dimension,
            tool,
            args.deepCopy(),
            failureCode(error),
            count,
            now + Math.min(2400, 400L * count)));
    while (failures.size() > 64) failures.remove(failures.keySet().iterator().next());
  }

  public void succeeded(String dimension, String tool, JsonObject args) {
    failures.remove(key(dimension, tool, args));
    if (Set.of("dig", "place").contains(tool) && args.has("x")) {
      failures
          .values()
          .removeIf(
              f -> f.dimension.equals(dimension) && f.args.has("x") && distance(f.args, args) <= 9);
      searches
          .values()
          .removeIf(
              s ->
                  s.get("dimension").getAsString().equals(dimension)
                      && s.get("tool").getAsString().equals("find_blocks"));
    }
  }

  private static double distance(JsonObject a, JsonObject b) {
    double n = 0;
    for (String axis : List.of("x", "y", "z"))
      n += Math.pow(a.get(axis).getAsDouble() - b.get(axis).getAsDouble(), 2);
    return n;
  }

  public void searched(
      String dimension, String tool, JsonObject args, JsonObject result, long now) {
    if(tool.equals("find_blocks")&&args.has("origin")&&result.has("blocks")&&result.getAsJsonArray("blocks").isEmpty()){
      String resource=args.get("block").getAsString();var origin=args.getAsJsonArray("origin");
      String area=dimension+"|"+resource+"|"+(int)Math.floor(origin.get(0).getAsDouble()/4)+","+(int)Math.floor(origin.get(2).getAsDouble()/4);
      emptyAreas.remove(area);emptyAreas.put(area,origin.deepCopy());
      while(emptyAreas.size()>128)emptyAreas.remove(emptyAreas.keySet().iterator().next());
    }
    if (!Set.of("find_blocks", "find_chests", "read_signs").contains(tool)
        || !result.has("ok")
        || !result.get("ok").getAsBoolean()) return;
    String k = key(dimension, tool, args);
    var bounded =
        tool.equals("read_signs") ? NpcObservations.bounded(result, 6000) : result.deepCopy();
    if (bounded.has("chests"))
      for (var e : bounded.getAsJsonArray("chests")) {
        var chest = e.getAsJsonObject();
        // Keep identity and position even when a chest has a very large modded inventory.
        chest.remove("items");
      }
    for (String field : List.of("blocks", "chests"))
      if (bounded.has(field)) {
        var a = bounded.getAsJsonArray(field);
        while (a.size() > 24) {
          a.remove(a.size() - 1);
          bounded.addProperty("truncated", true);
        }
      }
    searches.remove(k);
    searches.put(
        k,
        object(
            "dimension",
            dimension,
            "tool",
            tool,
            "arguments",
            args.deepCopy(),
            "observedTick",
            now,
            "result",
            bounded));
    while (searches.size() > 16) searches.remove(searches.keySet().iterator().next());
  }

  public JsonObject observation(String dimension, long now) {
    var known = new JsonArray();
    for (var s : new ArrayList<>(searches.values()).reversed())
      if (s.get("dimension").getAsString().equals(dimension)) known.add(s.deepCopy());
    var failed = new JsonArray();
    for (var f : failures.values())
      if (f.dimension.equals(dimension) && f.until > now) {
        var arguments = f.args.deepCopy();
        var context = new JsonObject();
        for (String field :
            List.of("actorCell", "fromArea", "heldItem", "placedItem", "targetState"))
          if (arguments.has(field)) context.add(field, arguments.remove(field));
        failed.add(
            object(
                "tool",
                f.tool,
                "arguments",
                arguments,
                "context",
                context,
                "code",
                f.code,
                "attempts",
                f.count,
                "retryAfterTick",
                f.until));
        if (failed.size() == 12) break;
      }
    return object(
        "searches", known, "failedTargets", failed, "lastVerifiedOutcome", outcome.deepCopy());
  }

  public void walked(String dimension, int x, int y, int z) {
    var trail = trails.computeIfAbsent(dimension, d -> new ArrayList<>());
    var point = List.of(x, y, z);
    if (!trail.isEmpty() && trail.getLast().equals(point)) return;
    // Erase loops, but never invent a shortcut through unvisited cells.
    int old = trail.lastIndexOf(point);
    if (old >= 0) trail.subList(old + 1, trail.size()).clear();
    else trail.add(point);
    while (trail.size() > 1024) trail.removeFirst();
    while (trails.size() > 2) trails.remove(trails.keySet().iterator().next());
  }

  /** Only reuses actual walked cells, in reverse order, with nearby joins at both ends. */
  public List<List<Integer>> returnPath(
      String dimension, int x, int y, int z, int tx, int ty, int tz) {
    var trail = trails.getOrDefault(dimension, List.of());
    int from = -1, to = -1;
    double fromDistance = 4, toDistance = 16;
    for (int i = 0; i < trail.size(); i++) {
      var p = trail.get(i);
      double a = squared(p, x, y, z), b = squared(p, tx, ty, tz);
      if (a <= fromDistance) {
        from = i;
        fromDistance = a;
      }
      if (b < toDistance) {
        to = i;
        toDistance = b;
      }
    }
    if (from <= to || to < 0) return List.of();
    var result = new ArrayList<List<Integer>>();
    for (int i = from; i >= to; i--) result.add(trail.get(i));
    for (int i = 1; i < result.size(); i++) {
      var a = result.get(i - 1);
      var b = result.get(i);
      if (Math.abs(a.get(0) - b.get(0)) > 1
          || Math.abs(a.get(1) - b.get(1)) > 1
          || Math.abs(a.get(2) - b.get(2)) > 1) return List.of();
    }
    return result;
  }

  /** Observed return checkpoints can include small drops, but never become unchecked walk edges. */
  public List<List<Integer>> returnCheckpoints(String dimension, int x,int y,int z,int tx,int ty,int tz) {
    var trail=trails.getOrDefault(dimension,List.of());
    int from=-1,to=-1; double fd=4,td=16;
    for(int i=0;i<trail.size();i++) {
      double a=squared(trail.get(i),x,y,z),b=squared(trail.get(i),tx,ty,tz);
      if(a<=fd){from=i;fd=a;}
      if(b<td){to=i;td=b;}
    }
    if(from<=to || to<0)return List.of();
    var points=new ArrayList<List<Integer>>();points.add(trail.get(from));
    var anchor=trail.get(from);
    for(int i=from-1;i>=to;i--) {
      var a=trail.get(i+1);var b=trail.get(i);
      if(Math.abs(a.get(0)-b.get(0))>4 || Math.abs(a.get(1)-b.get(1))>4
          || Math.abs(a.get(2)-b.get(2))>4)return List.of();
      if(squared(b,anchor.get(0),anchor.get(1),anchor.get(2))>=64
          || Math.abs(b.get(1)-anchor.get(1))>=6 || i==to) {
        points.add(b);anchor=b;
      }
    }
    return points;
  }

  private static double squared(List<Integer> p, int x, int y, int z) {
    return Math.pow(p.get(0) - x, 2.) + Math.pow(p.get(1) - y, 2.) + Math.pow(p.get(2) - z, 2.);
  }

  public void completed(String summary, JsonObject evidence) {
    outcome =
        object(
            "state",
            "SUCCEEDED",
            "summary",
            summary,
            "verified",
            NpcObservations.bounded(evidence, 6000));
  }

  public boolean hasCompletedGoal() {
    return outcome.has("state");
  }

  public void newGoal() {
    outcome = new JsonObject();
    emptyAreas.clear();
  }

  public boolean searchedEmpty(String dimension,String resource,int x,int y,int z){
    String prefix=dimension+"|"+resource+"|";
    for(var entry:emptyAreas.entrySet())if(entry.getKey().startsWith(prefix)){
      var p=entry.getValue();
      if(Math.hypot(p.get(0).getAsDouble()-x,p.get(2).getAsDouble()-z)<12&&Math.abs(p.get(1).getAsDouble()-y)<12)return true;
    }
    return false;
  }

  public String save() {
    return object(
            "failures",
            new Gson().toJsonTree(failures.values()),
            "searches",
            new Gson().toJsonTree(searches),
            "trails",
            new Gson().toJsonTree(trails),
            "outcome",
            outcome,"emptyAreas",new Gson().toJsonTree(emptyAreas))
        .toString();
  }

  public void restore(String json) {
    failures.clear();
    searches.clear();
    trails.clear();
    outcome = new JsonObject();
    emptyAreas.clear();
    if (json == null || json.isBlank()) return;
    try {
      if (json.length() > 262144) throw new IllegalArgumentException("Execution memory too large");
      var o = JsonParser.parseString(json).getAsJsonObject();
      var g = new Gson();
      if(o.has("emptyAreas"))for(var e:o.getAsJsonObject("emptyAreas").entrySet()){
        if(emptyAreas.size()>=128)break;
        if(e.getKey().length()<=256&&e.getValue().isJsonArray()&&e.getValue().getAsJsonArray().size()==3)
          emptyAreas.put(e.getKey(),e.getValue().getAsJsonArray().deepCopy());
      }
      for (var e : o.getAsJsonArray("failures")) {
        var f = g.fromJson(e, Failure.class);
        if (failures.size() == 64 || f.args.toString().length() > 8192) break;
        failures.put(key(f.dimension, f.tool, f.args), f);
      }
      for (var e : o.getAsJsonObject("searches").entrySet()) {
        if (searches.size() == 16) break;
        searches.put(e.getKey(), e.getValue().getAsJsonObject().deepCopy());
      }
      for (var e : o.getAsJsonObject("trails").entrySet()) {
        if (trails.size() == 2) break;
        var points = new ArrayList<List<Integer>>();
        for (var v : e.getValue().getAsJsonArray()) {
          var a = v.getAsJsonArray();
          if (a.size() != 3 || points.size() == 1024) break;
          var p = List.of(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
          if (p.stream().anyMatch(n -> Math.abs((long) n) > 30000000)) break;
          points.add(p);
        }
        trails.put(e.getKey(), points);
      }
      outcome = o.getAsJsonObject("outcome").deepCopy();
    } catch (RuntimeException e) {
      failures.clear();
      searches.clear();
      trails.clear();
      outcome = new JsonObject();
    }
  }
}

package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Per-NPC task state. All methods/callbacks run on the server thread, never wait for a provider.
 */
public final class LocalTaskRunner implements AutoCloseable {
  public static final int EXPLORATION_BUDGET = 768;

  public record Option(
      String kind, String tool, JsonObject args, double distance, JsonObject context) {}

  public interface World {
    default void continuousWork(boolean enabled) {}

    JsonObject observe();

    CompletionStage<JsonObject> execute(String tool, JsonObject args);

    /** -1 = current hand is best, -2 = no appropriate harvesting tool, otherwise a bag slot. */
    int bestTool(JsonArray position);

    int foodSlot();

    JsonObject approach(JsonArray target);

    default JsonObject sharedLocation(String name) {
      return null;
    }

    default JsonObject pickupApproach(JsonArray target) {
      return approach(target);
    }

    default Option pickupWork(JsonArray target) {
      return null;
    }

    default int miningTool(TaskPlan.Step step) {
      return -1;
    }

    default boolean mineAccessible(JsonArray target) {
      return true;
    }

    default boolean harvestable(TaskPlan.Step step, JsonArray target) {
      return mineAccessible(target);
    }

    default List<Option> preparation(TaskPlan.Step step, JsonArray chests) {
      return List.of();
    }

    default List<Option> exploration(
        TaskPlan.Step step, Set<String> visited, int actions, JsonArray targets) {
      return List.of();
    }

    default void record(JsonObject selection) {}

    default boolean storageReachable(JsonObject chest) {
      return distance(observe(), vector(chest)) <= 3;
    }

    default int storageCapacity(JsonObject chest, int bagSlot) {
      return Integer.MAX_VALUE;
    }

    default boolean blocked(String tool, JsonObject args) {
      return false;
    }

    default List<Option> tactics(JsonObject observation) {
      return List.of();
    }
  }

  private record Candidate(
      String kind, String tool, JsonObject args, String tag, double distance, JsonObject context) {
    Candidate(String kind, String tool, JsonObject args, String tag, double distance) {
      this(kind, tool, args, tag, distance, new JsonObject());
    }
  }

  private record Eating(String item, int count, int food, boolean task, String signature) {}

  private final TaskPlan plan;
  private final World world;
  private final Set<String> allowed;
  private final LocalActionPolicy policy;
  private int index, done, failures;
  private int dryMines, pickupWaits, lastCollectionCount = -1;
  private int explorationActions;
  private final Set<String> visited = new LinkedHashSet<>();
  private boolean scannedTools;
  private boolean phaseComplete;
  private JsonObject homeStorage;
  private JsonArray visitedStorageAnchor;
  private String activity = "ローカル作業中";
  private Eating eating;
  private long nextTick, eatingSince = -1;
  private long microTick = -1, microDeadline;
  private int microBudget;
  private long settlingSince = -1;
  private boolean pending, closed, scannedBlocks, scannedChests;
  private JsonArray blocks = new JsonArray(), chests = new JsonArray();
  private String problem = "", lastFailure = "";
  private final Set<String> rejected = new HashSet<>();
  private final Set<String> fullStorage = new HashSet<>();
  private final JsonArray verifiedSteps = new JsonArray();

  public LocalTaskRunner(TaskPlan plan, World world, Collection<String> allowed) {
    this.plan = plan;
    this.world = world;
    world.continuousWork(true);
    this.allowed = Set.copyOf(allowed);
    policy = LocalActionPolicy.bundled();
    problem = plan.blocked();
  }

  public boolean complete() {
    return !closed && problem.isEmpty() && index >= plan.steps().size();
  }

  public boolean needsReplan() {
    return !closed && !problem.isEmpty();
  }

  public String problem() {
    return problem;
  }

  public boolean phaseComplete() {
    return phaseComplete;
  }

  public boolean busy() {
    return pending;
  }

  public String activity() {
    return activity;
  }

  public JsonObject navigation() {
    var saved = object("actions", explorationActions, "visited", new Gson().toJsonTree(visited));
    saved.add("verifiedSteps", verifiedSteps.deepCopy());
    if (homeStorage != null) saved.add("homeStorage", homeStorage.deepCopy());
    return saved;
  }

  public void restoreNavigation(JsonObject o) {
    if (o.has("verifiedSteps")) {
      var completed = o.getAsJsonArray("verifiedSteps");
      if (completed.size() > 24) throw new IllegalArgumentException("Invalid completed steps");
      for (var step : completed) verifiedSteps.add(step.deepCopy());
    }
    int count = o.get("actions").getAsInt();
    var places = o.getAsJsonArray("visited");
    if (count < 0 || count > EXPLORATION_BUDGET || places.size() > 256)
      throw new IllegalArgumentException("Invalid exploration state");
    for (var p : places) {
      String key = p.getAsString();
      if (!key.matches("-?[0-9]{1,8},-?[0-9]{1,8},-?[0-9]{1,8}"))
        throw new IllegalArgumentException("Invalid visit");
      visited.add(key);
    }
    explorationActions = count;
    if (o.has("homeStorage")) {
      var home = o.getAsJsonObject("homeStorage");
      if (home.toString().length() > 512
          || !home.get("dimension").getAsString().matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
        throw new IllegalArgumentException("Invalid storage home");
      for (String key : List.of("x", "y", "z"))
        if (!Double.isFinite(home.get(key).getAsDouble())
            || Math.abs(home.get(key).getAsDouble()) > 30000000)
          throw new IllegalArgumentException("Invalid storage coordinate");
      homeStorage = home.deepCopy();
    }
  }

  public JsonObject progress() {
    return object(
        "summary",
        plan.summary(),
        "step",
        index,
        "totalSteps",
        plan.steps().size(),
        "verifiedCount",
        index < plan.steps().size() && plan.steps().get(index).action().equals("collect")
            ? Math.max(0, lastCollectionCount)
            : done,
        "completedOperations",
        done,
        "failure",
        phaseComplete ? "" : problem.isEmpty() ? lastFailure : problem,
        "remaining",
        remaining(),
        "explorationActions",
        explorationActions,
        "activity",
        activity,
        "verifiedSteps",
        verifiedSteps.deepCopy(),
        "phaseComplete",
        phaseComplete);
  }

  public JsonArray remaining() {
    var out = new JsonArray();
    for (int i = index; i < plan.steps().size(); i++) {
      var step = plan.steps().get(i);
      if (i == index && !step.action().equals("collect") && done >= step.count()) continue;
      var o = new Gson().toJsonTree(step).getAsJsonObject();
      if (i == index && !step.action().equals("collect"))
        o.addProperty("count", Math.max(1, step.count() - done));
      out.add(o);
    }
    return out;
  }

  public TaskPlan checkpoint() {
    var remaining = remaining();
    if (remaining.isEmpty()) return null;
    return TaskPlan.parse(
        object("version", 1, "summary", plan.summary(), "blocked", "", "steps", remaining)
            .toString());
  }

  public void tick(long now) {
    if (closed || pending || complete() || needsReplan() || now < nextTick) return;
    if (microTick != now) {
      microTick = now;
      microBudget = 8;
      microDeadline = System.nanoTime() + 750_000;
    }
    if (microBudget-- <= 0) return;
    nextTick = now;
    try {
      var observation = world.observe();
      if (observation.has("settling") && observation.get("settling").getAsBoolean()) {
        if (settlingSince < 0) settlingSince = now;
        if (now - settlingSince > 100) problem = "足場に着地できません";
        nextTick = now + 2;
        return;
      }
      settlingSince = -1;
      if (observation.get("using_item").getAsBoolean()) {
        if (eatingSince < 0) eatingSince = now;
        if (now - eatingSince > 200) problem = "Item use did not finish";
        return;
      }
      eatingSince = -1;
      if (eating != null) {
        if (count(observation, eating.item) < eating.count
            || observation.get("food").getAsInt() > eating.food) {
          if (eating.task) done++;
        } else {
          failures++;
          lastFailure = "Food consumption was not verified";
          rejected.add(eating.signature);
        }
        eating = null;
      }
      var step = plan.steps().get(index);
      var candidates = new ArrayList<Candidate>();
      if (failures >= 3) problem = "Repeated action failure: " + lastFailure;
      if (!problem.isEmpty()) return;
      if (Set.of("collect", "pickup").contains(step.action())) {
        int collected = count(observation, step.target());
        if (collected > lastCollectionCount) dryMines = pickupWaits = 0;
        lastCollectionCount = collected;
        if (dryMines >= 32 || pickupWaits >= 40) {
          problem = "Requested items are not being collected; check drops and inventory capacity";
          return;
        }
      }
      boolean finished =
          Set.of("collect", "pickup").contains(step.action())
              ? count(observation, step.target()) >= step.count()
              : done >= step.count();
      if (finished) candidates.add(new Candidate("verify", "", new JsonObject(), "advance", 0));
      else build(step, observation, candidates);
      if (phaseComplete) return;
      if (candidates.size() > 8) candidates.subList(8, candidates.size()).clear();
      // Guard owns combat and its pursuit boundary. Generic self-defense must not bypass it.
      if (!(step.action().equals("tool")
          && Set.of("bep_3davater_companion:guard", "bep_3davater_companion:guard_post").contains(step.target())))
        for (var option : world.tactics(observation)) option(candidates, option, "defend");
      int food = observation.get("food").getAsInt(), foodSlot = world.foodSlot();
      if (food <= 12
          && foodSlot >= 0
          && allowed.contains("eat")
          && !rejected.contains("eat" + object("slot", foodSlot)))
        candidates.add(new Candidate("eat", "eat", object("slot", foodSlot), "food", 0));
      candidates.add(new Candidate("idle", "", new JsonObject(), "idle", 0));
      var features = new ArrayList<double[]>();
      Candidate selected = null;
      double best = Double.NEGATIVE_INFINITY;
      long actionable = candidates.stream().filter(c -> !c.tag.equals("idle")).count();
      for (var c : candidates) {
        var x =
            LocalActionPolicy.features(
                c.kind,
                c.tag.equals("food") || c.tag.equals("defend"),
                c.distance,
                failures,
                food,
                done,
                step.count(),
                context(step, observation, c));
        features.add(x);
        double score = actionable == 1 ? (c.tag.equals("idle") ? -1 : 1) : policy.score(x);
        if (score > best) {
          selected = c;
          best = score;
        }
      }
      var log =
          object(
              "modelVersion",
              LocalActionPolicy.VERSION,
              "selector",
              actionable == 1 ? "single-legal-action" : "learned-ranker",
              "goalAction",
              step.action(),
              "goalTarget",
              step.target(),
              "goalBlock",
              step.block(),
              "candidates",
              new Gson().toJsonTree(features),
              "actions",
              new Gson().toJsonTree(candidates),
              "chosen",
              candidates.indexOf(selected),
              "kind",
              selected.kind,
              "step",
              index,
              "verifiedCount",
              Set.of("collect", "pickup").contains(step.action())
                  ? count(observation, step.target())
                  : done);
      if (selected.tag.equals("idle")) {
        problem = "No executable local action candidate";
        return;
      }
      world.record(log);
      if (selected.tag.equals("advance")) {
        verifiedSteps.add(
            object(
                "step",
                new Gson().toJsonTree(step),
                "verifiedCount",
                Set.of("collect", "pickup").contains(step.action())
                    ? count(observation, step.target())
                    : done));
        index++;
        done = failures = 0;
        dryMines = pickupWaits = 0;
        lastCollectionCount = -1;
        explorationActions = 0;
        visited.clear();
        scannedTools = false;
        blocks = new JsonArray();
        chests = new JsonArray();
        scannedBlocks = scannedChests = false;
        visitedStorageAnchor = null;
        fullStorage.clear();
        rejected.clear();
        lastFailure = "";
      } else if (selected.tag.equals("pause")) {
        pickupWaits++;
        nextTick = now + 10;
      } else if (selected.tag.equals("replan"))
        problem = lastFailure.isEmpty() ? "Required target or item is unavailable" : lastFailure;
      else run(selected, now);
      if (!pending
          && !complete()
          && !needsReplan()
          && now >= nextTick
          && microBudget > 0
          && System.nanoTime() < microDeadline) tick(now);
    } catch (RuntimeException e) {
      problem = "Invalid local world state or action candidate";
    }
  }

  private void add(
      List<Candidate> list,
      String kind,
      String tool,
      JsonObject args,
      String tag,
      double distance) {
    if (!NpcTools.permitted(tool, allowed)) {
      lastFailure = "Skill is not allowed: " + tool;
      return;
    }
    if (!rejected.contains(tool + args) && !world.blocked(tool, args))
      list.add(new Candidate(kind, tool, args, tag, distance));
  }

  private void build(TaskPlan.Step s, JsonObject o, List<Candidate> out) {
    switch (s.action()) {
      case "plan_next" -> {
        phaseComplete = true;
        problem = "Verified phase complete; plan remaining mission";
      }
      case "tool" -> {
        String tool = s.target().substring("bep_3davater_companion:".length());
        add(
            out,
            Set.of("look_at", "equip_armor").contains(tool)
                ? "prepare"
                : tool.startsWith("inspect") ? "scan" : "execute",
            tool,
            JsonParser.parseString(s.arguments()).getAsJsonObject(),
            "task",
            0);
      }
      case "acquire" -> {
        if (count(o, s.target()) >= s.count()) done = s.count();
        else
          add(
              out,
              "execute",
              "acquire",
              object("item", s.target(), "count", s.count()),
              "acquire",
              0);
      }
      case "smelt" -> {
        var p = position(s, o);
        if (p == null) {
          lastFailure = "Smelting requires an observed furnace position";
          break;
        }
        if (distance(o, p) > 3) {
          approach(o, p, out);
          break;
        }
        var args = blockXyz(p);
        args.addProperty("item", s.target());
        args.addProperty("count", Math.min(64, s.count() - done));
        add(out, "execute", "smelt", args, "transfer", 0);
      }
      case "delegate" ->
          add(
              out,
              "execute",
              "delegate",
              object("entity_id", s.entityId(), "item", s.target(), "count", s.count()),
              "delegate",
              0);
      case "mine", "collect" -> {
        if (s.action().equals("collect") && !bagSpace(o, s.target())) {
          lastFailure = "Inventory is full";
          break;
        }
        if (s.action().equals("collect") && o.has("drops")) {
          for (var e : o.getAsJsonArray("drops")) {
            var drop = e.getAsJsonObject();
            if (!NpcResources.matches(drop, s.target())) continue;
            var p = drop.getAsJsonArray("position");
            if (drop.has("settling") && drop.get("settling").getAsBoolean()) {
              out.add(new Candidate("execute", "", new JsonObject(), "pause", 0));
              continue;
            }
            if (distance(o, p) < 1.3)
              out.add(new Candidate("execute", "", new JsonObject(), "pause", 0));
            else {
              var approach = world.pickupApproach(p);
              if (approach != null)
                add(out, "approach", "move_precisely", approach, "setup", distance(o, p));
              else {
                var access = world.pickupWork(p);
                if (access != null) option(out, access, "exploration");
              }
            }
          }
          if (!out.isEmpty()) break;
        }
        if (!prepareTool(s, o, out)) break;
        if (!scannedBlocks)
          add(
              out,
              "scan",
              "find_blocks",
              object("block", NpcResources.search(s), "radius", 16),
              "blocks",
              0);
        else
          for (var p : blocks) {
            var position = p.getAsJsonArray();
            if (!s.position().isEmpty() && distance(position, vector(s.position())) > 1) continue;
            if (!world.harvestable(s, position)) continue;
            int tool = world.bestTool(position);
            if (tool == -2) {
              lastFailure = "No suitable tool for the requested block";
              continue;
            }
            if (tool >= 0)
              add(
                  out,
                  "equip",
                  "equip",
                  object("slot", tool, "hand", "main"),
                  "setup",
                  distance(o, position));
            else add(out, "execute", "dig", blockXyz(position), "task", distance(o, position));
          }
        if (out.isEmpty() && scannedBlocks) explore(s, o, out, blocks);
      }
      case "deposit" -> {
        var destination = position(s, o);
        if (!s.location().isEmpty() && destination == null) break;
        boolean anchor = anchor(s);
        if (anchor && !storageAnchorReady(o, destination)) {
          approach(o, destination, out);
          break;
        }
        int slot = bagSlot(o, s.target());
        if (slot < 0) {
          putAway(o, s.target(), out);
          break;
        }
        if (!scannedChests) {
          add(out, "scan", "find_chests", object("radius", 16), "chests", 0);
          break;
        }
        var candidates = new ArrayList<JsonObject>();
        for (var e : chests) {
          var chest = e.getAsJsonObject();
          if (!anchor
              || distance(vector(chest), destination)
                  <= dev.avatarcraft.npc.AnchorIndex.CHEST_RADIUS) candidates.add(chest);
        }
        candidates.sort(
            Comparator.comparingDouble(
                c ->
                    (destination != null && distance(vector(c), destination) <= 1 ? -10000 : 0)
                        + distance(o, vector(c))));
        boolean preferredSeen =
            candidates.stream()
                .anyMatch(c -> destination != null && distance(vector(c), destination) <= 1);
        if (!anchor && destination != null && !preferredSeen && distance(o, destination) > 3) {
          approach(o, destination, out);
          break;
        }
        for (var chest : candidates) {
          var p = vector(chest);
          if (fullStorage.contains(p.toString())) continue;
          int capacity = world.storageCapacity(chest, slot);
          if (capacity == 0) {
            fullStorage.add(p.toString());
            continue;
          }
          if (capacity < 0 || !world.storageReachable(chest)) approach(o, p, out);
          else {
            var args = xyz(p);
            args.addProperty("slot", slot);
            args.addProperty("count", Math.min(capacity, Math.min(64, s.count() - done)));
            add(out, "execute", "deposit", args, "transfer", distance(o, p));
          }
          if (!out.isEmpty()) break;
        }
        if (out.isEmpty()) lastFailure = "CONTAINER_FULL: no accessible compatible storage space";
      }
      case "withdraw" -> {
        var destination = position(s, o);
        if (!s.location().isEmpty() && destination == null) break;
        boolean anchor = anchor(s);
        if (destination != null
            && (anchor ? !storageAnchorReady(o, destination) : distance(o, destination) > 4)) {
          approach(o, destination, out);
          break;
        }
        if (!scannedChests) add(out, "scan", "find_chests", object("radius", 16), "chests", 0);
        else
          for (var element : chests) {
            var chest = element.getAsJsonObject();
            var p = vector(chest);
            if (destination != null
                && distance(p, destination)
                    > (anchor ? dev.avatarcraft.npc.AnchorIndex.CHEST_RADIUS : 1)) continue;
            int slot =
                s.action().equals("deposit")
                    ? bagSlot(o, s.target())
                    : chestSlot(chest, s.target());
            if (slot < 0) {
              if (s.action().equals("deposit")) putAway(o, s.target(), out);
              continue;
            }
            if (!world.storageReachable(chest)) approach(o, p, out);
            else {
              var args = xyz(p);
              args.addProperty("slot", slot);
              args.addProperty("count", Math.min(64, s.count() - done));
              add(out, "execute", s.action(), args, "transfer", distance(o, p));
            }
          }
      }
      case "give_item" -> {
        int
            id =
                o.has("delivery_entity_id")
                    ? o.get("delivery_entity_id").getAsInt()
                    : entityId(s, o, false),
            slot = bagSlot(o, s.target());
        var target = entity(o, id);
        if (slot < 0) putAway(o, s.target(), out);
        else if (target != null) {
          var p = target.getAsJsonArray("position");
          if (distance(o, p) > 3) approach(o, p, out);
          else
            add(
                out,
                "execute",
                "give_item",
                object("entity_id", id, "slot", slot, "count", Math.min(64, s.count() - done)),
                "transfer",
                distance(o, p));
        }
      }
      case "equip", "eat" -> {
        int slot = s.target().isEmpty() ? s.slot() : bagSlot(o, s.target());
        int hand = s.hand().equals("off") ? 40 : 41;
        if (s.action().equals("equip")
            && !s.target().isEmpty()
            && itemAt(o, hand).equals(s.target())) done = s.count();
        else if (slot >= 0)
          add(
              out,
              "execute",
              s.action(),
              s.action().equals("eat")
                  ? object("slot", slot)
                  : object("slot", slot, "hand", s.hand()),
              "task",
              0);
      }
      case "craft" -> add(out, "execute", "craft", object("recipe", s.target()), "task", 0);
      case "prepare_tool" -> {
        if (prepareTool(s, o, out)) done = s.count();
      }
      case "move_to" -> {
        var p = position(s, o);
        if (p == null) break;
        if (!s.location().isEmpty()) {
          if (distance(o, p) < 2) {
            done = s.count();
            break;
          }
          var args = world.approach(p);
          if (args != null) add(out, "execute", "move_to", args, "task", distance(o, p));
        } else add(out, "execute", "move_to", xyz(p), "task", 0);
      }
      case "pickup" -> {
        if (!bagSpace(o, s.target())) {
          lastFailure = "Inventory is full";
          break;
        }
        var p = position(s, o);
        if (!s.location().isEmpty() && p == null) break;
        if (p != null && distance(o, p) > 4) {
          approach(o, p, out);
          break;
        }
        if (o.has("drops"))
          for (var entry : o.getAsJsonArray("drops")) {
            var drop = entry.getAsJsonObject();
            if (!NpcResources.matches(drop, s.target())) continue;
            var at = drop.getAsJsonArray("position");
            if (drop.has("settling") && drop.get("settling").getAsBoolean()) {
              out.add(new Candidate("execute", "", new JsonObject(), "pause", 0));
              continue;
            }
            if (distance(o, at) < 1.3)
              out.add(new Candidate("execute", "", new JsonObject(), "pause", 0));
            else {
              var approach = world.pickupApproach(at);
              if (approach != null)
                add(out, "approach", "move_precisely", approach, "setup", distance(o, at));
              else {
                var access = world.pickupWork(at);
                if (access != null) option(out, access, "exploration");
              }
            }
          }
        if (out.isEmpty()) explore(s, o, out, new JsonArray());
      }
      case "remember_location" -> {
        var p = s.position().isEmpty() ? o.getAsJsonArray("position") : vector(s.position());
        var args = blockXyz(p);
        args.addProperty("name", s.location());
        add(out, "execute", "remember_location", args, "task", 0);
      }
      case "forget_location" ->
          add(out, "execute", "forget_location", object("name", s.location()), "task", 0);
      case "place" -> {
        int slot = bagSlot(o, s.target());
        var p = vector(s.position());
        if (slot >= 0) {
          if (distance(o, p) > 3) approach(o, p, out);
          else {
            var args = blockXyz(p);
            args.addProperty("slot", slot);
            add(out, "execute", "place", args, "task", 0);
          }
        } else putAway(o, s.target(), out);
      }
      case "follow", "attack", "hunt" -> {
        int id = entityId(s, o, s.action().equals("attack"));
        if (id >= 0) {
          var args = object("entity_id", id);
          if (s.action().equals("follow")) args.addProperty("seconds", s.seconds());
          add(out, "execute", s.action(), args, "task", 0);
        }
      }
      case "wait" -> add(out, "execute", "wait", object("seconds", s.seconds()), "task", 0);
      default -> throw new IllegalArgumentException("Unknown local task");
    }
    if (out.isEmpty() && done >= s.count() && !Set.of("collect", "pickup").contains(s.action()))
      out.add(new Candidate("verify", "", new JsonObject(), "advance", 0));
    if (out.isEmpty()) out.add(new Candidate("replan", "", new JsonObject(), "replan", 0));
  }

  private boolean prepareTool(TaskPlan.Step s, JsonObject o, List<Candidate> out) {
    int slot = world.miningTool(s);
    if (slot == -1) return true;
    if (slot >= 0) {
      add(out, "equip", "equip", object("slot", slot, "hand", "main"), "setup", 0);
      return false;
    }
    // Observe spare tools before a crafting/acquisition fallback can hide the chest candidates.
    if (!scannedTools && NpcTools.permitted("find_chests", allowed)) {
      add(out, "scan", "find_chests", object("radius", 16), "tool_chests", 0);
      if (!out.isEmpty()) return false;
    }
    for (var option : world.preparation(s, chests)) option(out, option, "prepare");
    if (out.isEmpty()) lastFailure = "収穫できる道具を準備できません。資材・工作・精錬と採集経路を確認してください";
    return false;
  }

  private void option(List<Candidate> out, Option option, String tag) {
    if (NpcTools.permitted(option.tool, allowed)
        && !rejected.contains(option.tool + option.args)
        && !world.blocked(option.tool, option.args))
      out.add(
          new Candidate(
              option.kind,
              option.tool,
              option.args.deepCopy(),
              tag,
              option.distance,
              option.context.deepCopy()));
  }

  private void explore(TaskPlan.Step s, JsonObject o, List<Candidate> out, JsonArray targets) {
    if (explorationActions >= EXPLORATION_BUDGET || visited.size() >= 256) {
      lastFailure = "探索の上限に達しました。鉱石や通路を指定してください";
      return;
    }
    visited.add(key(blockXyz(o.getAsJsonArray("position"))));
    for (var option :
        world.exploration(
            s,
            Collections.unmodifiableSet(new LinkedHashSet<>(visited)),
            explorationActions,
            targets)) option(out, option, "exploration");
    if (out.isEmpty()) lastFailure = "安全に進める経路・掘り下げ先がありません。液体・崩落・保護・範囲・道具を確認してください";
  }

  private static String key(JsonObject p) {
    return (int) Math.floor(p.get("x").getAsDouble())
        + ","
        + (int) Math.floor(p.get("y").getAsDouble())
        + ","
        + (int) Math.floor(p.get("z").getAsDouble());
  }

  private boolean anchor(TaskPlan.Step s) {
    return !s.location().isEmpty() && world.sharedLocation(s.location()) != null;
  }

  private boolean storageAnchorReady(JsonObject observation, JsonArray destination) {
    if (distance(observation, destination) <= 6) visitedStorageAnchor = destination.deepCopy();
    // Once surveyed, let the NPC approach a chest at the edge without returning to the anchor.
    return destination.equals(visitedStorageAnchor);
  }

  private JsonArray position(TaskPlan.Step s, JsonObject o) {
    if (s.action().equals("deposit")
        && s.location().isEmpty()
        && s.position().isEmpty()
        && homeStorage != null) {
      if (!homeStorage.get("dimension").getAsString().equals(o.get("dimension").getAsString())) {
        problem = "Return to the home storage dimension before depositing";
        return null;
      }
      return vector(homeStorage);
    }
    if (s.location().isEmpty()) return s.position().isEmpty() ? null : vector(s.position());
    var shared = world.sharedLocation(s.location());
    if (shared != null) {
      if (!shared.get("dimension").getAsString().equals(o.get("dimension").getAsString())) {
        lastFailure = "アバターアンカーは別の次元です。実際のポータル移動が必要です";
        return null;
      }
      return vector(shared);
    }
    if (o.has("locations"))
      for (var entry : o.getAsJsonArray("locations")) {
        var p = entry.getAsJsonObject();
        if (!p.get("name").getAsString().equals(s.location())) continue;
        if (!o.has("dimension")
            || !p.get("dimension").getAsString().equals(o.get("dimension").getAsString())) {
          lastFailure = "記憶した場所は別の次元です";
          return null;
        }
        return vector(p);
      }
    lastFailure = "記憶した場所が見つかりません: " + s.location();
    return null;
  }

  private JsonObject context(TaskPlan.Step s, JsonObject o, Candidate c) {
    var result = c.context.deepCopy();
    result.addProperty(
        "target_missing", c.tag.equals("exploration") || scannedBlocks && blocks.isEmpty() ? 1 : 0);
    result.addProperty(
        "tool_missing", c.tag.equals("prepare") || c.tag.equals("tool_chests") ? 1 : 0);
    int occupied = 0;
    for (var e : o.getAsJsonArray("inventory"))
      if (e.getAsJsonObject().get("slot").getAsInt() < 36) occupied++;
    result.addProperty("bag_free", 1 - occupied / 36.);
    result.addProperty("exploration_fraction", (double) explorationActions / EXPLORATION_BUDGET);
    if (!o.get("owner").isJsonNull())
      result.addProperty(
          "owner_distance",
          Math.min(1, distance(o, o.getAsJsonObject("owner").getAsJsonArray("position")) / 48));
    result.addProperty(
        "health", o.has("health") ? Math.clamp(o.get("health").getAsDouble() / 20, 0, 1) : 1);
    result.addProperty(
        "inventory_fraction", Math.min(1, (double) count(o, s.target()) / s.count()));
    result.addProperty("ore_target", NpcResources.underground(s) ? 1 : 0);
    result.addProperty("diamond_target", NpcResources.diamond(s) ? 1 : 0);
    result.addProperty(
        "remaining_budget", Math.max(0, 1 - (double) explorationActions / EXPLORATION_BUDGET));
    boolean combat = Set.of("attack", "hunt").contains(c.tool);
    result.addProperty("combat", combat ? 1 : 0);
    result.addProperty(
        "target_health", o.has("combat_health") ? o.get("combat_health").getAsDouble() : 0);
    result.addProperty(
        "hostile_distance",
        o.has("hostile_distance") ? o.get("hostile_distance").getAsDouble() : 1);
    result.addProperty("weapon_ready", itemAt(o, 41).isEmpty() ? 0 : 1);
    boolean ranged = itemAt(o, 41).contains("bow") || itemAt(o, 41).contains("trident");
    result.addProperty("ranged", ranged ? 1 : 0);
    result.addProperty(
        "ammunition", o.has("ammunition") && o.get("ammunition").getAsBoolean() ? 1 : 0);
    result.addProperty("needs_aim", c.tool.equals("look_at") ? 1 : 0);
    result.addProperty(
        "use_charge",
        c.args.has("ticks") ? Math.clamp(c.args.get("ticks").getAsDouble() / 1200, 0, 1) : 0);
    result.addProperty("smelt_ready", c.tool.equals("smelt") ? 1 : 0);
    result.addProperty(
        "fuel_available",
        o.has("fuel_available") && o.get("fuel_available").getAsBoolean() ? 1 : 0);
    result.addProperty(
        "team_waiting", o.has("team_waiting") && o.get("team_waiting").getAsBoolean() ? 1 : 0);
    result.addProperty("delegated", c.tool.equals("delegate") ? 1 : 0);
    result.addProperty("dimension_travel", c.tool.equals("travel_dimension") ? 1 : 0);
    result.addProperty(
        "portal_visible",
        o.has("portal_visible") && o.get("portal_visible").getAsBoolean() ? 1 : 0);
    result.addProperty(
        "structure_observed",
        o.has("structure_observed") && o.get("structure_observed").getAsBoolean() ? 1 : 0);
    result.addProperty("danger", o.has("danger") ? o.get("danger").getAsDouble() : 0);
    return result;
  }

  private void putAway(JsonObject o, String target, List<Candidate> out) {
    int empty = -1;
    for (int i = 0; i < 36; i++)
      if (itemAt(o, i).isEmpty()) {
        empty = i;
        break;
      }
    if (empty < 0) {
      lastFailure = "No free bag slot for held items";
      return;
    }
    if (itemAt(o, 41).equals(target))
      add(out, "equip", "equip", object("slot", empty, "hand", "main"), "setup", 0);
    else if (itemAt(o, 40).equals(target))
      add(out, "equip", "equip", object("slot", empty, "hand", "off"), "setup", 0);
  }

  private void approach(JsonObject o, JsonArray p, List<Candidate> out) {
    var args = world.approach(p);
    if (args != null) add(out, "approach", "move_to", args, "setup", distance(o, p));
  }

  private void run(Candidate c, long now) {
    pending = true;
    activity =
        c.kind.equals("excavate")
            ? "地下へ階段状に掘り下げ中"
            : c.kind.equals("explore")
                ? "鉱石・通路を探索中"
                : c.tag.equals("prepare") ? "道具を準備中" : "ローカル作業中: " + c.tool;
    try {
      if (c.tool.equals("eat")) {
        var before = world.observe();
        String item = itemAt(before, c.args.get("slot").getAsInt());
        eating =
            new Eating(
                item,
                count(before, item),
                before.get("food").getAsInt(),
                c.tag.equals("task"),
                c.tool + c.args);
      }
      world
          .execute(c.tool, c.args.deepCopy())
          .whenComplete(
              (result, error) -> {
                if (closed) return;
                pending = false;
                nextTick = now;
                if (error != null
                    || result == null
                    || !result.has("ok")
                    || !result.get("ok").getAsBoolean()) {
                  if (c.tool.equals("eat")) eating = null;
                  failures++;
                  lastFailure =
                      result != null && result.has("error")
                          ? result.get("error").getAsString()
                          : "Action failed: " + c.tool;
                  rejected.add(c.tool + c.args);
                  if (c.tag.equals("blocks")) scannedBlocks = false;
                  if (c.tag.equals("chests")) scannedChests = false;
                  return;
                }
                if (c.tag.equals("blocks")) {
                  blocks = result.getAsJsonArray("blocks").deepCopy();
                  scannedBlocks = true;
                } else if (c.tag.equals("chests") || c.tag.equals("tool_chests")) {
                  chests = result.getAsJsonArray("chests").deepCopy();
                  if (c.tag.equals("tool_chests")) scannedTools = true;
                  else scannedChests = true;
                } else if (c.tag.equals("exploration")) {
                  explorationActions++;
                  failures = 0;
                  if (Set.of("move_to", "move_precisely", "work_route").contains(c.tool)) {
                    if (!c.tool.equals("work_route") && visited.size() < 256)
                      visited.add(key(c.args));
                    scannedBlocks = false;
                    blocks = new JsonArray();
                  }
                  if (Set.of("dig", "work_route").contains(c.tool))
                    rejected.removeIf(k -> k.startsWith("dig") || k.startsWith("move_"));
                } else if (c.tag.equals("setup")
                    && Set.of("move_to", "move_precisely").contains(c.tool)) {
                  scannedChests = false;
                } else if (c.tag.equals("prepare")) {
                  scannedTools = false;
                } else if (c.tag.equals("transfer")) {
                  int moved = result.get("moved").getAsInt();
                  if (moved <= 0 || moved > c.args.get("count").getAsInt()) {
                    problem = "Invalid verified transfer count";
                    return;
                  }
                  done += moved;
                  scannedChests = false;
                } else if (c.tag.equals("delegate") || c.tag.equals("acquire")) {
                  done = plan.steps().get(index).count();
                } else if (c.tag.equals("task")) {
                  failures = 0;
                  if (c.tool.equals("place_workstation")
                      && c.args.get("item").getAsString().equals("minecraft:chest")
                      && result.has("dimension")) {
                    homeStorage =
                        object(
                            "x",
                            result.get("x"),
                            "y",
                            result.get("y"),
                            "z",
                            result.get("z"),
                            "dimension",
                            result.get("dimension"));
                  }
                  if (!c.tool.equals("eat")) done++;
                  if (c.tool.equals("dig")) {
                    if (plan.steps().get(index).action().equals("collect")) dryMines++;
                    for (int i = blocks.size() - 1; i >= 0; i--)
                      if (blockXyz(blocks.get(i).getAsJsonArray()).equals(c.args)) blocks.remove(i);
                    scannedBlocks = !blocks.isEmpty();
                  }
                }
                if (c.tool.equals("eat")) eatingSince = now;
              });
    } catch (RuntimeException e) {
      pending = false;
      eating = null;
      failures++;
      lastFailure = "Local action was rejected";
    }
  }

  private static int bagSlot(JsonObject o, String item) {
    for (var e : o.getAsJsonArray("inventory")) {
      var s = e.getAsJsonObject();
      int slot = s.get("slot").getAsInt();
      if (slot < 36 && NpcResources.matches(s, item)) return slot;
    }
    return -1;
  }

  private static boolean bagSpace(JsonObject o, String item) {
    int occupied = 0;
    for (var e : o.getAsJsonArray("inventory")) {
      var stack = e.getAsJsonObject();
      if (stack.get("slot").getAsInt() >= 36) continue;
      occupied++;
      if (NpcResources.matches(stack, item) && stack.get("count").getAsInt() < 64) return true;
    }
    return occupied < 36;
  }

  private static String itemAt(JsonObject o, int slot) {
    for (var e : o.getAsJsonArray("inventory")) {
      var s = e.getAsJsonObject();
      if (s.get("slot").getAsInt() == slot) return s.get("item").getAsString();
    }
    return "";
  }

  private static int chestSlot(JsonObject chest, String item) {
    for (var e : chest.getAsJsonArray("items")) {
      var s = e.getAsJsonObject();
      if (NpcResources.matches(s, item)) return s.get("slot").getAsInt();
    }
    return -1;
  }

  private static int count(JsonObject o, String item) {
    int count = 0;
    for (var e : o.getAsJsonArray("inventory")) {
      var s = e.getAsJsonObject();
      if (NpcResources.matches(s, item)
          && (s.get("slot").getAsInt() < 36 || s.get("slot").getAsInt() >= 40))
        count += s.get("count").getAsInt();
    }
    return count;
  }

  private static int entityId(TaskPlan.Step s, JsonObject o, boolean hostile) {
    if (s.entityId() >= 0) return s.entityId();
    if (!hostile)
      return o.get("owner").isJsonNull()
          ? -1
          : o.getAsJsonObject("owner").get("entity_id").getAsInt();
    for (var e : o.getAsJsonArray("nearby"))
      if (e.getAsJsonObject().get("hostile").getAsBoolean())
        return e.getAsJsonObject().get("entity_id").getAsInt();
    return -1;
  }

  private static JsonObject entity(JsonObject o, int id) {
    if (!o.get("owner").isJsonNull()
        && o.getAsJsonObject("owner").get("entity_id").getAsInt() == id)
      return o.getAsJsonObject("owner");
    for (var e : o.getAsJsonArray("nearby"))
      if (e.getAsJsonObject().get("entity_id").getAsInt() == id) return e.getAsJsonObject();
    return null;
  }

  private static JsonArray vector(JsonObject o) {
    return vector(
        List.of(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble()));
  }

  private static JsonArray vector(List<Double> p) {
    var out = new JsonArray();
    p.forEach(out::add);
    return out;
  }

  private static JsonObject xyz(JsonArray p) {
    return object(
        "x", p.get(0).getAsDouble(), "y", p.get(1).getAsDouble(), "z", p.get(2).getAsDouble());
  }

  private static JsonObject blockXyz(JsonArray p) {
    return object(
        "x",
        (int) Math.floor(p.get(0).getAsDouble()),
        "y",
        (int) Math.floor(p.get(1).getAsDouble()),
        "z",
        (int) Math.floor(p.get(2).getAsDouble()));
  }

  private static double distance(JsonObject o, JsonArray p) {
    return distance(o.getAsJsonArray("position"), p);
  }

  private static double distance(JsonArray a, JsonArray b) {
    double n = 0;
    for (int i = 0; i < 3; i++) n += Math.pow(a.get(i).getAsDouble() - b.get(i).getAsDouble(), 2);
    return Math.sqrt(n);
  }

  public void close() {
    world.continuousWork(false);
    closed = true;
    pending = false;
  }
}

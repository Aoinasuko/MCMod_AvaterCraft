package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.*;
import dev.avatarcraft.npc.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;

/** Blueprint control only. Supplies and all construction effects are composed existing tools. */
final class NpcConstruction implements AutoCloseable {
  private final CompanionEntity npc;
  private final ServerLevel level;
  private final NpcSkills tools;
  private final String mode;
  private final int depth;
  private final Set<String> chain;
  private final BlueprintSpec spec;
  private NpcBlueprints.Plan plan;
  private NpcToolQueue queue;
  private CompletableFuture<JsonObject> search;
  private JsonArray chests;
  private final Deque<BlockPos> sites = new ArrayDeque<>();
  private BlockPos site;
  private int siteCell, operations;
  private final boolean specified;
  private final long deadline;
  final CompletableFuture<JsonObject> result = new CompletableFuture<>();

  NpcConstruction(CompanionEntity npc, String mode, JsonObject args, int depth, Set<String> chain) {
    this.npc = npc;
    level = (ServerLevel) npc.level();
    this.mode = mode;
    this.depth = depth;
    this.chain = chain;
    if (depth > 4) throw new IllegalArgumentException("Construction depth exceeds 4");
    tools = new NpcSkills(npc, (s, id) -> {}, depth, chain);
    tools.continuousWork(true);
    deadline = level.getGameTime() + 36000;
    if (mode.equals("create_blueprint")) {
      sourceDesign = args.get("design_json").getAsString();
      spec = BlueprintSpec.parse(args.get("design_json").getAsString());
      validateMaterials(spec);
      var selection = JsonParser.parseString(args.get("site_json").getAsString()).getAsJsonObject();
      specified = !selection.isEmpty();
      if (specified) {
        if (!selection.keySet().equals(Set.of("from", "to")))
          throw new IllegalArgumentException(
              "Site requires from/to inclusive coordinate arrays, or {}");
        var a = point(selection.getAsJsonArray("from"));
        var b = point(selection.getAsJsonArray("to"));
        var min =
            new BlockPos(
                Math.min(a.getX(), b.getX()),
                Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ()));
        if ((long) Math.abs((long) a.getX() - b.getX()) + 1 < spec.width()
            || (long) Math.abs((long) a.getY() - b.getY()) + 1 < spec.height()
            || (long) Math.abs((long) a.getZ() - b.getZ()) + 1 < spec.depth())
          throw new IllegalArgumentException("Blueprint exceeds specified building range");
        sites.add(min);
      } else {
        var center = NpcWalkSurface.standingCell(npc);
        var options = new ArrayList<BlockPos>();
        for (int dx = -24; dx <= 24; dx += 4)
          for (int dz = -24; dz <= 24; dz += 4) options.add(center.offset(dx, 0, dz));
        options.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
        sites.addAll(options);
      }
    } else {
      specified = true;
      plan =
          NpcBlueprints.get(level.getServer()).current(npc, args.get("blueprint_id").getAsString());
      spec = plan.spec();
      validateMaterials(spec);
      if (plan.status.equals("cancelled"))
        throw new IllegalArgumentException("Blueprint cancelled; create a new plan");
      if (plan.status.equals("complete")) {
        if (needed(npc, plan).isEmpty()) {
          result.complete(inspect(npc, plan.id));
          return;
        }
        plan.status = "planned";
        dirty();
      }
      if (mode.equals("build_blueprint")) {
        if (!missing(npc, plan).isEmpty())
          throw new IllegalArgumentException(
              "MATERIAL_MISSING: gather all blueprint materials into NPC inventory before"
                  + " construction");
        var nodes = new ArrayList<ToolProgram.Node>();
        int i = 0;
        var supportSteps = supportSteps(spec);
        String lastMount =
            supportSteps.isEmpty() ? "" : "support-mount:" + (supportSteps.size() - 1);
        for (var e : spec.blocks().entrySet()) {
          var c = e.getKey();
          var p = new BlockPos(plan.x + c.x(), plan.y + c.y(), plan.z + c.z());
          int priority = -c.y() * 100;
          if (c.y() == spec.height() - 1) {
            boolean edgeX = c.x() == 0 || c.x() == spec.width() - 1,
                edgeZ = c.z() == 0 || c.z() == spec.depth() - 1;
            priority += edgeX && edgeZ ? 20 : edgeX || edgeZ ? 10 : 0;
            if (supportSteps.stream().anyMatch(s -> s.x() == c.x() && s.z() == c.z()))
              priority -= 20;
          }
          nodes.add(
              new ToolProgram.Node(
                  "build:" + i++,
                  "place",
                  object("x", p.getX(), "y", p.getY(), "z", p.getZ(), "slot", "$item"),
                  priority,
                  c.y() == spec.height() - 1 && !lastMount.isEmpty()
                      ? List.of(lastMount)
                      : List.of(),
                  new JsonObject(),
                  object("block", e.getValue()),
                  true,
                  item(e.getValue()),
                  new AreaWorkRegion.Cell(p.getX(), p.getY(), p.getZ())));
        }
        var buildingIds = nodes.stream().map(ToolProgram.Node::id).toList();
        var supports = new LinkedHashSet<BlueprintSpec.Cell>();
        for (int step = 0; step < supportSteps.size(); step++) {
          var c = supportSteps.get(step);
          String previous = step == 0 ? "" : "support-mount:" + (step - 1);
          for (int y = 1; y <= c.y(); y++) {
            var support = new BlueprintSpec.Cell(c.x(), y, c.z());
            if (!supports.add(support)) continue;
            var p = new BlockPos(plan.x + c.x(), plan.y + y, plan.z + c.z());
            String id = "support:" + step + ":" + y;
            nodes.add(
                new ToolProgram.Node(
                    id,
                    "place",
                    object("x", p.getX(), "y", p.getY(), "z", p.getZ(), "slot", "$item"),
                    900,
                    previous.isEmpty() ? List.of() : List.of(previous),
                    new JsonObject(),
                    new JsonObject(),
                    true,
                    "minecraft:dirt",
                    new AreaWorkRegion.Cell(p.getX(), p.getY(), p.getZ())));
            previous = id;
          }
          var top = new BlockPos(plan.x + c.x(), plan.y + c.y() + 1, plan.z + c.z());
          nodes.add(
              new ToolProgram.Node(
                  "support-mount:" + step,
                  "move_to",
                  object("x", top.getX(), "y", top.getY(), "z", top.getZ()),
                  900,
                  List.of(previous),
                  new JsonObject(),
                  new JsonObject(),
                  true,
                  "",
                  new AreaWorkRegion.Cell(top.getX(), top.getY(), top.getZ())));
        }
        var center = npc.blockPosition();
        nodes.add(
            new ToolProgram.Node(
                "building-verified",
                "observe",
                new JsonObject(),
                -999,
                buildingIds,
                new JsonObject(),
                new JsonObject(),
                true,
                "",
                new AreaWorkRegion.Cell(center.getX(), center.getY(), center.getZ())));
        var cleanup = new ArrayList<>(supports);
        cleanup.sort(Comparator.comparingInt(BlueprintSpec.Cell::y).reversed());
        String previous = "building-verified";
        for (var c : cleanup) {
          var p = new BlockPos(plan.x + c.x(), plan.y + c.y(), plan.z + c.z());
          String id = "cleanup:" + c;
          nodes.add(
              new ToolProgram.Node(
                  id,
                  "dig",
                  object("x", p.getX(), "y", p.getY(), "z", p.getZ()),
                  -1000,
                  List.of(previous),
                  new JsonObject(),
                  object("block", "minecraft:air"),
                  true,
                  "",
                  new AreaWorkRegion.Cell(p.getX(), p.getY(), p.getZ())));
          previous = id;
        }
        nodes.sort(Comparator.comparingInt(ToolProgram.Node::priority).reversed());
        queue = new NpcToolQueue(npc, nodes, depth, chain);
      }
      plan.status = mode.equals("gather_blueprint") ? "gathering" : "building";
      dirty();
    }
  }

  private static void validateMaterials(BlueprintSpec spec) {
    for (String id : spec.materials().keySet()) {
      var key = Identifier.tryParse(id);
      if (key == null || !BuiltInRegistries.BLOCK.containsKey(key))
        throw new IllegalArgumentException("Unknown blueprint block");
      var block = BuiltInRegistries.BLOCK.getValue(key);
      if (block instanceof GameMasterBlock
          || block instanceof DoorBlock
          || block instanceof BedBlock
          || block instanceof DoublePlantBlock
          || !(block.asItem() instanceof BlockItem))
        throw new IllegalArgumentException(
            "Blueprint requires placeable single-cell blocks; multi-cell furniture needs a"
                + " supported design adapter");
    }
  }

  static String item(String block) {
    return NpcRecipes.id(BuiltInRegistries.BLOCK.getValue(Identifier.parse(block)).asItem());
  }

  private static BlockPos point(JsonArray a) {
    if (a.size() != 3) throw new IllegalArgumentException("Coordinate triple required");
    for (var v : a)
      if (!v.isJsonPrimitive()
          || !v.getAsJsonPrimitive().isNumber()
          || v.getAsDouble() != Math.rint(v.getAsDouble())
          || Math.abs(v.getAsDouble()) > 30000000)
        throw new IllegalArgumentException("Integer site coordinate required");
    return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
  }

  static Map<String, Integer> needed(CompanionEntity npc, NpcBlueprints.Plan plan) {
    var counts = new TreeMap<String, Integer>();
    var level = (ServerLevel) npc.level();
    for (var e : plan.spec().blocks().entrySet()) {
      var c = e.getKey();
      var p = new BlockPos(plan.x + c.x(), plan.y + c.y(), plan.z + c.z());
      if (!level.hasChunkAt(p)
          || !level
              .getBlockState(p)
              .is(BuiltInRegistries.BLOCK.getValue(Identifier.parse(e.getValue()))))
        counts.merge(item(e.getValue()), 1, Integer::sum);
    }
    if (!plan.status.equals("complete")) {
      var supports = new HashSet<BlueprintSpec.Cell>();
      for (var c : supportSteps(plan.spec()))
        for (int y = 1; y <= c.y(); y++) supports.add(new BlueprintSpec.Cell(c.x(), y, c.z()));
      for (var c : supports) {
        var p = new BlockPos(plan.x + c.x(), plan.y + c.y(), plan.z + c.z());
        if (!level.hasChunkAt(p) || !level.getBlockState(p).is(Blocks.DIRT))
          counts.merge("minecraft:dirt", 1, Integer::sum);
      }
    }
    return counts;
  }

  private static List<BlueprintSpec.Cell> supportSteps(BlueprintSpec spec) {
    int height = Math.max(0, spec.height() - 3);
    if (height == 0) return List.of();
    var positions = new ArrayList<BlueprintSpec.Cell>();
    for (int z = 1; z < spec.depth() - 1; z++)
      for (int c = 1; c < spec.width() - 1; c++) {
        int x = z % 2 == 1 ? c : spec.width() - 1 - c;
        boolean free = spec.blocks().containsKey(new BlueprintSpec.Cell(x, 0, z));
        for (int y = 1; y <= height; y++)
          free &= !spec.blocks().containsKey(new BlueprintSpec.Cell(x, y, z));
        if (free) positions.add(new BlueprintSpec.Cell(x, 0, z));
      }
    positions.sort(
        Comparator.comparingDouble(
            c ->
                Math.pow(c.x() - (spec.width() - 1) / 2.0, 2)
                    + Math.pow(c.z() - (spec.depth() - 1) / 2.0, 2)));
    if (positions.size() < height) return List.of();
    var out = new ArrayList<BlueprintSpec.Cell>();
    for (int i = 0; i < height; i++) {
      var c = positions.get(i);
      out.add(new BlueprintSpec.Cell(c.x(), i + 1, c.z()));
    }
    return out;
  }

  static int carried(CompanionEntity npc, String id) {
    int n = 0;
    for (int i = 0; i < 42; i++)
      if (NpcRecipes.id(npc.inventory.getItem(i).getItem()).equals(id))
        n += npc.inventory.getItem(i).getCount();
    return n;
  }

  static Map<String, Integer> missing(CompanionEntity npc, NpcBlueprints.Plan p) {
    var m = needed(npc, p);
    m.replaceAll((id, n) -> Math.max(0, n - carried(npc, id)));
    m.values().removeIf(n -> n == 0);
    return m;
  }

  static JsonObject inspect(CompanionEntity npc, String id) {
    var p = NpcBlueprints.get(npc.level().getServer()).current(npc, id);
    return object(
        "ok",
        true,
        "blueprint_id",
        p.id,
        "status",
        p.status,
        "geometry",
        NpcBlueprints.geometry(p),
        "needed",
        new Gson().toJsonTree(needed(npc, p)),
        "missing",
        new Gson().toJsonTree(missing(npc, p)));
  }

  void tick() {
    if (result.isDone()) return;
    try {
      if (!npc.isAlive()
          || npc.level() != level
          || npc.ownerId() == null
          || level.getServer().getPlayerList().getPlayer(npc.ownerId()) == null) {
        close();
        return;
      }
      if (level.getGameTime() > deadline) {
        fail("Construction time budget exceeded");
        return;
      }
      tools.tick();
      if (mode.equals("create_blueprint")) {
        create();
        return;
      }
      if (queue != null) {
        queue.tick();
        if (!queue.result.isDone()) return;
        var r = queue.result.join();
        queue.close();
        queue = null;
        if (!r.get("ok").getAsBoolean()) {
          plan.status = "blocked";
          dirty();
          r.addProperty("blueprint_id", plan.id);
          r.addProperty("actor", npc.position().toString());
          result.complete(r);
          close();
          return;
        }
        if (mode.equals("build_blueprint")) {
          plan.status = "complete";
          dirty();
          result.complete(inspect(npc, plan.id));
          return;
        }
        chests = null;
      }
      if (mode.equals("gather_blueprint")) gather();
    } catch (RuntimeException e) {
      fail(e.getMessage() == null ? "Construction operation rejected" : e.getMessage());
    }
  }

  private void create() {
    for (int budget = 0; budget < 128; budget++) {
      if (site == null) {
        if (sites.isEmpty()) {
          fail("No loaded, supported, clear building site found; specify/prepare a range");
          return;
        }
        site = sites.removeFirst();
        siteCell = 0;
      }
      int layer = siteCell / (spec.width() * spec.depth()),
          row = siteCell / spec.width() % spec.depth(),
          column = siteCell % spec.width();
      var p = site.offset(column, layer, row);
      boolean valid =
          level.hasChunkAt(p)
              && level.isInWorldBounds(p)
              && level.getWorldBorder().isWithinBounds(p)
              && npc.distanceToSqr(Vec3.atCenterOf(p)) <= 128 * 128
              && tools.mayChange(p, 128)
              && level.getBlockState(p).isAir();
      if (layer == 0)
        valid &=
            level.hasChunkAt(p.below())
                && level
                    .getBlockState(p.below())
                    .isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)
                && level.getFluidState(p.below()).isEmpty();
      if (!valid) {
        if (specified) {
          fail("Specified building range is occupied, unsupported, protected or unloaded");
          return;
        }
        site = null;
        continue;
      }
      if (++siteCell < spec.width() * spec.height() * spec.depth()) continue;
      plan = new NpcBlueprints.Plan();
      plan.id = UUID.randomUUID().toString();
      plan.owner = npc.ownerId().toString();
      plan.npc = npc.getUUID().toString();
      plan.dimension = level.dimension().identifier().toString();
      plan.x = site.getX();
      plan.y = site.getY();
      plan.z = site.getZ();
      // Preserve the compact, validated source design rather than expanding saved JSON.
      plan.design = sourceDesign;
      NpcBlueprints.get(level.getServer()).put(plan);
      result.complete(inspect(npc, plan.id));
      return;
    }
  }

  private String sourceDesign;

  private void gather() {
    if (++operations > 4096) {
      fail("Supply operation budget exceeded");
      return;
    }
    var shortages = missing(npc, plan);
    if (shortages.isEmpty()) {
      plan.status = "planned";
      dirty();
      result.complete(inspect(npc, plan.id));
      return;
    }
    if (search != null) {
      if (!search.isDone()) return;
      var r = search.join();
      search = null;
      if (!r.get("ok").getAsBoolean()) {
        fail("Nearby storage search failed");
        return;
      }
      chests = r.getAsJsonArray("chests");
    }
    if (chests == null) {
      search = tools.execute("find_chests", object("radius", 16)).toCompletableFuture();
      return;
    }
    var need = shortages.entrySet().iterator().next();
    String tool = "";
    JsonObject args = null;
    for (var e : chests) {
      var chest = e.getAsJsonObject();
      for (var s : chest.getAsJsonArray("items")) {
        var stack = s.getAsJsonObject();
        if (!stack.get("item").getAsString().equals(need.getKey())) continue;
        tool = "withdraw";
        args =
            object(
                "x",
                chest.get("x"),
                "y",
                chest.get("y"),
                "z",
                chest.get("z"),
                "slot",
                stack.get("slot"),
                "count",
                Math.min(64, Math.min(need.getValue(), stack.get("count").getAsInt())));
        break;
      }
      if (args != null) break;
    }
    if (args == null) {
      int total = carried(npc, need.getKey()) + need.getValue();
      if (total > 256) {
        fail("More than 256 of one missing material: provide chest supplies or split the design");
        return;
      }
      tool = "acquire";
      args = object("item", need.getKey(), "count", total);
    }
    var c = npc.blockPosition();
    var node =
        new ToolProgram.Node(
            "supply:" + plan.id + ":" + operations,
            tool,
            args,
            0,
            List.of(),
            new JsonObject(),
            new JsonObject(),
            true,
            "",
            new AreaWorkRegion.Cell(c.getX(), c.getY(), c.getZ()));
    queue = new NpcToolQueue(npc, List.of(node), depth, chain);
  }

  private void dirty() {
    NpcBlueprints.get(level.getServer()).setDirty();
  }

  private void fail(String error) {
    if (plan != null) {
      plan.status = "blocked";
      dirty();
    }
    result.complete(
        object("ok", false, "error", error, "blueprint_id", plan == null ? "" : plan.id));
    close();
  }

  JsonObject progress() {
    return object(
        "stage",
        mode,
        "blueprint_id",
        plan == null ? "" : plan.id,
        "queue",
        queue == null ? JsonNull.INSTANCE : queue.progress());
  }

  public void close() {
    tools.cancel("Construction stopped");
    if (queue != null) queue.close();
    if (!result.isDone()) result.complete(object("ok", false, "error", "Construction stopped"));
  }
}

package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.*;
import dev.avatarcraft.npc.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.*;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** Opt-in diagnostic missions. Failures are retained as failures, never hidden by assertions. */
final class NpcComplexGameTests {
  private static final String[] KEYS = {
    "skeleton_spawner_watch",
    "trap_stream_sort",
    "enclosed_irrigated_farm",
    "mine_diamond_armor",
    "chest_move_all",
    "mixed_logs_tools",
    "delayed_hostile_patrol",
    "mixed_crop_replant",
    "iron_gold_fuel",
    "double_chest_bulk",
    "hostile_type_priority",
    "gated_slab_delivery",
    "water_drop_recovery",
    "broken_pickaxe_replace",
    "full_destination_alternate",
    "remote_roundtrip_cargo",
    "cooperative_supply_smelting",
    "portal_cargo_roundtrip",
    "furnace_contention",
    "instruction_change_cancels_old_work"
  };
  private static final int[] BUDGETS = {
    2400, 3000, 3000, 14000, 6000, 10000, 3000, 6000, 14000, 10000, 4000, 4000, 5000, 7000, 6000,
    10000, 12000, 8000, 7000, 4000
  };
  private static final String[] INSTRUCTIONS = {
    "その場で待機して、スケルトンスポナーからスケルトンがスポーンしたらスケルトンを倒す",
    "トラップタワーから出てきたアイテムを種類ごとに別々のチェストに分別する",
    "丸石で囲まれた土を鍬で耕して水を張り種を植える",
    "ダイヤを必要数採掘しダイヤ防具一式を制作する",
    "指定のチェストの全アイテムを別の指定チェストに移す",
    "原木16個を集め板材・棒・木の斧を作り余りを収納",
    "門の向こうに後から来るゾンビを倒して帰る",
    "熟した四種類の作物をすべて採り植え直す",
    "木材から燃料を用意し鉄8と金4を精錬し別々に収納",
    "容量を超えるダブルチェストを複数回往復して全量移送",
    "スケルトンを先に倒し、ゾンビとクモも倒す。村人は攻撃しない",
    "段差とゲートを通り仲間にパン8個を届けて帰る",
    "水際の鉄5と金3を回収して収納",
    "道具が壊れたらチェストの予備を使い石8個を採掘して収納",
    "石炭64個を回収し満杯なら別のチェストに分ける",
    "遠くの保管庫から鉄64個を拠点へ運ぶ",
    "仲間に原鉄を届けてもらい精錬して鉄のツルハシを作る",
    "ネザーへ行き荷物を保持したまま元の拠点へ戻る",
    "他人の予約中のかまどを避けて鉄を精錬する",
    "採掘収納の途中で停止指示が来たら止めて待機する"
  };

  static void register(RegisterGameTestsEvent event) {
    for (int i = 0; i < KEYS.length; i++) {
      final int id = i;
      var selected = Set.of(System.getProperty("avatarcraft.complexQaCases", "").split(","));
      if (!selected.contains("") && !selected.contains(String.format("%02d", i + 1))) continue;
      var env =
          event.registerEnvironment(
              NpcMod.id("complex_diagnostics_" + String.format("%02d", i + 1)),
              new TestEnvironmentDefinition.AllOf());
      event.registerTest(
          NpcMod.id("complex_" + String.format("%02d", i + 1) + "_" + KEYS[i]),
          new FunctionGameTestInstance(
              BuiltinTestFunctions.ALWAYS_PASS,
              new TestData<>(
                  env,
                  NpcMod.id(i == 15 ? "qa_longtravel" : "qa_complex"),
                  Boolean.getBoolean("avatarcraft.complexLive") ? 2000000 : BUDGETS[i] + 500,
                  1,
                  true)) {
            public void run(GameTestHelper h) {
              var t = new Trial(h, id);
              try {
                t.prepare();
                t.start();
              } catch (Exception e) {
                t.finish(false, "FIXTURE_OR_SETUP: " + e);
                return;
              }
              h.onEachTick(
                  () -> {
                    if (t.finished) return;
                    try {
                      t.tick();
                    } catch (Exception e) {
                      if (t.finished) throw e;
                      t.finish(false, "EXECUTION_EXCEPTION: " + e);
                    }
                  });
            }
          });
    }
  }

  private static final class Trial {
    final GameTestHelper h;
    final ServerLevel level;
    final int id;
    final List<Entity> extras = new ArrayList<>();
    CompanionEntity n, worker;
    final UUID uuid;
    final NpcSkills observer;
    NpcMind oracle;
    NpcComplexLiveBrain live;
    final boolean liveMode = Boolean.getBoolean("avatarcraft.complexLive");
    final long wallStarted = System.nanoTime();
    final Map<Mob, Boolean> frozenActors = new HashMap<>();
    Supplier<TaskPlan> planner;
    BooleanSupplier goal = () -> false;
    Runnable environment = () -> {};
    final List<TaskPlan.Step> steps = new ArrayList<>();
    final List<TaskPlan> referencePlans = new ArrayList<>();
    final Map<Item, Integer> totals = new LinkedHashMap<>();
    final List<LivingEntity> enemies = new ArrayList<>();
    int ticks, requests, phase, killed, emitted, sourceTotal, atStop = -1, inventoryAtStop;
    boolean finished, spawnerStopped;
    double maxDistance;
    double maxGuardPathDistance;
    final Vec3 origin;
    JsonObject initial;

    Trial(GameTestHelper h, int id) {
      this.h = h;
      this.level = h.getLevel();
      this.id = id;
      int length = id == 15 ? 530 : 64;
      for (int x = 0; x < length; x++)
        for (int z = 0; z < 24; z++) h.setBlock(x, 0, z, Blocks.BEDROCK);
      var owner = h.makeMockServerPlayerInLevel();
      owner.setPos(Vec3.atBottomCenterOf(p(1, 1, 1)));
      n = NpcMod.COMPANION.get().create(level, EntitySpawnReason.COMMAND);
      n.initialize(owner, "tortobu");
      n.setFollowsOwner(false);
      n.setPos(Vec3.atBottomCenterOf(p(3, 1, 6)));
      level.addFreshEntity(n);
      uuid = n.getUUID();
      origin = n.position();
      n.inventory.setItem(30, new ItemStack(Items.BREAD, 16));
      observer = new NpcSkills(n, (text, target) -> {});
      h.setTime(18000);
    }

    BlockPos p(int x, int y, int z) {
      return h.absolutePos(new BlockPos(x, y, z));
    }

    JsonObject at(int x, int y, int z) {
      var p = p(x, y, z);
      return object("x", p.getX(), "y", p.getY(), "z", p.getZ());
    }

    TaskPlan.Step move(int x, int y, int z) {
      return NpcSurvival.at(
          "move_to", "", 1, p(x, y, z).getX(), p(x, y, z).getY(), p(x, y, z).getZ());
    }

    TaskPlan.Step typed(String action, Item item, int count) {
      return NpcSurvival.step(action, NpcRecipes.id(item), "", count);
    }

    TaskPlan.Step transfer(String action, Item item, int count, int x, int z) {
      return NpcSurvival.at(
          action,
          NpcRecipes.id(item),
          count,
          p(x, 1, z).getX(),
          p(x, 1, z).getY(),
          p(x, 1, z).getZ());
    }

    TaskPlan.Step atomic(String name, JsonObject args) {
      return new TaskPlan.Step(
          "tool",
          "avatarcraftnpc:" + name,
          "",
          1,
          -1,
          -1,
          "main",
          1,
          List.of(),
          "",
          args.toString());
    }

    TaskPlan.Step waitStep(int seconds) {
      return new TaskPlan.Step("wait", "", "", 1, -1, -1, "main", seconds, List.of());
    }

    TaskPlan.Step next() {
      return NpcSurvival.step("plan_next", "", "", 1);
    }

    TaskPlan plan(List<TaskPlan.Step> s) {
      return new TaskPlan(1, KEYS[id] + " reference phase " + phase, "", s);
    }

    int count(Item item) {
      return count(n.inventory, item);
    }

    int count(Container c, Item item) {
      int total = 0;
      for (int i = 0; i < c.getContainerSize(); i++)
        if (c.getItem(i).is(item)) total += c.getItem(i).getCount();
      return total;
    }

    int nonempty(Container c) {
      int x = 0;
      for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty()) x++;
      return x;
    }

    Container chest(int x, int z) {
      h.setBlock(x, 1, z, Blocks.CHEST);
      return (Container) level.getBlockEntity(p(x, 1, z));
    }

    Container existing(int x, int z) {
      return (Container) level.getBlockEntity(p(x, 1, z));
    }

    void stack(int slot, Item item, int count) {
      n.inventory.setItem(slot, new ItemStack(item, count));
    }

    void drop(Item item, int count, int x, int y, int z) {
      var at = Vec3.atCenterOf(p(x, y, z));
      var e = new ItemEntity(level, at.x, at.y + .15, at.z, new ItemStack(item, count));
      e.setNoPickUpDelay();
      level.addFreshEntity(e);
      extras.add(e);
    }

    int dropped(Item item) {
      return level
          .getEntitiesOfClass(
              ItemEntity.class,
              new AABB(p(0, 0, 0)).expandTowards(id == 15 ? 530 : 64, 15, 24),
              e -> e.isAlive() && e.getItem().is(item))
          .stream()
          .mapToInt(e -> e.getItem().getCount())
          .sum();
    }

    void craftTable() {
      h.setBlock(4, 1, 4, Blocks.CRAFTING_TABLE);
    }

    void furnace(int x, int z) {
      h.setBlock(x, 1, z, Blocks.FURNACE);
    }

    void armor() {
      for (var slot : EquipmentSlot.values())
        if (slot == EquipmentSlot.HEAD) n.setItemSlot(slot, new ItemStack(Items.NETHERITE_HELMET));
        else if (slot == EquipmentSlot.CHEST)
          n.setItemSlot(slot, new ItemStack(Items.NETHERITE_CHESTPLATE));
        else if (slot == EquipmentSlot.LEGS)
          n.setItemSlot(slot, new ItemStack(Items.NETHERITE_LEGGINGS));
        else if (slot == EquipmentSlot.FEET)
          n.setItemSlot(slot, new ItemStack(Items.NETHERITE_BOOTS));
      stack(41, Items.NETHERITE_SWORD, 1);
    }

    LivingEntity mob(EntityType<? extends LivingEntity> type, int x, int z) {
      var e = type.create(level, EntitySpawnReason.COMMAND);
      e.setPos(Vec3.atBottomCenterOf(p(x, 1, z)));
      level.addFreshEntity(e);
      extras.add(e);
      enemies.add(e);
      return e;
    }

    CompanionEntity colleague(int x, int z) {
      var owner = level.getServer().getPlayerList().getPlayer(n.ownerId());
      var e = NpcMod.COMPANION.get().create(level, EntitySpawnReason.COMMAND);
      e.initialize(owner, "tortobu");
      e.setFollowsOwner(false);
      e.setPos(Vec3.atBottomCenterOf(p(x, 1, z)));
      level.addFreshEntity(e);
      extras.add(e);
      return e;
    }

    void prepare() {
      switch (id) {
        case 0 -> spawner();
        case 1 -> sorting();
        case 2 -> farmConstruction();
        case 3 -> diamondArmor();
        case 4 -> chestMove(false);
        case 5 -> logs();
        case 6 -> delayedCombat();
        case 7 -> crops();
        case 8 -> smelting();
        case 9 -> chestMove(true);
        case 10 -> combat();
        case 11 -> delivery();
        case 12 -> water();
        case 13 -> brokenTool();
        case 14 -> overflow();
        case 15 -> remote();
        case 16 -> cooperation();
        case 17 -> portal();
        case 18 -> contention();
        case 19 -> interruption();
      }
    }

    void start() {
      initial = NpcObservations.planning(observer.observe(), 16000);
      n.setInstruction(INSTRUCTIONS[id]);
      if (liveMode) {
        live = new NpcComplexLiveBrain(String.format("%02d", id + 1), this::liveFixtureContext);
        oracle = new NpcMind(n, NpcService.get(level.getServer()), live);
        n.gameTestMind(oracle);
        write("RUNNING", "");
        return;
      }
      if (planner == null) {
        var plan = plan(steps);
        referencePlans.add(plan);
        n.mind().acceptDelegatedPlan(plan);
      } else {
        NpcBrain brain =
            new NpcBrain() {
              public CompletableFuture<Void> initialize() {
                return CompletableFuture.completedFuture(null);
              }

              public boolean connected() {
                return true;
              }

              public String status() {
                return "Deterministic test planner, no live AI";
              }

              public void close() {}

              public CompletableFuture<? extends Session> open(
                  Personality p,
                  BiFunction<String, JsonObject, CompletionStage<JsonObject>> tools) {
                return CompletableFuture.completedFuture(
                    new Session() {
                      boolean closed;

                      public boolean busy() {
                        return false;
                      }

                      public boolean connected() {
                        return !closed;
                      }

                      public boolean canStart(boolean voice) {
                        return !closed;
                      }

                      public CompletableFuture<Reply> turn(String prompt, boolean voice) {
                        return CompletableFuture.completedFuture(new Reply("", false));
                      }

                      public CompletableFuture<TaskPlan> plan(String prompt) {
                        requests++;
                        if (prompt.contains("RECOVERY_RESERVATION"))
                          return CompletableFuture.completedFuture(
                              new TaskPlan(
                                  1,
                                  "Diagnostic halt",
                                  "Reference planner retains the original local failure for"
                                      + " inspection",
                                  List.of()));
                        if (id == 2 || id == 7)
                          phase =
                              JsonParser.parseString(oracle.saveState())
                                  .getAsJsonObject()
                                  .get("phases")
                                  .getAsInt();
                        var value = planner.get();
                        referencePlans.add(value);
                        return CompletableFuture.completedFuture(value);
                      }

                      public void stop() {
                        closed = true;
                      }

                      public void close() {
                        closed = true;
                      }
                    });
              }
            };
        oracle = new NpcMind(n, NpcService.get(level.getServer()), brain);
      }
      write("RUNNING", "");
    }

    String liveFixtureContext() {
      var blocks = new JsonArray();
      for (int x = 0; x < 40; x++)
        for (int z = 0; z < 16; z++)
          for (int y = 0; y < 3; y++) {
            var p = p(x, y, z);
            var state = level.getBlockState(p);
            if (state.isAir()
                || state.is(Blocks.BEDROCK)
                || state.is(Blocks.COBBLESTONE) && id != 2) continue;
            blocks.add(
                object("x", p.getX(), "y", p.getY(), "z", p.getZ(), "state", state.toString()));
          }
      String detail =
          switch (id) {
            case 0 -> "現在地点を固定した持ち場にする。半径3以内の追撃、飛び道具には携帯の弓と矢を使用。監視は継続する。";
            case 1 ->
                "骨は"
                    + at(5, 1, 10)
                    + "、腐肉は"
                    + at(9, 1, 10)
                    + "、火薬は"
                    + at(13, 1, 10)
                    + "。後から数回排出されるので仕分け監視を継続。";
            case 2 -> "中央の" + at(9, 0, 6) + "を水源にし、周りの土8区画を耕して小麦を植える。丸石枠は壊さない。";
            case 4, 9 ->
                "移送元=" + at(7, 1, 8) + "、移送先=" + at(30, 1, 8) + "。私物のパン16個を移送しない。元の全量がなくなるまで往復。";
            case 5 -> "木の斧1本、棒4個を用意し、トウヒの板材8個を" + at(5, 1, 10) + "に入れる。";
            case 6 -> "200Tick後に門の先へゾンビが来る。倒したことを確認して開始位置=" + origin + "へ戻る。";
            case 7 -> "成熟した8株を収穫・回収して4種類すべて同じ区画に植え直す。未成熟株は壊さず、余りは携帯。";
            case 8 -> "鉄8個は" + at(4, 1, 10) + "、金4個は" + at(8, 1, 10) + "へ収納。原木4個は燃料加工できる。";
            case 10 -> "現在のスケルトンを最初に倒してからゾンビとクモ。村人を攻撃しない。実死亡まで確認。";
            case 11 -> "パン8個を観測された同じオーナーの仲間NPCに渡し、開始位置=" + origin + "へ戻る。";
            case 12 -> "鉄5個と金3個を" + at(5, 1, 10) + "へ収納。";
            case 13 -> "石を採掘して丸石8個を" + at(5, 1, 10) + "へ収納する。手の石ツルハシは耐久1。予備の鉄ツルハシが同チェストにある。";
            case 14 -> "優先収納=" + at(5, 1, 10) + "、満杯なら予備=" + at(9, 1, 10) + "。";
            case 15 -> "回収元=" + at(384, 1, 6) + "に鉄64個、帰還先=" + at(5, 1, 10) + "へ全量収納。";
            case 16 -> "同じオーナーの仲間が原鉄3個を持つ。仲間に配達を分担し、鉄ツルハシはリーダーが作る。";
            case 17 ->
                "枠の下部="
                    + at(7, 0, 8)
                    + "へ火打石を使って点火する。ネザーへ実際に移動し、ダイヤ7個を保持したまま元の開始位置="
                    + origin
                    + "へ帰る。";
            case 18 -> "鉄3個を作る。他NPCの予約済みかまどの原金を変更しない。";
            case 19 -> "丸石22個を集め" + at(5, 1, 10) + "へ収納。途中でオーナーから停止指示が入ったら旧作業を継続しない。";
            default -> "ダイヤ24個を実採掘して防具4種を一式作る。余りのダイヤは0。";
          };
      return "オーナーからの作業詳細: " + detail + "\n検証用の実ブロック配置（手順ではなく環境データ）: " + blocks;
    }

    void tick() {
      if (id == 17)
        for (var dimension : level.getServer().getAllLevels())
          if (dimension.getEntity(uuid) instanceof CompanionEntity current && current != n) {
            n = current;
            if (liveMode) {
              oracle = new NpcMind(n, NpcService.get(level.getServer()), live);
              n.gameTestMind(oracle);
            }
          }
      if (liveMode && (System.nanoTime() - wallStarted) > TimeUnit.MINUTES.toNanos(10)) {
        finish(false, "LIVE_WALL_TIMEOUT: ten minutes");
        return;
      }
      if (liveMode && live.waiting) {
        // Fast GameTest ticks must not turn a real network wait into hours of simulated combat.
        // Pause fixture actors/events, not the provider, until the requested plan is returned.
        for (var actor :
            level.getEntitiesOfClass(Mob.class, new AABB(p(0, 0, 0)).expandTowards(64, 16, 24)))
          if (actor != n) {
            frozenActors.putIfAbsent(actor, actor.isNoAi());
            actor.setNoAi(true);
          }
        java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        return;
      }
      if (!frozenActors.isEmpty()) {
        frozenActors.forEach((actor, noAi) -> actor.setNoAi(noAi));
        frozenActors.clear();
      }
      ticks++;
      if (!n.isAlive()) {
        finish(false, "NPC_DIED");
        return;
      }
      environment.run();
      if (oracle != null && !liveMode) oracle.tick();
      maxDistance = Math.max(maxDistance, n.position().distanceTo(origin));
      if (id == 0
          && n.getNavigation().getPath() != null
          && n.getNavigation().getPath().getEndNode() != null) {
        var end = n.getNavigation().getPath().getEndNode();
        maxGuardPathDistance =
            Math.max(
                maxGuardPathDistance,
                Vec3.atBottomCenterOf(new BlockPos(end.x, end.y, end.z)).distanceTo(origin));
      }
      // Hostile arrows retain normal knockback. Detect forbidden pursuit destinations rather
      // than treating an externally forced displacement as an AI decision to abandon the post.
      if (id == 0 && maxGuardPathDistance > 3) {
        finish(
            false,
            "GUARD_ANCHOR_VIOLATION: pursued spawned skeleton beyond the allowed three-block"
                + " radius");
        return;
      }
      if (n.aiStatus().startsWith("作業保留")) {
        finish(false, "LOCAL_BLOCKED: " + n.aiStatus());
        return;
      }
      if (goal.getAsBoolean()) {
        finish(true, "");
        return;
      }
      if ((planner == null || liveMode)
          && n.instruction().isBlank()
          && !(id == 19 && atStop >= 0)) {
        finish(false, "GOAL_UNVERIFIED: executor ended but world postcondition is false");
        return;
      }
      if (ticks >= BUDGETS[id]) finish(false, "DEADLINE: world postcondition not achieved");
    }

    void write(String status, String error) {
      try {
        var data =
            object(
                "id",
                String.format("%02d", id + 1),
                "key",
                KEYS[id],
                "instruction",
                INSTRUCTIONS[id],
                "status",
                status,
                "controlVariant",
                Boolean.getBoolean("avatarcraft.complexControl"),
                "error",
                error,
                "ticks",
                ticks,
                "budgetTicks",
                BUDGETS[id],
                "actor",
                uuid.toString(),
                "owner",
                n.ownerId().toString(),
                "oraclePlanningRequests",
                requests,
                "referencePhaseCount",
                referencePlans.size(),
                "spawnedHostiles",
                enemies.size(),
                "deadHostiles",
                enemies.stream().filter(e -> !e.isAlive()).count(),
                "hostiles",
                new Gson()
                    .toJsonTree(
                        enemies.stream()
                            .map(
                                e ->
                                    object(
                                        "id",
                                        e.getId(),
                                        "type",
                                        e.getType().toString(),
                                        "health",
                                        e.getHealth(),
                                        "alive",
                                        e.isAlive(),
                                        "x",
                                        e.getX(),
                                        "y",
                                        e.getY(),
                                        "z",
                                        e.getZ()))
                            .toList()),
                "maxDistanceFromStart",
                maxDistance,
                "maxGuardPathDistance",
                maxGuardPathDistance,
                "portalCooldown",
                n.getPortalCooldown(),
                "currentDimension",
                n.level().dimension().identifier().toString(),
                "blockAtFeet",
                n.level().getBlockState(n.blockPosition()).toString(),
                "initial",
                initial,
                "observation",
                NpcObservations.planning(new NpcSkills(n, (a, b) -> {}).observe(), 16000),
                "mindState",
                oracle == null ? n.mind().saveState() : oracle.saveState(),
                "referencePlans",
                new Gson().toJsonTree(referencePlans));
        data.addProperty("liveAiExecutedInWorld", liveMode);
        data.addProperty("wallSeconds", (System.nanoTime() - wallStarted) / 1e9);
        if (liveMode) {
          data.add("generatedPlans", new Gson().toJsonTree(live.plans));
          data.add("planningErrors", new Gson().toJsonTree(live.errors));
          data.addProperty("livePlanningRequests", live.requests);
        }
        var root =
            Path.of(
                liveMode
                    ? "../build/npc-complex-live-results"
                    : Boolean.getBoolean("avatarcraft.complexControl")
                        ? "../build/npc-complex-controls"
                        : "../build/npc-complex-results");
        Files.createDirectories(root);
        Files.writeString(
            root.resolve(String.format("%02d", id + 1) + "-" + KEYS[id] + ".json"),
            new GsonBuilder().setPrettyPrinting().create().toJson(data));
      } catch (Exception e) {
        throw new IllegalStateException("Cannot save complex test evidence", e);
      }
    }

    void finish(boolean success, String error) {
      if (finished) return;
      finished = true;
      write(success ? "PASS" : "FAIL", error);
      if (oracle != null) oracle.close();
      if (live != null) live.close();
      frozenActors.forEach((actor, noAi) -> actor.setNoAi(noAi));
      n.mind().stop();
      for (var e : extras) if (e.isAlive()) e.discard();
      n.discard();
      if (success) h.succeed();
      else h.fail(String.format("%02d", id + 1) + " " + KEYS[id] + ": " + error);
    }

    void spawner() {
      armor();
      // A fixed post cannot chase ranged enemies. Supply real ranged equipment and ammunition;
      // the guard must consume arrows and damage the bow through vanilla item use.
      stack(0, Items.BOW, 1);
      stack(1, Items.ARROW, 64);
      for (int x = 0; x < 14; x++) for (int z = 0; z < 14; z++) h.setBlock(x, 5, z, Blocks.STONE);
      for (int x = 0; x < 14; x++)
        for (int z = 0; z < 14; z++)
          if (x == 0 || x == 13 || z == 0 || z == 13)
            for (int y = 1; y < 5; y++) h.setBlock(x, y, z, Blocks.COBBLESTONE);
      h.setBlock(5, 1, 6, Blocks.SPAWNER);
      var block = (SpawnerBlockEntity) level.getBlockEntity(p(5, 1, 6));
      var out =
          TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
      block.getSpawner().save(out);
      var tag = out.buildResult();
      tag.putShort("Delay", (short) 40);
      tag.putShort("MinSpawnDelay", (short) 200);
      tag.putShort("MaxSpawnDelay", (short) 240);
      tag.putShort("SpawnCount", (short) 1);
      tag.putShort("SpawnRange", (short) 1);
      tag.putShort("MaxNearbyEntities", (short) 3);
      block
          .getSpawner()
          .load(
              level,
              p(5, 1, 6),
              TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
      block.setEntityId(EntityTypes.SKELETON, level.getRandom());
      var seen = new HashSet<UUID>();
      planner =
          () -> {
            var list = new ArrayList<TaskPlan.Step>();
            list.add(
                atomic(
                    "guard_post",
                    object(
                        "x",
                        origin.x,
                        "y",
                        origin.y,
                        "z",
                        origin.z,
                        "mob",
                        "minecraft:skeleton",
                        "radius",
                        3)));
            list.add(next());
            return plan(list);
          };
      environment =
          () -> {
            for (var mob :
                level.getEntitiesOfClass(
                    Skeleton.class, new AABB(p(0, 0, 0)).expandTowards(14, 5, 14)))
              if (seen.add(mob.getUUID())) {
                enemies.add(mob);
                extras.add(mob);
              }
            if (ticks == 1400) {
              h.setBlock(5, 1, 6, Blocks.AIR);
              spawnerStopped = true;
            }
          };
      goal =
          () ->
              ticks > 1500
                  && spawnerStopped
                  && enemies.size() >= 2
                  && enemies.stream().noneMatch(Entity::isAlive)
                  && (liveMode || requests == 1)
                  && maxGuardPathDistance <= 3
                  && n.position().distanceToSqr(origin) <= 9;
    }

    void sorting() {
      var bones = chest(5, 10);
      var flesh = chest(9, 10);
      var powder = chest(13, 10);
      environment =
          () -> {
            if (ticks == 40 || ticks == 700 || ticks == 1300) {
              drop(Items.BONE, 4, 8, 2, 6);
              drop(Items.ROTTEN_FLESH, 3, 8, 2, 6);
              drop(Items.GUNPOWDER, 2, 8, 2, 6);
              emitted++;
            }
          };
      planner =
          () -> {
            var list = new ArrayList<TaskPlan.Step>();
            Item[] types = {Items.BONE, Items.ROTTEN_FLESH, Items.GUNPOWDER};
            int[] xs = {5, 9, 13};
            for (int i = 0; i < types.length; i++) {
              int amount = count(types[i]) + dropped(types[i]);
              if (amount > 0) {
                list.add(typed("pickup", types[i], amount));
                list.add(transfer("deposit", types[i], amount, xs[i], 10));
              }
            }
            if (list.isEmpty()) list.add(waitStep(1));
            list.add(next());
            return plan(list);
          };
      goal =
          () ->
              emitted == 3
                  && count(bones, Items.BONE) == 12
                  && count(flesh, Items.ROTTEN_FLESH) == 9
                  && count(powder, Items.GUNPOWDER) == 6
                  && nonempty(bones) == 1
                  && nonempty(flesh) == 1
                  && nonempty(powder) == 1;
    }

    void farmConstruction() {
      for (int x = 7; x <= 11; x++)
        for (int z = 4; z <= 8; z++)
          h.setBlock(
              x, 0, z, x == 7 || x == 11 || z == 4 || z == 8 ? Blocks.COBBLESTONE : Blocks.DIRT);
      stack(0, Items.IRON_HOE, 1);
      stack(1, Items.WATER_BUCKET, 1);
      stack(2, Items.WHEAT_SEEDS, 8);
      steps.add(move(6, 1, 6));
      steps.add(atomic("dig", at(9, 0, 6)));
      steps.add(atomic("move_precisely", at(8, 1, 6)));
      var water = at(9, 0, 6);
      h.setBlock(9, -1, 6, Blocks.COBBLESTONE);
      water.addProperty("x", water.get("x").getAsDouble() + .5);
      water.addProperty("z", water.get("z").getAsDouble() + .5);
      steps.add(atomic("look_at", water));
      steps.add(atomic("use_item", object("slot", 1, "ticks", 0)));
      for (int x = 8; x <= 10; x++)
        for (int z = 5; z <= 7; z++)
          if (x != 9 || z != 6) {
            var till = at(x, 0, z);
            till.addProperty("slot", 0);
            till.addProperty("face", "up");
            steps.add(atomic("use_on_block", till));
            steps.add(atomic("equip", object("slot", 0, "hand", "main")));
          }
      // Seed planting is a second reference phase to keep the production 24-step contract.
      var first = List.copyOf(steps);
      steps.clear();
      planner =
          () -> {
            if (phase++ == 0) {
              var list = new ArrayList<>(first);
              list.add(next());
              return plan(list);
            }
            var list = new ArrayList<TaskPlan.Step>();
            for (int x = 8; x <= 10; x++)
              for (int z = 5; z <= 7; z++)
                if (x != 9 || z != 6) {
                  var a = at(x, 0, z);
                  a.addProperty("slot", 2);
                  a.addProperty("face", "up");
                  list.add(atomic("use_on_block", a));
                  list.add(atomic("equip", object("slot", 2, "hand", "main")));
                }
            return plan(list);
          };
      goal =
          () -> {
            if (!n.instruction().isBlank() || !level.getFluidState(p(9, 0, 6)).isSource())
              return false;
            for (int x = 8; x <= 10; x++)
              for (int z = 5; z <= 7; z++)
                if ((x != 9 || z != 6)
                    && (!level.getBlockState(p(x, 0, z)).is(Blocks.FARMLAND)
                        || !level.getBlockState(p(x, 1, z)).is(Blocks.WHEAT))) return false;
            return count(Items.WHEAT_SEEDS) == 0 && count(Items.BUCKET) == 1;
          };
    }

    void diamondArmor() {
      stack(41, Items.IRON_PICKAXE, 1);
      craftTable();
      for (int x = 8; x < 14; x++)
        for (int z = 5; z < 9; z++) h.setBlock(x, 1, z, Blocks.DIAMOND_ORE);
      steps.add(
          new TaskPlan.Step(
              "collect",
              "minecraft:diamond",
              "minecraft:diamond_ore",
              24,
              -1,
              -1,
              "main",
              1,
              List.of()));
      steps.add(move(3, 1, 6));
      for (String part : List.of("helmet", "chestplate", "leggings", "boots"))
        steps.add(NpcSurvival.step("craft", "minecraft:diamond_" + part, "", 1));
      goal =
          () ->
              count(Items.DIAMOND_HELMET) == 1
                  && count(Items.DIAMOND_CHESTPLATE) == 1
                  && count(Items.DIAMOND_LEGGINGS) == 1
                  && count(Items.DIAMOND_BOOTS) == 1
                  && count(Items.DIAMOND) == 0;
    }

    void chestMove(boolean bulk) {
      Container source = chest(7, 8), target = chest(30, 8);
      if (bulk) {
        h.setBlock(
            new BlockPos(30, 1, 8),
            Blocks.CHEST
                .defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.LEFT));
        h.setBlock(
            new BlockPos(31, 1, 8),
            Blocks.CHEST
                .defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.RIGHT));
        target =
            ChestBlock.getContainer(
                (ChestBlock) Blocks.CHEST,
                level.getBlockState(p(30, 1, 8)),
                level,
                p(30, 1, 8),
                false);
        h.setBlock(
            new BlockPos(7, 1, 8),
            Blocks.CHEST
                .defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.LEFT));
        h.setBlock(
            new BlockPos(8, 1, 8),
            Blocks.CHEST
                .defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.RIGHT));
        source =
            ChestBlock.getContainer(
                (ChestBlock) Blocks.CHEST,
                level.getBlockState(p(7, 1, 8)),
                level,
                p(7, 1, 8),
                false);
        for (int i = 0; i < 42; i++) source.setItem(i, new ItemStack(Items.IRON_SWORD));
        sourceTotal = 42;
      } else {
        source.setItem(0, new ItemStack(Items.SPRUCE_LOG, 64));
        source.setItem(7, new ItemStack(Items.RAW_IRON, 17));
        source.setItem(26, new ItemStack(Items.BREAD, 5));
      }
      var src = source;
      var dst = target;
      planner =
          () -> {
            var list = new ArrayList<TaskPlan.Step>();
            int carried = 0;
            for (int i = 0; i < 36; i++) carried += n.inventory.getItem(i).getCount();
            if (bulk) {
              int carriedSwords = count(Items.IRON_SWORD);
              if (carriedSwords > 0)
                list.add(transfer("deposit", Items.IRON_SWORD, carriedSwords, 30, 8));
              else if (nonempty(src) > 0) {
                list.add(transfer("withdraw", Items.IRON_SWORD, Math.min(34, nonempty(src)), 7, 8));
              } else return plan(List.of(waitStep(1)));
            } else {
              for (Item type : List.of(Items.SPRUCE_LOG, Items.RAW_IRON, Items.BREAD)) {
                int amount = count(src, type);
                if (amount > 0) list.add(transfer("withdraw", type, amount, 7, 8));
                int inventory = count(type) - (type == Items.BREAD ? 16 : 0);
                if (amount + inventory > 0)
                  list.add(transfer("deposit", type, amount + Math.max(0, inventory), 30, 8));
              }
            }
            if (!list.isEmpty()) list.add(next());
            else list.add(waitStep(1));
            return plan(list);
          };
      goal =
          () ->
              nonempty(src) == 0
                  && (bulk
                      ? count(dst, Items.IRON_SWORD) == 42
                      : count(dst, Items.SPRUCE_LOG) == 64
                          && count(dst, Items.RAW_IRON) == 17
                          && count(dst, Items.BREAD) == 5);
    }

    void logs() {
      craftTable();
      chest(5, 10);
      stack(41, Items.IRON_AXE, 1);
      for (int i = 0; i < 16; i++)
        h.setBlock(8 + i, 1, 8, i % 2 == 0 ? Blocks.SPRUCE_LOG : Blocks.BIRCH_LOG);
      steps.add(
          new TaskPlan.Step(
              "collect", "#minecraft:logs", "#minecraft:logs", 16, -1, -1, "main", 1, List.of()));
      steps.add(move(3, 1, 6));
      steps.add(typed("acquire", Items.SPRUCE_PLANKS, 16));
      steps.add(typed("acquire", Items.STICK, 4));
      steps.add(typed("acquire", Items.WOODEN_AXE, 1));
      steps.add(transfer("deposit", Items.SPRUCE_PLANKS, 8, 5, 10));
      goal =
          () ->
              count(Items.WOODEN_AXE) >= 1
                  && count(Items.STICK) >= 2
                  && count(existing(5, 10), Items.SPRUCE_PLANKS) >= 8;
    }

    void delayedCombat() {
      armor();
      var gate =
          Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.EAST);
      for (int z = 0; z < 20; z++)
        for (int y = 1; y <= 3; y++) h.setBlock(8, y, z, Blocks.COBBLESTONE);
      h.setBlock(new BlockPos(8, 1, 6), gate);
      h.setBlock(8, 2, 6, Blocks.AIR);
      environment =
          () -> {
            if (ticks == 200) mob(EntityTypes.ZOMBIE, 13, 6);
          };
      planner =
          () -> {
            var alive = enemies.stream().filter(Entity::isAlive).findFirst();
            if (alive.isPresent()) {
              if (Boolean.getBoolean("avatarcraft.complexControl") && n.getX() < p(9, 1, 6).getX())
                return plan(
                    List.of(
                        move(10, 1, 6),
                        new TaskPlan.Step(
                            "attack", "", "", 1, alive.get().getId(), -1, "main", 1, List.of()),
                        next()));
              return plan(
                  List.of(
                      new TaskPlan.Step(
                          "attack", "", "", 1, alive.get().getId(), -1, "main", 1, List.of()),
                      next()));
            }
            if (enemies.isEmpty()) return plan(List.of(waitStep(1), next()));
            return plan(List.of(move(3, 1, 6)));
          };
      goal =
          () ->
              ticks > 200
                  && !enemies.isEmpty()
                  && enemies.stream().noneMatch(Entity::isAlive)
                  && n.position().distanceToSqr(origin) < 4;
    }

    void crops() {
      Block[] blocks = {Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS};
      Item[] seeds = {Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS};
      Item[] food = {Items.WHEAT, Items.CARROT, Items.POTATO, Items.BEETROOT};
      for (int i = 0; i < 4; i++) {
        stack(20 + i, seeds[i], 8);
        for (int z = 5; z <= 7; z += 2) {
          h.setBlock(
              new BlockPos(7 + i, 0, z),
              Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7));
          var crop = (CropBlock) blocks[i];
          h.setBlock(new BlockPos(7 + i, 1, z), crop.getStateForAge(crop.getMaxAge()));
        }
        h.setBlock(7 + i, 0, 9, Blocks.WATER);
        h.setBlock(7 + i, -1, 9, Blocks.BEDROCK);
      }
      h.setBlock(new BlockPos(12, 0, 7), Blocks.FARMLAND.defaultBlockState());
      h.setBlock(12, 1, 7, Blocks.WHEAT);
      planner =
          () -> {
            var list = new ArrayList<TaskPlan.Step>();
            if (phase == 0) {
              for (int x = 7; x <= 10; x++)
                for (int z = 5; z <= 7; z += 2) list.add(atomic("dig", at(x, 1, z)));
              list.add(next());
            } else if (phase == 1) {
              for (Item item : food) {
                int amount = count(item) + dropped(item);
                totals.put(item, amount);
                if (amount > 0) list.add(typed("pickup", item, amount));
              }
              list.add(next());
            } else {
              list.add(move(8, 1, 6));
              for (int i = 0; i < 4; i++)
                for (int z = 5; z <= 7; z += 2) {
                  var a = at(7 + i, 0, z);
                  a.addProperty("slot", 20 + i);
                  a.addProperty("face", "up");
                  list.add(atomic("use_on_block", a));
                  list.add(atomic("equip", object("slot", 20 + i, "hand", "main")));
                }
            }
            phase++;
            return plan(list);
          };
      goal =
          () -> {
            if ((!liveMode && phase < 3) || !n.instruction().isBlank()) return false;
            for (int i = 0; i < 4; i++)
              for (int z = 5; z <= 7; z += 2) {
                var state = level.getBlockState(p(7 + i, 1, z));
                if (!state.is(blocks[i]) || ((CropBlock) blocks[i]).isMaxAge(state)) return false;
              }
            return level.getBlockState(p(12, 1, 7)).is(Blocks.WHEAT);
          };
    }

    void smelting() {
      furnace(5, 5);
      chest(4, 10);
      chest(8, 10);
      stack(0, Items.RAW_IRON, 8);
      stack(1, Items.RAW_GOLD, 4);
      stack(2, Items.SPRUCE_LOG, 4);
      if (Boolean.getBoolean("avatarcraft.complexControl"))
        steps.add(NpcSurvival.step("craft", "minecraft:spruce_planks", "", 4));
      steps.add(typed("acquire", Items.IRON_INGOT, 8));
      steps.add(transfer("deposit", Items.IRON_INGOT, 8, 4, 10));
      steps.add(move(3, 1, 6));
      steps.add(typed("acquire", Items.GOLD_INGOT, 4));
      steps.add(transfer("deposit", Items.GOLD_INGOT, 4, 8, 10));
      goal =
          () ->
              count(existing(4, 10), Items.IRON_INGOT) == 8
                  && count(existing(8, 10), Items.GOLD_INGOT) == 4;
    }

    void combat() {
      armor();
      for (int x = 0; x < 24; x++)
        for (int z = 0; z < 16; z++) {
          h.setBlock(x, 5, z, Blocks.COBBLESTONE);
          if (x == 0 || x == 23 || z == 0 || z == 15)
            for (int y = 1; y < 5; y++) h.setBlock(x, y, z, Blocks.COBBLESTONE);
        }
      var skeleton = mob(EntityTypes.SKELETON, 8, 6);
      var zombie = mob(EntityTypes.ZOMBIE, 12, 6);
      var spider = mob(EntityTypes.SPIDER, 15, 6);
      var villager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
      villager.setNoAi(true);
      villager.setPos(Vec3.atBottomCenterOf(p(12, 1, 9)));
      level.addFreshEntity(villager);
      extras.add(villager);
      float health = villager.getHealth();
      planner =
          () -> {
            var alive = enemies.stream().filter(Entity::isAlive).findFirst();
            return alive.isEmpty()
                ? plan(List.of(waitStep(1)))
                : plan(
                    List.of(
                        new TaskPlan.Step(
                            "attack", "", "", 1, alive.get().getId(), -1, "main", 1, List.of()),
                        next()));
          };
      goal = () -> enemies.stream().noneMatch(Entity::isAlive);
      environment =
          () -> {
            if (villager.getLastHurtByMob() == n)
              throw new IllegalStateException("NPC attacked protected villager");
            if (!skeleton.isAlive() && phase == 0) phase = 1;
            if (phase == 0 && (!zombie.isAlive() || !spider.isAlive()))
              throw new IllegalStateException("Combat priority violated");
          };
    }

    void delivery() {
      worker = colleague(26, 6);
      stack(1, Items.BREAD, 8);
      for (int x = 7; x < 14; x++)
        h.setBlock(new BlockPos(x, 1, 6), Blocks.STONE_SLAB.defaultBlockState());
      for (int z = 0; z < 20; z++)
        for (int y = 1; y < 4; y++) h.setBlock(18, y, z, Blocks.COBBLESTONE);
      h.setBlock(
          new BlockPos(18, 1, 6),
          Blocks.SPRUCE_FENCE_GATE
              .defaultBlockState()
              .setValue(FenceGateBlock.FACING, Direction.EAST));
      h.setBlock(18, 2, 6, Blocks.AIR);
      steps.add(move(24, 1, 6));
      steps.add(
          new TaskPlan.Step(
              "give_item", "minecraft:bread", "", 8, worker.getId(), -1, "main", 1, List.of()));
      steps.add(move(3, 1, 6));
      goal =
          () -> count(worker.inventory, Items.BREAD) == 8 && n.position().distanceToSqr(origin) < 4;
    }

    void water() {
      chest(5, 10);
      for (int x = 8; x < 15; x++) for (int z = 4; z < 9; z++) h.setBlock(x, 1, z, Blocks.BEDROCK);
      h.setBlock(12, 1, 6, Blocks.WATER);
      drop(Items.IRON_INGOT, 5, 12, 2, 6);
      drop(Items.GOLD_INGOT, 3, 12, 2, 6);
      steps.add(typed("pickup", Items.IRON_INGOT, 5));
      steps.add(typed("pickup", Items.GOLD_INGOT, 3));
      steps.add(transfer("deposit", Items.IRON_INGOT, 5, 5, 10));
      steps.add(transfer("deposit", Items.GOLD_INGOT, 3, 5, 10));
      goal =
          () ->
              count(existing(5, 10), Items.IRON_INGOT) == 5
                  && count(existing(5, 10), Items.GOLD_INGOT) == 3;
    }

    void brokenTool() {
      var c = chest(5, 10);
      c.setItem(0, new ItemStack(Items.IRON_PICKAXE));
      var pick = new ItemStack(Items.STONE_PICKAXE);
      pick.setDamageValue(pick.getMaxDamage() - 1);
      n.inventory.setItem(41, pick);
      for (int x = 8; x < 16; x++) h.setBlock(x, 1, 8, Blocks.STONE);
      if (Boolean.getBoolean("avatarcraft.complexControl")) {
        steps.add(
            new TaskPlan.Step(
                "collect",
                "minecraft:cobblestone",
                "minecraft:stone",
                1,
                -1,
                -1,
                "main",
                1,
                List.of()));
        steps.add(transfer("withdraw", Items.IRON_PICKAXE, 1, 5, 10));
      }
      steps.add(
          new TaskPlan.Step(
              "collect",
              "minecraft:cobblestone",
              "minecraft:stone",
              8,
              -1,
              -1,
              "main",
              1,
              List.of()));
      steps.add(transfer("deposit", Items.COBBLESTONE, 8, 5, 10));
      goal =
          () ->
              count(c, Items.COBBLESTONE) == 8
                  && count(Items.STONE_PICKAXE) == 0
                  && count(Items.IRON_PICKAXE) == 1;
    }

    void overflow() {
      var first = chest(5, 10);
      var other = chest(9, 10);
      for (int i = 0; i < 27; i++) first.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
      first.setItem(26, new ItemStack(Items.COAL, 32));
      drop(Items.COAL, 64, 12, 1, 6);
      steps.add(typed("pickup", Items.COAL, 64));
      steps.add(transfer("deposit", Items.COAL, 64, 5, 10));
      goal =
          () ->
              count(first, Items.COAL) == 64
                  && count(other, Items.COAL) == 32
                  && count(Items.COAL) == 0;
    }

    void remote() {
      var home = chest(5, 10);
      var source = chest(384, 6);
      source.setItem(0, new ItemStack(Items.IRON_INGOT, 64));
      steps.add(transfer("withdraw", Items.IRON_INGOT, 64, 384, 6));
      steps.add(transfer("deposit", Items.IRON_INGOT, 64, 5, 10));
      goal =
          () -> nonempty(source) == 0 && count(home, Items.IRON_INGOT) == 64 && maxDistance > 375;
    }

    void cooperation() {
      craftTable();
      furnace(5, 5);
      worker = colleague(6, 6);
      worker.inventory.setItem(0, new ItemStack(Items.RAW_IRON, 3));
      stack(0, Items.SPRUCE_LOG, 2);
      stack(1, Items.STICK, 2);
      steps.add(
          new TaskPlan.Step(
              "delegate", "minecraft:raw_iron", "", 3, worker.getId(), -1, "main", 1, List.of()));
      steps.add(typed("acquire", Items.IRON_PICKAXE, 1));
      goal =
          () ->
              count(Items.IRON_PICKAXE) == 1
                  && NpcWorkData.get(level).jobs.stream()
                      .anyMatch(
                          j ->
                              j.leader.equals(uuid)
                                  && j.worker.equals(worker.getUUID())
                                  && j.status.equals("complete"));
    }

    void portal() {
      stack(0, Items.FLINT_AND_STEEL, 1);
      stack(2, Items.DIAMOND, 7);
      for (int x = 6; x <= 9; x++)
        for (int y = 0; y <= 4; y++)
          if (x == 6 || x == 9 || y == 0 || y == 4) h.setBlock(x, y, 8, Blocks.OBSIDIAN);
      steps.add(move(7, 1, 6));
      var a = at(7, 0, 8);
      a.addProperty("slot", 0);
      a.addProperty("face", "up");
      steps.add(atomic("use_on_block", a));
      steps.add(atomic("travel_dimension", object("dimension", "minecraft:the_nether")));
      steps.add(waitStep(12));
      steps.add(atomic("travel_dimension", object("dimension", "minecraft:overworld")));
      steps.add(move(3, 1, 6));
      goal =
          () ->
              n.level() == level
                  && count(Items.DIAMOND) == 7
                  && ticks > 250
                  && n.position().distanceToSqr(origin) < 9
                  && n.instruction().isBlank();
    }

    void contention() {
      furnace(5, 5);
      furnace(9, 5);
      worker = colleague(6, 8);
      var busy = (FurnaceBlockEntity) level.getBlockEntity(p(5, 1, 5));
      busy.setItem(0, new ItemStack(Items.RAW_GOLD, 2));
      NpcWorkData.get(level).furnaces.put(NpcSmelting.key(level, p(5, 1, 5)), worker.getUUID());
      stack(0, Items.RAW_IRON, 3);
      stack(1, Items.COAL, 1);
      steps.add(typed("acquire", Items.IRON_INGOT, 3));
      goal =
          () ->
              count(Items.IRON_INGOT) == 3
                  && busy.getItem(0).is(Items.RAW_GOLD)
                  && busy.getItem(0).getCount() == 2;
    }

    void interruption() {
      chest(5, 10);
      stack(41, Items.IRON_PICKAXE, 1);
      for (int x = 8; x < 30; x++) h.setBlock(x, 1, 8, Blocks.STONE);
      steps.add(
          new TaskPlan.Step(
              "collect",
              "minecraft:cobblestone",
              "minecraft:stone",
              22,
              -1,
              -1,
              "main",
              1,
              List.of()));
      steps.add(transfer("deposit", Items.COBBLESTONE, 22, 5, 10));
      environment =
          () -> {
            if (atStop < 0 && count(Items.COBBLESTONE) >= 2) {
              n.setInstruction("");
              atStop = ticks;
              inventoryAtStop = count(Items.COBBLESTONE);
            }
          };
      goal =
          () ->
              atStop > 0
                  && ticks > atStop + 120
                  && n.instruction().isBlank()
                  && count(existing(5, 10), Items.COBBLESTONE) == 0
                  && count(Items.COBBLESTONE) <= inventoryAtStop + 1;
    }
  }
}

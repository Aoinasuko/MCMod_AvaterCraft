package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.avatarcraft.npc.Personality;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic command audit against the signed-in provider. Never reads credential files. */
@EnabledIfEnvironmentVariable(named = "AVATARCRAFT_COMPLEX_LIVE", matches = "1")
class NpcComplexPlanningAuditTest {
  @TempDir Path workspace;

  @Test
  @Timeout(1800)
  void auditTwentyJapaneseCommandsWithoutPretendingPlansWereExecuted() throws Exception {
    JsonArray catalog;
    try (var input = getClass().getResourceAsStream("/npc-complex-scenarios.json")) {
      assertNotNull(input);
      catalog =
          JsonParser.parseString(
                  new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
              .getAsJsonArray();
    }
    var config = new CodexConfig();
    config.executable =
        System.getenv().getOrDefault("AVATARCRAFT_CODEX_EXECUTABLE", config.executable);
    config.turnTimeoutSeconds = 90;
    var report = new JsonArray();
    var selection =
        Set.of(System.getenv().getOrDefault("AVATARCRAFT_COMPLEX_CASES", "").split(","));
    var output =
        Path.of(
            System.getenv()
                .getOrDefault(
                    "AVATARCRAFT_COMPLEX_AUDIT_OUTPUT", "build/npc-complex-planning-audit.json"));
    try (var executor = Executors.newFixedThreadPool(2)) {
      var jobs = new ArrayList<Future<?>>();
      for (int worker = 0; worker < 2; worker++) {
        final int workerId = worker;
        jobs.add(
            executor.submit(
                () -> {
                  try {
                    var workerPath = workspace.resolve("worker-" + workerId);
                    var rawText = new java.util.concurrent.atomic.AtomicReference<String>("");
                    try (var bridge =
                        new CodexBridge(
                            config, workerPath, recordedLaunch(config, workerPath, rawText))) {
                      bridge.initialize().get(40, TimeUnit.SECONDS);
                      assertTrue(
                          bridge.status().contains("OAuth"),
                          "Audit requires managed OAuth, no embedded credentials");
                      for (int rowIndex = workerId; rowIndex < catalog.size(); rowIndex += 2) {
                        var row = catalog.get(rowIndex).getAsJsonObject();
                        if (!selection.contains("")
                            && !selection.contains(row.get("id").getAsString())) continue;
                        rawText.set("");
                        long start = System.nanoTime();
                        var record =
                            object(
                                "id",
                                row.get("id"),
                                "key",
                                row.get("key"),
                                "instruction",
                                row.get("ja"),
                                "language",
                                "ja",
                                "inputRevision",
                                "consistent-20261025-v2",
                                "executedInWorld",
                                false);
                        var profile = Personality.preset("tortobu");
                        String allowed = NpcTools.effective(profile.allowedSkills).toString();
                        profile.allowedSkills.clear();
                        NpcBrain.Session session = null;
                        boolean submitted = false;
                        try {
                          var facts = observation(row.get("id").getAsInt());
                          session =
                              bridge
                                  .openAdvanced(
                                      profile,
                                      (name, args) ->
                                          CompletableFuture.failedFuture(
                                              new IllegalStateException(
                                                  "Audit planner may not execute tools")))
                                  .get(30, TimeUnit.SECONDS);
                          submitted = true;
                          var plan =
                              session
                                  .plan(
                                      "Authenticated owner instruction: "
                                          + row.get("ja").getAsString()
                                          + "\nLocal executor allowedSkills: "
                                          + allowed
                                          + "\n"
                                          + "Synthetic audit observation (test facts, not a real"
                                          + " user world): "
                                          + facts
                                          + "\n"
                                          + "Return the executable first phase using only the"
                                          + " actual TaskPlan/tools contract. If more observation"
                                          + " or work is needed, preserve the entire remaining"
                                          + " mission with plan_next. Do not invent future entity"
                                          + " IDs, deposits, harvested counts or success. Ongoing"
                                          + " watch/sort tasks must not finish after one event.")
                                  .get(110, TimeUnit.SECONDS);
                          TaskPlan.parse(new Gson().toJson(plan));
                          record.add("plan", new Gson().toJsonTree(plan));
                          boolean next =
                              plan.steps().stream().anyMatch(s -> s.action().equals("plan_next"));
                          record.addProperty(
                              "status",
                              !plan.blocked().isBlank()
                                  ? "BLOCKED"
                                  : next
                                      ? "VALID_FIRST_PHASE_ONLY"
                                      : "VALID_FINITE_PLAN_UNEXECUTED");
                          var alerts = new JsonArray();
                          if (Integer.parseInt(row.get("id").getAsString()) <= 2
                              && !next
                              && plan.blocked().isBlank())
                            alerts.add("Ongoing mission has no continuation/condition operator");
                          if (plan.steps().stream()
                              .anyMatch(
                                  s ->
                                      Set.of("attack", "hunt").contains(s.action())
                                          && s.count() > 1))
                            alerts.add(
                                "Attack count means successful attacks, not verified kills; target"
                                    + " disappearance must be handled");
                          record.add("semanticAlerts", alerts);
                        } catch (Exception e) {
                          var cause = e;
                          while (cause.getCause() instanceof Exception nested) cause = nested;
                          record.addProperty("status", submitted ? "PLAN_ERROR" : "FIXTURE_ERROR");
                          record.addProperty("errorClass", cause.getClass().getSimpleName());
                          record.addProperty(
                              "error",
                              String.valueOf(cause.getMessage())
                                  .substring(
                                      0,
                                      Math.min(700, String.valueOf(cause.getMessage()).length())));
                        } finally {
                          if (session != null) session.close();
                        }
                        record.addProperty("requestSubmitted", submitted);
                        if (!rawText.get().isBlank()) record.addProperty("rawPlan", rawText.get());
                        record.addProperty("durationMs", (System.nanoTime() - start) / 1_000_000);
                        synchronized (report) {
                          report.add(record);
                          Files.writeString(
                              output,
                              new GsonBuilder().setPrettyPrinting().create().toJson(report));
                        }
                        System.out.println(
                            "Complex planning audit "
                                + row.get("id").getAsString()
                                + ": "
                                + record.get("status").getAsString()
                                + " ("
                                + record.get("durationMs").getAsLong()
                                + " ms)");
                      }
                    }
                  } catch (Exception e) {
                    throw new CompletionException(e);
                  }
                }));
      }
      for (var job : jobs) job.get(20, TimeUnit.MINUTES);
    }
    assertEquals(selection.contains("") ? 20 : selection.size(), report.size());
  }

  /** Capture only synthetic agentMessage text. Account/config RPC responses are never saved. */
  private static CodexRpc recordedLaunch(
      CodexConfig config, Path workspace, java.util.concurrent.atomic.AtomicReference<String> text)
      throws Exception {
    Files.createDirectories(workspace);
    var command =
        new ArrayList<>(
            List.of(
                config.executable,
                "app-server",
                "--listen",
                "stdio://",
                "-c",
                "mcp_servers={}",
                "-c",
                "web_search=\"disabled\"",
                "-c",
                "model_provider=\"openai\""));
    for (String feature : CodexRpc.DISABLED_FEATURES) command.addAll(List.of("--disable", feature));
    var builder = new ProcessBuilder(command).directory(workspace.toFile());
    builder.environment().remove("OPENAI_API_KEY");
    builder.environment().remove("AZURE_OPENAI_API_KEY");
    var process = builder.start();
    Thread.ofVirtual()
        .start(
            () -> {
              try (var error = process.getErrorStream()) {
                error.transferTo(java.io.OutputStream.nullOutputStream());
              } catch (Exception ignored) {
              }
            });
    var reader =
        new java.io.FilterReader(
            new java.io.InputStreamReader(
                process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)) {
          final StringBuilder line = new StringBuilder();
          boolean oversized;
          final char[] single = new char[1];

          public int read() throws java.io.IOException {
            int n = read(single, 0, 1);
            return n < 0 ? -1 : single[0];
          }

          public int read(char[] chars, int offset, int length) throws java.io.IOException {
            int n = super.read(chars, offset, length);
            for (int i = offset; i < offset + Math.max(0, n); i++) {
              if (chars[i] == '\n') {
                if (!oversized)
                  try {
                    var message = JsonParser.parseString(line.toString()).getAsJsonObject();
                    if (message.has("method")
                        && message.get("method").getAsString().equals("item/completed")) {
                      var item = message.getAsJsonObject("params").getAsJsonObject("item");
                      if (item != null
                          && item.has("type")
                          && item.get("type").getAsString().equals("agentMessage"))
                        text.set(item.get("text").getAsString());
                    }
                  } catch (RuntimeException ignored) {
                  }
                line.setLength(0);
                oversized = false;
              } else if (line.length() < 65536 && !oversized) line.append(chars[i]);
              else oversized = true;
            }
            return n;
          }
        };
    return new CodexRpc(
        reader,
        new java.io.OutputStreamWriter(
            process.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8),
        java.time.Duration.ofSeconds(config.requestTimeoutSeconds),
        process);
  }

  private JsonObject observation(int id) {
    var items = new JsonArray();
    String[] ids =
        switch (id) {
          case 1, 7, 11 ->
              new String[] {
                "netherite_sword",
                "netherite_helmet",
                "netherite_chestplate",
                "netherite_leggings",
                "netherite_boots"
              };
          case 3 -> new String[] {"iron_hoe", "water_bucket", "wheat_seeds"};
          case 4, 20 -> new String[] {"iron_pickaxe"};
          case 14 -> new String[] {"stone_pickaxe"};
          case 6 -> new String[] {"iron_axe"};
          case 8 -> new String[] {"wheat_seeds", "carrot", "potato", "beetroot_seeds"};
          case 9 -> new String[] {"raw_iron", "raw_gold", "spruce_log"};
          case 12 -> new String[] {"bread"};
          case 17 -> new String[] {"spruce_log", "stick"};
          case 18 -> new String[] {"flint_and_steel", "diamond"};
          case 19 -> new String[] {"raw_iron", "coal"};
          default -> new String[] {};
        };
    for (int i = 0; i < ids.length; i++) {
      int slot = i, count = 1;
      if (Set.of(1, 7, 11).contains(id)) slot = i == 0 ? 41 : 35 + i;
      else if (Set.of(4, 6, 14, 20).contains(id)) slot = 41;
      else if (id == 3) count = i == 2 ? 8 : 1;
      else if (id == 8) {
        slot = 20 + i;
        count = 8;
      } else if (id == 9) count = new int[] {8, 4, 4}[i];
      else if (id == 12) {
        slot = 1;
        count = 8;
      } else if (id == 17) count = 2;
      else if (id == 18) {
        slot = i == 0 ? 0 : 2;
        count = i == 0 ? 1 : 7;
      } else if (id == 19) count = i == 0 ? 3 : 1;
      var stack = object("slot", slot, "item", "minecraft:" + ids[i], "count", count);
      if (id == 14) {
        stack.addProperty("damage", 130);
        stack.addProperty("max_damage", 131);
      }
      items.add(stack);
    }
    items.add(object("slot", 30, "item", "minecraft:bread", "count", 16));
    var locations = new JsonArray();
    var chests = new JsonArray();
    var o =
        object(
            "health",
            20,
            "food",
            20,
            "position",
            new Gson().toJsonTree(List.of(0, 64, 0)),
            "dimension",
            "minecraft:overworld",
            "inventory",
            items,
            "inventory_complete",
            true,
            "main_hand_slot",
            41,
            "off_hand_slot",
            40,
            "locations",
            locations,
            "nearby",
            new JsonArray(),
            "nearby_chests",
            chests,
            "drops",
            new JsonArray(),
            "personal_food_to_preserve",
            16);
    if (id == 2) {
      chest(chests, locations, "bones", 5, 10, List.of());
      chest(chests, locations, "flesh", 9, 10, List.of());
      chest(chests, locations, "powder", 13, 10, List.of());
    }
    if (id == 5) {
      chest(
          chests,
          locations,
          "source",
          8,
          0,
          List.of(
              object("slot", 0, "item", "minecraft:spruce_log", "count", 64),
              object("slot", 7, "item", "minecraft:raw_iron", "count", 17),
              object("slot", 26, "item", "minecraft:bread", "count", 5)));
      chest(chests, locations, "destination", 30, 0, List.of());
    }
    if (id == 10) {
      var contents = new ArrayList<JsonObject>();
      for (int i = 0; i < 42; i++)
        contents.add(object("slot", i, "item", "minecraft:iron_sword", "count", 1));
      chest(chests, locations, "source", 8, 0, contents);
      chest(chests, locations, "destination", 30, 0, List.of());
      chests.forEach(e -> e.getAsJsonObject().addProperty("slots", 54));
    }
    if (Set.of(6, 13, 16, 20).contains(id)) chest(chests, locations, "base", 5, 10, List.of());
    if (id == 9) {
      chest(chests, locations, "iron", 4, 10, List.of());
      chest(chests, locations, "gold", 8, 10, List.of());
    }
    if (id == 14)
      chest(
          chests,
          locations,
          "spare_tools_and_storage",
          5,
          10,
          List.of(object("slot", 0, "item", "minecraft:iron_pickaxe", "count", 1)));
    if (id == 15) {
      var contents = new ArrayList<JsonObject>();
      for (int i = 0; i < 26; i++)
        contents.add(object("slot", i, "item", "minecraft:cobblestone", "count", 64));
      contents.add(object("slot", 26, "item", "minecraft:coal", "count", 32));
      chest(chests, locations, "primary", 5, 10, contents);
      chest(chests, locations, "backup", 9, 10, List.of());
    }
    if (id == 16)
      locations.add(
          object(
              "name",
              "remote",
              "kind",
              "chest",
              "dimension",
              "minecraft:overworld",
              "x",
              384,
              "y",
              64,
              "z",
              0));
    if (id == 11) {
      var actors = o.getAsJsonArray("nearby");
      String[] types = {"skeleton", "zombie", "spider", "villager"};
      int[] xs = {8, 12, 15, 12};
      for (int i = 0; i < 4; i++)
        actors.add(
            object(
                "entity_id",
                101 + i,
                "type",
                "minecraft:" + types[i],
                "hostile",
                i < 3,
                "health",
                i == 2 ? 16 : 20,
                "position",
                new Gson().toJsonTree(List.of(xs[i], 64, i == 3 ? 3 : 0))));
    }
    if (id == 12 || id == 17)
      o.add(
          "teammates",
          new Gson()
              .toJsonTree(
                  List.of(
                      object(
                          "entity_id",
                          105,
                          "available",
                          true,
                          "same_owner",
                          true,
                          "position",
                          new Gson().toJsonTree(List.of(id == 12 ? 26 : 6, 64, 0)),
                          "inventory",
                          new Gson()
                              .toJsonTree(
                                  id == 17
                                      ? List.of(
                                          object(
                                              "slot", 0, "item", "minecraft:raw_iron", "count", 3))
                                      : List.of())))));
    String facts =
        switch (id) {
          case 1 ->
              "Skeleton spawner at [2,64,0], no skeletons yet. Stay within three blocks of"
                  + " [0,64,0]. Skeletons may spawn later; do not invent IDs.";
          case 2 ->
              "Known category chests at [5,64,10] bones, [9,64,10] rotten flesh, [13,64,10]"
                  + " gunpowder. No drops yet; later waves are unknown.";
          case 3 ->
              "Cobblestone encloses dirt at x=8..10,z=5..7,y=63; center [9,63,6] is irrigation"
                  + " location, solid support below it. Hoe, one water bucket and eight seeds are"
                  + " carried.";
          case 4 ->
              "Twenty-four reachable diamond ore blocks at x=8..13,z=5..8,y=64. Carried iron"
                  + " pickaxe, no diamonds yet. Crafting table at [4,64,4].";
          case 5 ->
              "Source [8,64,0] observed slots: 0 spruce_log 64; 7 raw_iron 17; 26 bread 5."
                  + " Destination [30,64,0] empty. Preserve item components.";
          case 6 ->
              "Eight spruce and eight birch logs near [10,64,8]; crafting table [4,64,4], origin"
                  + " storage [5,64,10].";
          case 7 ->
              "Gate at [8,64,6]. No zombie visible yet; it can arrive later. Origin [0,64,0].";
          case 8 ->
              "Two mature plants each of wheat, carrots, potatoes, beetroots at x=7..10,z=5"
                  + " and7,y=64. Farmland below. An immature wheat plant [12,64,7] must remain.";
          case 9 ->
              "Actual inputs raw_iron 8, raw_gold 4 and spruce_log 4. Furnace [5,64,5], separate"
                  + " storage iron [4,64,10] gold [8,64,10].";
          case 10 ->
              "Source double chest has 42 non-stackable iron swords in slots 0..41; NPC bag has 36"
                  + " slots, one occupied by food. Destination is empty double chest.";
          case 11 ->
              "Visible entities skeleton id101 health20 at[8,64,0], zombie id102 health20"
                  + " at[12,64,0], spider id103 health16 at[15,64,0], villager id104 at[12,64,3].";
          case 12 ->
              "Same-owner companion id105 at[26,64,0], initially outside nearby observation radius."
                  + " Path has slabs and a gate, origin [0,64,0]. Carry eight bread for delivery.";
          case 13 ->
              "Five iron ingots and three gold ingots dropped in a contained one-block water pool"
                  + " [12,64,6]; accessible solid bank around it; destination [5,64,10].";
          case 14 ->
              "Held stone pickaxe has one durability left. Spare iron pickaxe in chest [5,64,10]."
                  + " Eight stone blocks at x=8..15,z=8,y=64.";
          case 15 ->
              "64 coal dropped at[12,64,6]. Designated chest[5,64,10] only has 32 compatible"
                  + " spaces; backup[9,64,10] is empty.";
          case 16 ->
              "Known remote chest[384,64,0] has 64 iron ingots. Return to origin chest[5,64,10]."
                  + " Both locations loaded along a safe route.";
          case 17 ->
              "Available same-owner companion id105 at[6,64,0], carrying three raw iron. Leader has"
                  + " two logs and two sticks. Crafting table[4,64,4], furnace[5,64,5].";
          case 18 ->
              "Unlit actual obsidian frame at[7,64,8]. Carry seven diamonds as cargo and flint and"
                  + " steel. Return through actual portal, no teleport or invented coordinate.";
          case 19 ->
              "Furnace[5,64,5] reserved by another companion with two raw gold inside;"
                  + " furnace[9,64,5] is free. Carry three raw iron and one coal.";
          default ->
              "Mine 22 stone blocks and store at origin chest[5,64,10]. The owner may change"
                  + " instruction to wait before completion; old work must then stop.";
        };
    o.addProperty("verified_fixture_facts", facts);
    return o;
  }

  private static void chest(
      JsonArray chests, JsonArray locations, String name, int x, int z, List<JsonObject> contents) {
    locations.add(
        object(
            "name",
            name,
            "kind",
            "chest",
            "dimension",
            "minecraft:overworld",
            "x",
            x,
            "y",
            64,
            "z",
            z));
    chests.add(
        object("x", x, "y", 64, "z", z, "slots", 27, "items", new Gson().toJsonTree(contents)));
  }
}

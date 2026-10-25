package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;
import static org.junit.jupiter.api.Assertions.*;

import dev.avatarcraft.npc.Personality;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Explicit opt-in: consumes the signed-in Codex account's usage, never reads or prints its tokens.
 */
@EnabledIfEnvironmentVariable(named = "AVATARCRAFT_CODEX_LIVE", matches = "1")
@Timeout(240)
class CodexLiveTest {
  @TempDir Path workspace;

  @Test
  void verifiedCompletionSpeechUsesConfiguredCharacterVoice() throws Exception {
    var config = new CodexConfig();
    if (System.getenv("AVATARCRAFT_CODEX_EXECUTABLE") != null)
      config.executable = System.getenv("AVATARCRAFT_CODEX_EXECUTABLE");
    var profile = Personality.preset("tortobu");
    profile.instructions = "元気な案内役。所有者へ短く明るく話す。";
    profile.speakingStyle = "日本語で、語尾に『ぞ』を使って元気に話す。";
    try (var bridge = new CodexBridge(config, workspace)) {
      bridge.initialize().get(40, TimeUnit.SECONDS);
      String speech =
          NpcCompletionSpeech.generate(
                  CompletableFuture.completedFuture(bridge),
                  profile,
                  object(
                      "state",
                      "SUCCEEDED",
                      "summary",
                      "原木64個を指定チェストへ収納",
                      "verified",
                      object("moved", 64)))
              .get(40, TimeUnit.SECONDS);
      assertTrue(speech.contains("ぞ"), speech);
      assertTrue(speech.contains("待"), speech);
      assertFalse(speech.contains("未確認"), speech);
      java.nio.file.Files.writeString(
          Path.of("build/npc-completion-live-result.json"),
          new com.google.gson.GsonBuilder()
              .setPrettyPrinting()
              .create()
              .toJson(
                  object(
                      "speakingStyle",
                      profile.speakingStyle,
                      "speech",
                      speech,
                      "verifiedState",
                      "SUCCEEDED")));
    }
  }

  @Test
  void repeatedFailureProducesThreeToFiveValidatedRecoverySlots() throws Exception {
    var config = new CodexConfig();
    if (System.getenv("AVATARCRAFT_CODEX_EXECUTABLE") != null)
      config.executable = System.getenv("AVATARCRAFT_CODEX_EXECUTABLE");
    var profile = Personality.preset("tortobu");
    String allowed = NpcTools.effective(profile.allowedSkills).toString();
    profile.allowedSkills.clear();
    try (var bridge = new CodexBridge(config, workspace)) {
      bridge.initialize().get(40, TimeUnit.SECONDS);
      var session =
          bridge
              .openAdvanced(
                  profile,
                  (name, args) -> {
                    fail("Recovery planner must never execute game actions");
                    return CompletableFuture.completedFuture(object("ok", false));
                  })
              .get(40, TimeUnit.SECONDS);
      var plan =
          session
              .plan(
                  NpcRecoveryQueue.prompt(
                          "手持ちのトウヒ原木64個を指定のチェストへしまって待機してください。",
                          "Direct movement to the chest cell failed twice; none of the logs have"
                              + " been deposited.")
                      + "\nLocal executor allowedSkills: "
                      + allowed
                      + "\nObservation: "
                      + """
                      {"health":20,"food":20,"position":[8,67,3],"dimension":"minecraft:overworld",
                      "inventory":[{"slot":0,"item":"minecraft:spruce_log","count":64}],
                      "locations":[{"name":"chest_1","kind":"chest","dimension":"minecraft:overworld","x":10,"y":67,"z":3}],
                      "nearby_chests":[{"x":10,"y":67,"z":3,"items":[]}],
                      "walkable_positions":[[9,67,3],[9,67,4]],
                      "execution_memory":{"failedTargets":[{"tool":"move_to","arguments":{"x":10,"y":67,"z":3},"code":"UNREACHABLE","attempts":2}]}}
                      """)
              .get(180, TimeUnit.SECONDS);
      assertTrue(plan.blocked().isEmpty(), plan.blocked());
      var queue = NpcRecoveryQueue.schedule(plan);
      assertTrue(queue.steps().size() >= 4 && queue.steps().size() <= 6);
      assertEquals("plan_next", queue.steps().getLast().action());
      TaskPlan.parse(new com.google.gson.Gson().toJson(queue));
      java.nio.file.Files.writeString(
          Path.of("build/npc-recovery-live-plan.json"),
          new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(queue));
      session.close();
      System.out.println(
          "Live external recovery: validated 3 to 5 reservations, original goal retained");
    }
  }

  @Test
  void cooperativeMiningPlansReachLocalExecutorAndRealBlockersRemainExplicit() throws Exception {
    var config = new CodexConfig();
    if (System.getenv("AVATARCRAFT_CODEX_EXECUTABLE") != null)
      config.executable = System.getenv("AVATARCRAFT_CODEX_EXECUTABLE");
    var profile = Personality.preset("tortobu");
    String permissions = NpcTools.effective(profile.allowedSkills).toString();
    profile.allowedSkills.clear();
    String observation =
        """
        Observation: {"health":20,"food":20,"position":[8,67,5],
        "inventory":[{"slot":41,"item":"minecraft:diamond_pickaxe","count":1,"equipment":"mainhand"}],
        "dimension":"minecraft:overworld","locations":[{"name":"chest_1","dimension":"minecraft:overworld","x":10,"y":67,"z":3,"kind":"chest"}],
        "nearby_chests":[{"x":10,"y":67,"z":3,"items":[]}],
        "teammates":[],"local_exploration":"Recursive acquire, real recipes, smelting and same-owner delegation; no available teammates in this observation."}
        """;
    try (var bridge = new CodexBridge(config, workspace)) {
      bridge.initialize().get(40, TimeUnit.SECONDS);
      for (String instruction :
          java.util.List.of(
              "仲間と協力して必要な道具を用意し、ダイヤモンドを1個採掘してチェストに入れる。",
              "Work together to prepare tools, mine one diamond, and put it in the chest.")) {
        var session =
            bridge
                .open(
                    profile,
                    (name, args) -> {
                      fail("The planner must not execute tools");
                      return CompletableFuture.completedFuture(object("ok", false));
                    })
                .get(40, TimeUnit.SECONDS);
        var plan =
            session
                .plan(
                    "Authenticated owner instruction: "
                        + instruction
                        + "\n"
                        + observation
                        + "\nLocal executor allowedSkills: "
                        + permissions)
                .get(180, TimeUnit.SECONDS);
        assertTrue(plan.blocked().isEmpty(), plan.blocked());
        assertTrue(
            plan.steps().stream()
                .anyMatch(
                    s ->
                        java.util.Set.of("collect", "acquire").contains(s.action())
                            && s.target().equals("minecraft:diamond")
                            && s.count() == 1));
        assertTrue(
            plan.steps().stream()
                .anyMatch(
                    s ->
                        s.action().equals("deposit")
                            && s.target().equals("minecraft:diamond")
                            && s.count() == 1));
        session.close();
      }
      var strict =
          bridge
              .open(profile, (name, args) -> CompletableFuture.completedFuture(object("ok", false)))
              .get(40, TimeUnit.SECONDS);
      var blocked =
          strict
              .plan(
                  "Authenticated owner instruction: 仲間のNPCへ採掘作業を委任してください。単独での採掘は禁止します。"
                      + "\n"
                      + observation
                      + "\nLocal executor allowedSkills: "
                      + permissions)
              .get(180, TimeUnit.SECONDS);
      assertFalse(blocked.blocked().isBlank());
      assertTrue(blocked.steps().isEmpty());
      strict.close();
      System.out.println(
          "Live planning: Japanese/English cooperative mining accepted; mandatory delegation"
              + " refused");
    }
  }

  @Test
  void advancedPlannerDividesEmptyHandedAndNetherMissions() throws Exception {
    var config = new CodexConfig();
    if (System.getenv("AVATARCRAFT_CODEX_EXECUTABLE") != null)
      config.executable = System.getenv("AVATARCRAFT_CODEX_EXECUTABLE");
    var profile = Personality.preset("tortobu");
    String allowed = NpcTools.effective(profile.allowedSkills).toString();
    profile.allowedSkills.clear();
    try (var bridge = new CodexBridge(config, workspace)) {
      bridge.initialize().get(40, TimeUnit.SECONDS);
      var inventory = bridge.models().get(30, TimeUnit.SECONDS).getAsJsonArray("data");
      assertNotNull(inventory);
      var preferred =
          inventory.asList().stream()
              .map(com.google.gson.JsonElement::getAsJsonObject)
              .filter(m -> m.has("isDefault") && m.get("isDefault").getAsBoolean())
              .findFirst()
              .orElse(inventory.get(0).getAsJsonObject());
      config.complexModel = preferred.get("model").getAsString();
      String observation =
          """
          Observation: {"health":20,"food":20,"position":[8,67,5],"inventory":[],"dimension":"minecraft:overworld",
          "locations":[{"name":"chest_1","dimension":"minecraft:overworld","x":10,"y":67,"z":3,"kind":"chest"}],
          "nearby_chests":[{"x":10,"y":67,"z":3,"items":[]}],
          "teammates":[{"entity_id":101,"name":"workerA","task":"","available":true,"inventory":[]},{"entity_id":102,"name":"workerB","task":"","available":true,"inventory":[]}],
          "local_exploration":"Recursive acquire from empty inventory, vanilla smelting, real crafting, actual delivery, safe stairs, finite delegated plans and native portal walking."}
          """;
      var results = new com.google.gson.JsonArray();
      for (String instruction :
          java.util.List.of(
              "仲間と木材・石材の準備を分担して、何も持っていない状態から道具を作り、ダイヤモンドを1個採掘して拠点のチェストへ収納して。",
              "複数NPCでネザーへ行ってネザー要塞を探してください。探索する担当と、道具・資材を準備する担当に分け、必要なら段階ごとに再計画してください。")) {
        var session =
            bridge
                .openAdvanced(
                    profile,
                    (name, args) -> {
                      fail("Planner must not execute tools while making assignments");
                      return CompletableFuture.completedFuture(object("ok", false));
                    })
                .get(40, TimeUnit.SECONDS);
        var plan =
            session
                .plan(
                    "Authenticated owner instruction: "
                        + instruction
                        + "\n"
                        + observation
                        + "\nLocal executor allowedSkills: "
                        + allowed)
                .get(180, TimeUnit.SECONDS);
        assertTrue(plan.blocked().isEmpty(), plan.blocked());
        assertTrue(
            plan.steps().stream()
                .anyMatch(
                    s ->
                        s.action().equals("delegate")
                            || s.action().equals("tool")
                                && s.target().equals("bep_3davater_companion:delegate_task")),
            "Advanced planner must actually assign work");
        results.add(new com.google.gson.Gson().toJsonTree(plan));
        session.close();
      }
      var report = object("model", config.complexModel, "reasoning", "high", "plans", results);
      java.nio.file.Files.writeString(
          Path.of("build/advanced-live-plan-result.json"),
          new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
      System.out.println(
          "Advanced live cooperative and Nether planning verified with " + config.complexModel);
    }
  }

  @Test
  void managedOAuthInvokesNpcToolAndReturnsCharacterSpeech() throws Exception {
    var config = new CodexConfig();
    if (System.getenv("AVATARCRAFT_CODEX_EXECUTABLE") != null)
      config.executable = System.getenv("AVATARCRAFT_CODEX_EXECUTABLE");
    try (var bridge = new CodexBridge(config, workspace)) {
      bridge.initialize().get(40, TimeUnit.SECONDS);
      assertTrue(bridge.status().contains("OAuth"));
      var count = new AtomicInteger();
      var waits = new AtomicInteger();
      var session =
          bridge
              .open(
                  Personality.preset("tortobu"),
                  (name, args) -> {
                    if (name.equals("wait")) {
                      assertEquals(1, args.get("seconds").getAsInt());
                      waits.incrementAndGet();
                      return CompletableFuture.completedFuture(
                          object("ok", true, "elapsed_seconds", 1));
                    }
                    assertEquals("observe", name);
                    count.incrementAndGet();
                    return CompletableFuture.completedFuture(
                        object(
                            "ok",
                            true,
                            "health",
                            20,
                            "inventory",
                            "empty",
                            "task",
                            "protocol test only; no real world"));
                  })
              .get(40, TimeUnit.SECONDS);
      var reply =
          session
              .turn(
                  "これはNPC連携の接続試験です。必ず最初にobserveを1回だけ呼び、取得できた体力を日本語の短い一文で報告してください。他の行動は禁止です。", false)
              .get(180, TimeUnit.SECONDS);
      assertEquals(1, count.get());
      assertFalse(reply.speech().isBlank());
      assertTrue(reply.speech().contains("20"));
      var waited =
          session
              .turn(
                  "接続試験です。必ずnpc_waitをseconds=1で1回呼び、成功したら『待機できたよ』とだけ話して作業完了してください。他のツールは呼ばないでください。",
                  false)
              .get(180, TimeUnit.SECONDS);
      assertEquals(1, waits.get());
      assertTrue(waited.taskComplete());
      assertFalse(waited.speech().isBlank());
      System.out.println("Codex OAuth and dynamic NPC tool: verified");
    }
  }
}

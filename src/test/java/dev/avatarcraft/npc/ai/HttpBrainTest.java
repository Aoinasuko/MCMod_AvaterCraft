package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import dev.avatarcraft.npc.Personality;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;

@Timeout(15)
class HttpBrainTest {
  @Test
  void advancedSessionsUseConfiguredModelWithoutChangingSpeechModel() throws Exception {
    for (boolean anthropic : new boolean[] {false, true})
      try (var peer = new Peer(anthropic, 4, r -> finalResponse(anthropic))) {
        var reject =
            (java.util.function.BiFunction<String, JsonObject, CompletionStage<JsonObject>>)
                (n, a) -> {
                  throw new AssertionError("No tools expected");
                };
        var profile = Personality.preset("tortobu");
        profile.allowedSkills.clear();
        peer.brain.openAdvanced(profile, reject).get().turn("Return speech only", false).get();
        peer.brain.open(profile, reject).get().turn("Return speech only", false).get();
        assertEquals("test-planner-model", peer.requests.get(0).get("model").getAsString());
        assertEquals("test-tool-model", peer.requests.get(1).get("model").getAsString());
        assertFalse(peer.requests.get(0).has("reasoning_effort"));
      }
  }

  @Test
  void japaneseAndEnglishInstructionPlanningIsStructuredAndCannotInvokeTools() throws Exception {
    var expected =
        LocalTaskRunnerTest.plan(LocalTaskRunnerTest.step("collect", "minecraft:cobblestone", 16));
    String json = new Gson().toJson(expected);
    for (boolean anthropic : new boolean[] {false, true}) {
      try (var peer =
          new Peer(
              anthropic,
              4,
              r ->
                  anthropic
                      ? object(
                          "stop_reason",
                          "end_turn",
                          "content",
                          array(object("type", "text", "text", json)))
                      : object(
                          "choices",
                          array(
                              object(
                                  "finish_reason",
                                  "stop",
                                  "message",
                                  object("role", "assistant", "content", json)))))) {
        var profile = Personality.preset("tortobu");
        profile.allowedSkills.clear();
        var session =
            peer.brain
                .open(
                    profile,
                    (n, a) -> {
                      throw new AssertionError("Planner must not execute tools");
                    })
                .get();
        for (String prompt : List.of("丸石を16個集めて", "Collect 16 cobblestone"))
          assertEquals(expected, session.plan(prompt).get());
        assertEquals(2, peer.requests.size());
        for (var request : peer.requests) {
          assertFalse(request.has("tools"));
          assertTrue(
              request.toString().contains("language")
                  || request.toString().contains("Japanese or English"));
        }
      }
    }
    try (var peer =
        new Peer(false, 4, r -> call(false, "bad", "npc_dig", object("x", 1, "y", 2, "z", 3)))) {
      var session =
          peer.brain
              .open(
                  Personality.preset("tortobu"),
                  (n, a) -> {
                    throw new AssertionError("Planner must reject tools");
                  })
              .get();
      assertThrows(ExecutionException.class, () -> session.plan("石を掘って").get());
    }
  }

  private final class Peer implements AutoCloseable {
    final HttpServer server;
    final HttpBrain brain;
    final List<JsonObject> requests = new CopyOnWriteArrayList<>();
    volatile String authorization, version;

    Peer(boolean anthropic, int limit, Function<JsonObject, JsonObject> response) throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      server.createContext(
          anthropic ? "/v1/messages" : "/v1/chat/completions",
          exchange -> {
            try {
              var body =
                  JsonParser.parseString(
                          new String(
                              exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                      .getAsJsonObject();
              authorization =
                  exchange.getRequestHeaders().getFirst(anthropic ? "x-api-key" : "Authorization");
              version = exchange.getRequestHeaders().getFirst("anthropic-version");
              requests.add(body);
              var result = response.apply(body);
              byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().add("Content-Type", "application/json");
              exchange.sendResponseHeaders(result.has("error") ? 401 : 200, bytes.length);
              exchange.getResponseBody().write(bytes);
            } finally {
              exchange.close();
            }
          });
      server.start();
      var c = new CodexConfig();
      c.provider = anthropic ? CodexConfig.Provider.ANTHROPIC : CodexConfig.Provider.OPENAI;
      c.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
      c.apiKey = "test-only-key";
      c.apiKeyEnvironment = "";
      c.model = "test-tool-model";
      c.complexModel = "test-planner-model";
      c.maxToolCalls = limit;
      brain = new HttpBrain(c);
      brain.initialize().get();
    }

    public void close() {
      brain.close();
      server.stop(0);
    }
  }

  private static JsonArray array(JsonObject... objects) {
    var a = new JsonArray();
    for (var o : objects) a.add(o);
    return a;
  }

  private static JsonObject finalResponse(boolean anthropic) {
    String json = "{\"speech\":\"渡しました\",\"taskComplete\":true}";
    return anthropic
        ? object("stop_reason", "end_turn", "content", array(object("type", "text", "text", json)))
        : object(
            "choices",
            array(
                object(
                    "finish_reason",
                    "stop",
                    "message",
                    object("role", "assistant", "content", json))));
  }

  private static JsonObject call(boolean anthropic, String id, String name, JsonObject args) {
    return anthropic
        ? object(
            "stop_reason",
            "tool_use",
            "content",
            array(object("type", "tool_use", "id", id, "name", name, "input", args)))
        : object(
            "choices",
            array(
                object(
                    "finish_reason",
                    "tool_calls",
                    "message",
                    object(
                        "role",
                        "assistant",
                        "content",
                        JsonNull.INSTANCE,
                        "tool_calls",
                        array(
                            object(
                                "id",
                                id,
                                "type",
                                "function",
                                "function",
                                object("name", name, "arguments", args.toString())))))));
  }

  @Test
  void openAiToolRoundTripAndHistory() throws Exception {
    roundTrip(false);
  }

  @Test
  void anthropicToolRoundTripAndHistory() throws Exception {
    roundTrip(true);
  }

  void roundTrip(boolean anthropic) throws Exception {
    var n = new AtomicInteger();
    var executed = new AtomicInteger();
    try (var peer =
        new Peer(
            anthropic,
            4,
            r ->
                n.incrementAndGet() == 1
                    ? call(
                        anthropic,
                        "one",
                        "npc_give_item",
                        object("entity_id", 7, "slot", 3, "count", 2))
                    : finalResponse(anthropic))) {
      var session =
          peer.brain
              .open(
                  Personality.preset("tortobu"),
                  (name, args) -> {
                    assertEquals("give_item", name);
                    assertEquals(2, args.get("count").getAsInt());
                    executed.incrementAndGet();
                    return CompletableFuture.completedFuture(object("ok", true, "moved", 2));
                  })
              .get();
      var reply = session.turn("仲間へ渡してください", false).get();
      assertTrue(reply.taskComplete());
      assertEquals("渡しました", reply.speech());
      assertEquals(1, executed.get());
      assertEquals(anthropic ? "test-only-key" : "Bearer test-only-key", peer.authorization);
      if (anthropic) assertEquals("2023-06-01", peer.version);
      String tools = peer.requests.getFirst().get("tools").toString();
      assertTrue(tools.contains("npc_withdraw"));
      assertTrue(tools.contains(anthropic ? "input_schema" : "parameters"));
      assertFalse(peer.requests.getFirst().toString().contains("test-only-key"));
      String second = peer.requests.get(1).get("messages").toString();
      assertTrue(second.contains(anthropic ? "tool_use_id" : "tool_call_id"));
      assertTrue(second.contains("moved"));
      session.turn("次は待ってください", false).get();
      assertTrue(peer.requests.get(2).get("messages").toString().contains("仲間へ渡してください"));
      session.close();
      assertFalse(session.connected());
    }
  }

  @Test
  void deniedAndMalformedToolsNeverReachGame() throws Exception {
    var executed = new AtomicInteger();
    var n = new AtomicInteger();
    try (var peer =
        new Peer(
            false,
            4,
            r ->
                switch (n.incrementAndGet()) {
                  case 1 -> call(false, "one", "npc_shell", object("command", "no"));
                  case 2 -> call(false, "two", "npc_give_item", object("slot", 3));
                  default -> finalResponse(false);
                })) {
      var p = Personality.preset("tortobu");
      var session =
          peer.brain
              .open(
                  p,
                  (name, args) -> {
                    executed.incrementAndGet();
                    return CompletableFuture.completedFuture(object("ok", true));
                  })
              .get();
      session.turn("観察してください", false).get();
      assertEquals(0, executed.get());
      assertTrue(peer.requests.getLast().toString().contains("Skill request rejected"));
    }
  }

  @Test
  void speechOnlyCannotInvokeToolsOrCompleteWork() throws Exception {
    var n = new AtomicInteger();
    try (var peer =
        new Peer(
            true,
            4,
            r ->
                n.incrementAndGet() == 1
                    ? call(true, "one", "npc_stop", new JsonObject())
                    : finalResponse(true))) {
      var executed = new AtomicInteger();
      var session =
          peer.brain
              .open(
                  Personality.preset("tortobu"),
                  (name, args) -> {
                    executed.incrementAndGet();
                    return CompletableFuture.completedFuture(object("ok", true));
                  })
              .get();
      assertThrows(ExecutionException.class, () -> session.turn("近況を話して", true).get());
      assertFalse(peer.requests.getFirst().has("tools"));
      assertEquals(0, executed.get());
      assertFalse(session.turn("近況を話して", true).get().taskComplete());
    }
  }

  @Test
  void duplicatedCallIdDoesNotRepeatItemTransferAndLimitStopsLoop() throws Exception {
    var executions = new AtomicInteger();
    try (var peer = new Peer(false, 2, r -> call(false, "same", "npc_observe", new JsonObject()))) {
      var session =
          peer.brain
              .open(
                  Personality.preset("tortobu"),
                  (name, args) -> {
                    executions.incrementAndGet();
                    return CompletableFuture.completedFuture(object("ok", true));
                  })
              .get();
      assertThrows(ExecutionException.class, () -> session.turn("確認", false).get());
      assertEquals(1, executions.get());
      assertEquals(3, peer.requests.size());
    }
  }

  @Test
  void stoppingCancelsPendingWorkAndReleasesConcurrency() throws Exception {
    var entered = new CountDownLatch(1);
    try (var peer =
        new Peer(
            false,
            4,
            r -> {
              entered.countDown();
              return call(false, "one", "npc_wait", object("seconds", 10));
            })) {
      var pending = new CompletableFuture<JsonObject>();
      var session = peer.brain.open(Personality.preset("tortobu"), (name, args) -> pending).get();
      var result = session.turn("待って", false);
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      session.stop();
      assertThrows(CancellationException.class, result::join);
      assertTimeoutPreemptively(
          java.time.Duration.ofSeconds(2),
          () -> {
            while (session.busy()) Thread.onSpinWait();
          });
      assertTrue(session.canStart(false));
    }
  }

  @Test
  void httpErrorsDoNotExposeProviderBodyOrKeys() throws Exception {
    try (var peer =
        new Peer(false, 4, r -> object("error", "private-provider-body test-only-key"))) {
      var session =
          peer.brain
              .open(
                  Personality.preset("tortobu"),
                  (name, args) -> CompletableFuture.completedFuture(new JsonObject()))
              .get();
      var error = assertThrows(ExecutionException.class, () -> session.turn("確認", false).get());
      assertTrue(error.getCause().getMessage().contains("401"));
      assertFalse(error.toString().contains("test-only-key"));
      assertFalse(error.toString().contains("private-provider-body"));
    }
  }

  @Test
  void configurationAndReplyBounds() throws Exception {
    var c = new CodexConfig();
    c.provider = CodexConfig.Provider.OPENAI;
    c.model = "model";
    c.baseUrl = "file:///private";
    assertThrows(IllegalArgumentException.class, c::validate);
    c.baseUrl = "http://localhost:1234/v1";
    c.apiKeyEnvironment = "";
    c.validate();
    assertTrue(
        HttpBrain.parseReply("```json\n{\"speech\":\"はい\",\"taskComplete\":true}\n```", false)
            .taskComplete());
    assertFalse(
        HttpBrain.parseReply("{\"speech\":\"はい\",\"taskComplete\":true}", true).taskComplete());
    var intent =
        HttpBrain.parseReply(
            "{\"speech\":\"石を掘るね\",\"taskComplete\":false,\"instruction\":\"石を1つ掘る\"}", true);
    assertEquals("石を1つ掘る", intent.instruction());
    assertFalse(intent.taskComplete());
    assertThrows(
        java.io.IOException.class, () -> HttpBrain.parseReply("I completed everything", false));
    assertThrows(
        java.io.IOException.class,
        () -> HttpBrain.parseReply("{\"speech\":\"はい\",\"taskComplete\":\"true\"}", false));
  }

  @Test
  void workSaturationPreservesASeparateSpeechSlot() throws Exception {
    try (var peer =
        new Peer(
            false,
            4,
            r ->
                r.has("tools")
                    ? call(false, UUID.randomUUID().toString(), "npc_wait", object("seconds", 10))
                    : finalResponse(false))) {
      var blocked = new CompletableFuture<JsonObject>();
      var entered = new CountDownLatch(2);
      var a =
          peer.brain
              .open(
                  Personality.preset("tortobu"),
                  (n, args) -> {
                    entered.countDown();
                    return blocked;
                  })
              .get();
      var b =
          peer.brain
              .open(
                  Personality.preset("tortobu"),
                  (n, args) -> {
                    entered.countDown();
                    return blocked;
                  })
              .get();
      var voice =
          peer.brain
              .open(
                  Personality.preset("tortobu"),
                  (n, args) -> {
                    throw new AssertionError("Speech cannot act");
                  })
              .get();
      a.turn("待機", false);
      b.turn("待機", false);
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      assertFalse(voice.canStart(false));
      assertTrue(voice.canStart(true));
      assertThrows(ExecutionException.class, () -> voice.turn("追加の作業", false).get());
      assertThrows(ExecutionException.class, () -> voice.plan("新しい計画").get());
      assertEquals("渡しました", voice.turn("発言", true).get().speech());
      a.stop();
      b.stop();
    }
  }
}

package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.avatarcraft.npc.Personality;
import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
class CodexBridgeTest {
  @TempDir Path workspace;

  private final class Peer implements AutoCloseable {
    final PipedReader hostInput = new PipedReader(16384), peerInput = new PipedReader(16384);
    final PipedWriter hostOutput = new PipedWriter(peerInput),
        peerOutput = new PipedWriter(hostInput);
    final CodexBridge bridge;
    final AtomicInteger turns = new AtomicInteger();
    volatile boolean rejectedTool, rejectedApproval;
    volatile String turnId;
    volatile boolean planning;
    volatile int dynamicTools;

    Peer(boolean oauth) throws IOException {
      var config = new CodexConfig();
      bridge =
          new CodexBridge(
              config, workspace, new CodexRpc(hostInput, hostOutput, Duration.ofSeconds(3), null));
      Thread.ofVirtual()
          .start(
              () -> {
                try (var lines = new BufferedReader(peerInput)) {
                  for (String line; (line = lines.readLine()) != null; ) {
                    var message = JsonParser.parseString(line).getAsJsonObject();
                    if (!message.has("method")) {
                      if (message.get("id").getAsString().startsWith("approval")) {
                        rejectedApproval = message.has("error");
                        continue;
                      }
                      rejectedTool =
                          !message.getAsJsonObject("result").get("success").getAsBoolean();
                      send(
                          object(
                              "method",
                              "item/completed",
                              "params",
                              object(
                                  "threadId",
                                  "fixture",
                                  "turnId",
                                  turnId,
                                  "item",
                                  object(
                                      "type",
                                      "agentMessage",
                                      "text",
                                      planning
                                          ? new Gson()
                                              .toJson(
                                                  LocalTaskRunnerTest.plan(
                                                      LocalTaskRunnerTest.step("wait", "", 1)))
                                          : "{\"speech\":\"確認できたよ！\",\"taskComplete\":true}"))));
                      send(
                          object(
                              "method",
                              "turn/completed",
                              "params",
                              object(
                                  "threadId",
                                  "fixture",
                                  "turn",
                                  object("id", turnId, "status", "completed"))));
                      continue;
                    }
                    String method = message.get("method").getAsString();
                    if (method.equals("initialized")) continue;
                    JsonObject result = new JsonObject();
                    if (method.equals("account/read"))
                      result = object("account", object("type", oauth ? "chatgpt" : "apiKey"));
                    if (method.equals("config/read"))
                      result =
                          object(
                              "config",
                              object(
                                  "mcp_servers",
                                  object("external_fixture", object("enabled", true))));
                    if (method.equals("mcpServerStatus/list"))
                      result = object("data", new JsonArray());
                    if (method.equals("thread/start")) {
                      var params = message.getAsJsonObject("params");
                      assertFalse(
                          params
                              .getAsJsonObject("config")
                              .getAsJsonObject("mcp_servers")
                              .getAsJsonObject("external_fixture")
                              .get("enabled")
                              .getAsBoolean());
                      assertEquals(0, params.getAsJsonArray("environments").size());
                      dynamicTools = params.getAsJsonArray("dynamicTools").size();
                      result = object("thread", object("id", "fixture"));
                    }
                    if (method.equals("turn/start")) {
                      planning =
                          message
                              .getAsJsonObject("params")
                              .getAsJsonObject("outputSchema")
                              .getAsJsonObject("properties")
                              .has("steps");
                      turnId = "turn-" + turns.incrementAndGet();
                      result = object("turn", object("id", turnId));
                    }
                    send(object("id", message.get("id"), "result", result));
                    if (method.equals("turn/start")) {
                      send(
                          object(
                              "method",
                              "turn/started",
                              "params",
                              object(
                                  "threadId",
                                  "fixture",
                                  "turn",
                                  object("id", turnId, "status", "inProgress"))));
                      send(
                          object(
                              "id",
                              "approval-" + turnId,
                              "method",
                              "item/commandExecution/requestApproval",
                              "params",
                              object("threadId", "fixture")));
                      send(
                          object(
                              "id",
                              "tool-" + turnId,
                              "method",
                              "item/tool/call",
                              "params",
                              object(
                                  "threadId",
                                  "fixture",
                                  "turnId",
                                  turnId,
                                  "callId",
                                  "observe-" + turnId,
                                  "tool",
                                  "npc_observe",
                                  "arguments",
                                  new JsonObject())));
                    }
                  }
                } catch (IOException ignored) {
                }
              });
    }

    synchronized void send(JsonObject message) throws IOException {
      peerOutput.write(message + "\n");
      peerOutput.flush();
    }

    public void close() throws Exception {
      bridge.close();
      peerOutput.close();
      peerInput.close();
    }
  }

  @Test
  void instructionPlannerUsesTaskSchemaAndRejectsEvenUnexpectedCodexToolCalls() throws Exception {
    try (var peer = new Peer(true)) {
      peer.bridge.initialize().get();
      var profile = Personality.preset("tortobu");
      profile.allowedSkills.clear();
      var session =
          peer.bridge
              .open(
                  profile,
                  (name, args) -> {
                    throw new AssertionError("Planning cannot mutate world");
                  })
              .get();
      var plan = session.plan("待って / wait").get();
      assertEquals("wait", plan.steps().getFirst().action());
      assertTrue(peer.rejectedTool);
      assertEquals(0, peer.dynamicTools);
      session.close();
    }
  }

  @Test
  void oauthToolsAndFinalReplyRoundTripWithoutShellApprovals() throws Exception {
    try (var peer = new Peer(true)) {
      peer.bridge.initialize().get(3, TimeUnit.SECONDS);
      var calls = new AtomicInteger();
      var session =
          peer.bridge
              .open(
                  Personality.preset("tortobu"),
                  (name, args) -> {
                    assertEquals("observe", name);
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(object("ok", true, "health", 20));
                  })
              .get(3, TimeUnit.SECONDS);
      var result = session.turn("観察して報告", false).get(3, TimeUnit.SECONDS);
      assertEquals("確認できたよ！", result.speech());
      assertTrue(result.taskComplete());
      assertEquals(1, calls.get());
      assertTrue(peer.rejectedApproval);
      assertFalse(peer.rejectedTool);
      session.turn("発言のみ", true).get(3, TimeUnit.SECONDS);
      assertEquals(1, calls.get());
      assertTrue(peer.rejectedTool);
    }
  }

  @Test
  void apiKeySessionIsNotSilentlyUsedAsOAuth() throws Exception {
    try (var peer = new Peer(false)) {
      assertThrows(
          ExecutionException.class, () -> peer.bridge.initialize().get(3, TimeUnit.SECONDS));
      assertTrue(peer.bridge.status().contains("codex login"));
    }
  }

  @Test
  void toolSchemaAndGameValidationRejectUnknownAndMalformedArguments() {
    var personality = Personality.preset("tortobu");
    assertEquals(17, NpcTools.definitions(personality.allowedSkills).size());
    NpcTools.validate("equip", object("slot", 5, "hand", "main"), personality.allowedSkills);
    assertEquals("equip", NpcTools.localName("npc_equip"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            NpcTools.validate(
                "equip", object("slot", 1.5, "hand", "main"), personality.allowedSkills));
    assertThrows(
        IllegalArgumentException.class,
        () -> NpcTools.validate("equip", object("slot", 1), personality.allowedSkills));
    assertThrows(
        IllegalArgumentException.class,
        () -> NpcTools.validate("shell", new JsonObject(), personality.allowedSkills));
    assertThrows(
        IllegalArgumentException.class,
        () -> NpcTools.validate("observe", object("extra", true), personality.allowedSkills));
    assertThrows(
        IllegalArgumentException.class,
        () -> NpcTools.validate("dig", object("x", .5, "y", 0, "z", 0), personality.allowedSkills));
  }
}

package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import dev.avatarcraft.npc.Personality;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

class NpcCompletionSpeechTest {
  static final class Session implements NpcBrain.Session {
    boolean closed;
    int turns;
    String prompt;
    CompletableFuture<NpcBrain.Reply> response =
        CompletableFuture.completedFuture(
            new NpcBrain.Reply("原木はしまったぞ！ここで待ってるぞ。", true, "__stop__"));

    public boolean busy() {
      return false;
    }

    public boolean connected() {
      return !closed;
    }

    public boolean canStart(boolean voice) {
      return !closed;
    }

    public CompletableFuture<NpcBrain.Reply> turn(String text, boolean voice) {
      assertTrue(voice);
      turns++;
      prompt = text;
      return response;
    }

    public CompletableFuture<TaskPlan> plan(String text) {
      throw new AssertionError("No planning");
    }

    public void stop() {
      closed = true;
    }

    public void close() {
      closed = true;
    }
  }

  static final class Brain implements NpcBrain {
    Personality received;
    BiFunction<String, JsonObject, CompletionStage<JsonObject>> tools;
    CompletableFuture<NpcBrain.Session> session;

    Brain(CompletableFuture<NpcBrain.Session> session) {
      this.session = session;
    }

    public CompletableFuture<Void> initialize() {
      return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<? extends NpcBrain.Session> open(
        Personality p, BiFunction<String, JsonObject, CompletionStage<JsonObject>> executor) {
      received = p;
      tools = executor;
      return session;
    }

    public boolean connected() {
      return true;
    }

    public String status() {
      return "fixture";
    }

    public void close() {}
  }

  @Test
  void completionUsesPersonalityAndVerifiedFactsWithoutActions() {
    var p = Personality.preset("tortobu");
    p.speakingStyle = "元気に、語尾は『だぞ』";
    p.instructions = "頑張り屋で、短くはっきり話す";
    var s = new Session();
    var brain = new Brain(CompletableFuture.completedFuture(s));
    var result =
        NpcCompletionSpeech.generate(
            CompletableFuture.completedFuture(brain),
            p,
            object("state", "SUCCEEDED", "summary", "原木を収納", "verified", object("moved", 64)));
    assertEquals("原木はしまったぞ！ここで待ってるぞ。", result.join());
    assertEquals(p.speakingStyle, brain.received.speakingStyle);
    assertEquals(p.instructions, brain.received.instructions);
    assertTrue(brain.received.allowedSkills.isEmpty());
    assertFalse(p.allowedSkills.isEmpty());
    assertFalse(
        brain.tools.apply("dig", object()).toCompletableFuture().join().get("ok").getAsBoolean());
    assertTrue(s.prompt.contains("VERIFIED_GOAL_COMPLETION") && s.prompt.contains("SUCCEEDED"));
    assertEquals(1, s.turns);
    assertTrue(s.closed);
  }

  @Test
  void uncertainResponsesUseConfiguredCharacterMessage() {
    var p = Personality.preset("tortobu");
    p.completionMessage = "できたぞ！ここで待ってるぞ。";
    for (String text : new String[] {"", "動作が完了したかどうかは未確認です", "I cannot confirm completion"})
      assertEquals(p.completionMessage, NpcCompletionSpeech.accepted(text, p));
    assertEquals("やった、終わったよ！", NpcCompletionSpeech.accepted("やった、終わったよ！", p));
  }

  @Test
  void cancelledCompletionClosesLateSessionWithoutSpeaking() {
    var open = new CompletableFuture<NpcBrain.Session>();
    var brain = new Brain(open);
    var result =
        NpcCompletionSpeech.generate(
            CompletableFuture.completedFuture(brain),
            Personality.preset("tortobu"),
            object("state", "SUCCEEDED"));
    result.cancel(false);
    var s = new Session();
    open.complete(s);
    assertTrue(s.closed);
    assertEquals(0, s.turns);
    assertTrue(result.isCancelled());
  }

  @Test
  void generationErrorClosesSessionForFallback() {
    var s = new Session();
    s.response = CompletableFuture.failedFuture(new IllegalStateException("offline"));
    var result =
        NpcCompletionSpeech.generate(
            CompletableFuture.completedFuture(new Brain(CompletableFuture.completedFuture(s))),
            Personality.preset("tortobu"),
            object("state", "SUCCEEDED"));
    assertThrows(CompletionException.class, result::join);
    assertTrue(s.closed);
  }

  @Test
  void characterCompletionMessageRoundTripsAndIsBounded() {
    var p = Personality.preset("tortobu");
    p.completionMessage = "お仕事、終わりましたわ。ここでお待ちしていますわ。";
    assertEquals(p.completionMessage, Personality.parse(p.json()).completionMessage);
    p.completionMessage = "a".repeat(513);
    assertThrows(IllegalArgumentException.class, p::validate);
  }
}

package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;
import static org.junit.jupiter.api.Assertions.*;

import dev.avatarcraft.npc.Personality;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class NpcBlockedSpeechTest {
  @Test
  void pauseUsesPersonalityAndCannotChangeTheJobOrExecuteTools() {
    var p = Personality.preset("tortobu");
    p.speakingStyle = "一人称はわたくし。丁寧に話す";
    var session = new NpcCompletionSpeechTest.Session();
    session.response = CompletableFuture.completedFuture(new NpcBrain.Reply(
        "わたくし、この道は安全を確認できませんので、ここでお待ちします。", false, "__stop__"));
    var brain = new NpcCompletionSpeechTest.Brain(CompletableFuture.completedFuture(session));
    var reply = NpcBlockedSpeech.generate(CompletableFuture.completedFuture(brain), p,
        object("reason", "No supported walking route", "verifiedCount", 3)).join();
    assertTrue(reply.startsWith("わたくし"));
    assertTrue(session.prompt.contains("VERIFIED_WORK_PAUSE"));
    assertTrue(session.prompt.contains("No supported walking route"));
    assertTrue(brain.received.allowedSkills.isEmpty());
    assertEquals(p.speakingStyle, brain.received.speakingStyle);
    assertTrue(session.closed);
  }

  @Test
  void cancellationDiscardsLatePauseAndClosesItsSession() {
    var session = new NpcCompletionSpeechTest.Session();
    session.response = new CompletableFuture<>();
    var brain = new NpcCompletionSpeechTest.Brain(CompletableFuture.completedFuture(session));
    var reply = NpcBlockedSpeech.generate(CompletableFuture.completedFuture(brain),
        Personality.preset("tortobu"), object("reason", "blocked"));
    reply.cancel(false);
    session.response.complete(new NpcBrain.Reply("古い保留通知", false));
    assertTrue(reply.isCancelled());
    assertTrue(session.closed);
  }
}

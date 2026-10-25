package dev.avatarcraft.npc.ai;

import static org.junit.jupiter.api.Assertions.*;
import static dev.avatarcraft.npc.ai.CodexBridge.object;
import dev.avatarcraft.npc.Personality;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class NpcDeathSpeechTest {
  @Test void characterAndCapturedDeathCoordinatesReachSpeechOnlyAi() {
    var profile = Personality.preset("tortobu");
    var facts = object("dimension", "minecraft:the_nether", "x", -123, "y", 61, "z", 45);
    var session = new NpcCompletionSpeechTest.Session();
    session.response = CompletableFuture.completedFuture(new NpcBrain.Reply("ぼく、そこで倒れちゃった。戻って待つね。", false));
    var brain = new NpcCompletionSpeechTest.Brain(CompletableFuture.completedFuture(session));
    String reply = NpcDeathSpeech.generate(CompletableFuture.completedFuture(brain), profile, facts).join();
    assertTrue(session.prompt.contains("VERIFIED_NPC_DEATH") && session.prompt.contains("-123") && session.prompt.contains("minecraft:the_nether"));
    assertEquals(profile.speakingStyle, brain.received.speakingStyle); assertTrue(brain.received.allowedSkills.isEmpty());
    assertTrue(reply.contains("ぼく") && reply.contains("X:-123 Y:61 Z:45"));
    assertTrue(session.closed); assertEquals(1, session.turns);
    assertFalse(brain.tools.apply("dig", object()).toCompletableFuture().join().get("ok").getAsBoolean());
  }
  @Test void logoutCancellationBeforeConnectionDoesNotOpenAiAndFallbackRetainsCoordinates() {
    var connection = new CompletableFuture<NpcBrain>();
    var profile = Personality.preset("tortobu");
    var facts = object("dimension", "minecraft:overworld", "x", 0, "y", -20, "z", -1);
    var future = NpcDeathSpeech.generate(connection, profile, facts); future.cancel(false);
    var session = new NpcCompletionSpeechTest.Session();
    var brain = new NpcCompletionSpeechTest.Brain(CompletableFuture.completedFuture(session));
    connection.complete(brain);
    assertNull(brain.received); assertEquals(0, session.turns);
    String fallback = NpcDeathSpeech.accepted("", profile, facts);
    assertTrue(fallback.contains("ぼく") && fallback.contains("X:0 Y:-20 Z:-1"));
    assertTrue(NpcDeathSpeech.accepted("あ".repeat(1000), profile, facts).length() <= 512);
  }
}

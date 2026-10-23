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

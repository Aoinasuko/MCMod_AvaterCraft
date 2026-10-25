package dev.avatarcraft.npc.ai;

import com.google.gson.*;
import dev.avatarcraft.npc.Personality;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Explicitly opt-in GameTest provider. Real OAuth plans, no planner-side game tools or speech
 * calls.
 */
final class NpcComplexLiveBrain implements NpcBrain {
  private final CompletableFuture<CodexBridge> bridge;
  final List<TaskPlan> plans = new CopyOnWriteArrayList<>();
  final List<String> errors = new CopyOnWriteArrayList<>();
  volatile boolean waiting;
  volatile int requests;
  private volatile boolean closed;
  private final Supplier<String> fixtureContext;

  NpcComplexLiveBrain(String caseId, Supplier<String> fixtureContext) {
    this.fixtureContext = fixtureContext;
    if (!Boolean.getBoolean("avatarcraft.complexLive"))
      throw new IllegalStateException("Live QA opt-in required");
    bridge =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                var config = new CodexConfig();
                config.executable =
                    System.getenv().getOrDefault("AVATARCRAFT_CODEX_EXECUTABLE", config.executable);
                config.turnTimeoutSeconds = 120;
                var client =
                    new CodexBridge(
                        config, Path.of("../build/npc-complex-live-workspaces/" + caseId));
                client.initialize().get(45, TimeUnit.SECONDS);
                if (closed) client.close();
                return client;
              } catch (Exception e) {
                throw new CompletionException(e);
              }
            });
  }

  public CompletableFuture<Void> initialize() {
    return bridge.thenApply(b -> null);
  }

  public boolean connected() {
    return !closed;
  }

  public String status() {
    return "Live Codex OAuth GameTest";
  }

  public CompletableFuture<? extends Session> open(
      Personality p, BiFunction<String, JsonObject, CompletionStage<JsonObject>> tools) {
    return openSession(p, tools, false);
  }

  public CompletableFuture<? extends Session> openAdvanced(
      Personality p, BiFunction<String, JsonObject, CompletionStage<JsonObject>> tools) {
    return openSession(p, tools, true);
  }

  private CompletableFuture<? extends Session> openSession(
      Personality p,
      BiFunction<String, JsonObject, CompletionStage<JsonObject>> tools,
      boolean advanced) {
    waiting = true;
    return bridge
        .thenCompose(b -> advanced ? b.openAdvanced(p, tools) : b.open(p, tools))
        .thenApply(
            real ->
                new Session() {
                  public boolean busy() {
                    return real.busy();
                  }

                  public boolean connected() {
                    return real.connected();
                  }

                  public boolean canStart(boolean voice) {
                    return real.canStart(voice);
                  }

                  public CompletableFuture<Reply> turn(String prompt, boolean voice) {
                    return CompletableFuture.completedFuture(new Reply("", false));
                  }

                  public CompletableFuture<TaskPlan> plan(String prompt) {
                    if (++requests > 24)
                      return CompletableFuture.failedFuture(
                          new IllegalStateException("QA live planning budget exhausted"));
                    waiting = true;
                    return real.plan(prompt + "\n" + fixtureContext.get())
                        .whenComplete(
                            (plan, error) -> {
                              if (error == null) plans.add(plan);
                              else errors.add(error.toString());
                              waiting = false;
                            });
                  }

                  public void stop() {
                    real.stop();
                  }

                  public void close() {
                    real.close();
                  }
                })
        .whenComplete(
            (s, e) -> {
              waiting = false;
              if (e != null) errors.add(e.toString());
            });
  }

  public void close() {
    closed = true;
    bridge.thenAccept(CodexBridge::close);
  }
}

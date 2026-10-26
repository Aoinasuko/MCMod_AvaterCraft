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
  final List<JsonObject> exchanges = new CopyOnWriteArrayList<>();
  private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
  boolean waiting() { return inFlight.get() > 0; }
  volatile int requests;
  private volatile boolean closed;
  private final Supplier<String> fixtureContext;
  private final int maxRequests;

  NpcComplexLiveBrain(String caseId, Supplier<String> fixtureContext) {
    this(caseId,fixtureContext,24);
  }

  NpcComplexLiveBrain(String caseId, Supplier<String> fixtureContext,int maxRequests) {
    this.fixtureContext = fixtureContext;
    if(maxRequests<1||maxRequests>24)throw new IllegalArgumentException("QA request limit 1..24 required");
    this.maxRequests=maxRequests;
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
                        config, Path.of(System.getProperty("avatarcraft.qaOutputRoot", "../build"))
                            .resolve("npc-complex-live-workspaces").resolve(caseId));
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
    inFlight.incrementAndGet();
    return bridge
        .thenCompose(b -> advanced ? b.openAdvanced(p, tools) : b.open(p, tools))
        .thenApply(
            real ->
                new Session() {
                  final String sessionId = UUID.randomUUID().toString();
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
                    int request = ++requests;
                    if (request > maxRequests)
                      return CompletableFuture.failedFuture(
                          new IllegalStateException("QA live planning budget exhausted"));
                    inFlight.incrementAndGet();
                    String input = prompt + "\n" + fixtureContext.get();
                    long began = System.nanoTime();
                    return real.plan(input)
                        .whenComplete(
                            (plan, error) -> {
                              if (error == null) plans.add(plan);
                              else errors.add(error.toString());
                              exchanges.add(CodexBridge.object("session", sessionId, "request", request,
                                  "input", input, "plan", new com.google.gson.Gson().toJsonTree(plan),
                                  "error", error == null ? "" : error.toString(),
                                  "durationMs", (System.nanoTime()-began)/1_000_000));
                              inFlight.decrementAndGet();
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
              inFlight.decrementAndGet();
              if (e != null) errors.add(e.toString());
            });
  }

  public void close() {
    closed = true;
    bridge.thenAccept(CodexBridge::close);
  }
}

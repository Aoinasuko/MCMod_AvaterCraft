package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.*;
import dev.avatarcraft.npc.Personality;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiFunction;

/** Bounded OpenAI Chat Completions / Anthropic Messages tool loop, entirely off the game thread. */
public final class HttpBrain implements NpcBrain {
  private final CodexConfig config;
  private final HttpClient http;
  private final URI endpoint;
  private final String key;
  private final boolean anthropic;
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final Set<HttpSession> sessions = ConcurrentHashMap.newKeySet();
  private final Semaphore permits, workPermits;
  private volatile boolean closed;
  private static final int MAX_RESPONSE = 2 * 1024 * 1024, MAX_CONTEXT = 262144;

  public HttpBrain(CodexConfig config) {
    config.validate();
    if (config.provider == CodexConfig.Provider.CODEX)
      throw new IllegalArgumentException("HTTP provider required");
    this.config = config;
    anthropic = config.provider == CodexConfig.Provider.ANTHROPIC;
    String base = config.baseUrl.replaceAll("/+$", "");
    endpoint = URI.create(base + (anthropic ? "/messages" : "/chat/completions"));
    key = config.resolvedApiKey();
    if (key.chars().anyMatch(c -> c < 32 || c == 127))
      throw new IllegalArgumentException("Invalid API key");
    http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(config.requestTimeoutSeconds))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    permits = new Semaphore(config.maxConcurrentTurns);
    workPermits = new Semaphore(config.maxConcurrentTurns - 1);
  }

  public CompletableFuture<Void> initialize() {
    if (key.isEmpty() && Set.of("api.openai.com", "api.anthropic.com").contains(endpoint.getHost()))
      return CompletableFuture.failedFuture(new IOException("APIキーをホスト環境のMOD設定または環境変数へ設定してください"));
    return CompletableFuture.completedFuture(null);
  }

  public String status() {
    return closed ? "AI接続停止" : config.provider + " API設定済み";
  }

  public boolean connected() {
    return !closed;
  }

  public CompletableFuture<HttpSession> open(
      Personality p, BiFunction<String, JsonObject, CompletionStage<JsonObject>> tools) {
    return open(p, tools, false);
  }

  public CompletableFuture<HttpSession> openAdvanced(
      Personality p, BiFunction<String, JsonObject, CompletionStage<JsonObject>> tools) {
    return open(p, tools, true);
  }

  private CompletableFuture<HttpSession> open(
      Personality p,
      BiFunction<String, JsonObject, CompletionStage<JsonObject>> tools,
      boolean advanced) {
    if (closed) return CompletableFuture.failedFuture(new IOException("AI is closed"));
    var session = new HttpSession(Personality.parse(p.json()), tools, advanced);
    sessions.add(session);
    if (closed) {
      session.close();
      return CompletableFuture.failedFuture(new IOException("AI is closed"));
    }
    return CompletableFuture.completedFuture(session);
  }

  private static final class Active {
    final CompletableFuture<Reply> reply = new CompletableFuture<>();
    final long deadline;
    volatile Future<?> worker;
    volatile InputStream body;

    Active(int seconds) {
      deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    }

    long remaining() throws TimeoutException {
      long left = deadline - System.nanoTime();
      if (left <= 0) throw new TimeoutException("AI turn timed out");
      return left;
    }

    void cancel() {
      var stream = body;
      if (stream != null)
        try {
          stream.close();
        } catch (IOException ignored) {
        }
      var job = worker;
      if (job != null) job.cancel(true);
    }
  }

  public final class HttpSession implements NpcBrain.Session {
    private final Personality personality;
    private final BiFunction<String, JsonObject, CompletionStage<JsonObject>> tools;
    private final AtomicReference<Active> active = new AtomicReference<>();
    private final Deque<JsonArray> history = new ArrayDeque<>();
    private volatile boolean disposed;

    private final boolean advanced;

    HttpSession(
        Personality p,
        BiFunction<String, JsonObject, CompletionStage<JsonObject>> tools,
        boolean advanced) {
      personality = p;
      this.tools = tools;
      this.advanced = advanced;
    }

    public boolean busy() {
      return active.get() != null;
    }

    public boolean connected() {
      return !closed && !disposed;
    }

    public boolean canStart(boolean speechOnly) {
      return connected()
          && !busy()
          && permits.availablePermits() > 0
          && (speechOnly || workPermits.availablePermits() > 0);
    }

    private void check(Active turn) throws IOException, TimeoutException {
      if (!connected()
          || active.get() != turn
          || turn.reply.isDone()
          || Thread.currentThread().isInterrupted()) throw new IOException("NPC turn cancelled");
      turn.remaining();
    }

    public CompletableFuture<Reply> turn(String prompt, boolean speechOnly) {
      return turn(prompt, speechOnly, false);
    }

    public CompletableFuture<TaskPlan> plan(String prompt) {
      return turn(prompt, true, true).thenApply(Reply::plan);
    }

    private CompletableFuture<Reply> turn(String prompt, boolean speechOnly, boolean planning) {
      boolean workTurn = !speechOnly || planning;
      if (!connected() || prompt == null || prompt.length() > 32768)
        return CompletableFuture.failedFuture(new IOException("Invalid or closed NPC turn"));
      if (workTurn && !workPermits.tryAcquire())
        return CompletableFuture.failedFuture(
            new RejectedExecutionException("NPC work limit reached"));
      if (!permits.tryAcquire()) {
        if (workTurn) workPermits.release();
        return CompletableFuture.failedFuture(
            new RejectedExecutionException("NPC concurrency limit reached"));
      }
      int limit =
          workTurn
              ? config.turnTimeoutSeconds
              : Math.min(config.turnTimeoutSeconds, CodexConfig.SPEECH_TURN_SECONDS);
      var turn = new Active(limit);
      if (!active.compareAndSet(null, turn)) {
        permits.release();
        if (workTurn) workPermits.release();
        return CompletableFuture.failedFuture(new IllegalStateException("NPC is already thinking"));
      }
      turn.reply
          .orTimeout(limit, TimeUnit.SECONDS)
          .whenComplete(
              (r, error) -> {
                if (error != null) turn.cancel();
                if (active.compareAndSet(turn, null)) {
                  permits.release();
                  if (workTurn) workPermits.release();
                }
              });
      try {
        turn.worker =
            workers.submit(
                () -> {
                  try {
                    run(turn, prompt, speechOnly, planning);
                  } catch (Exception e) {
                    turn.reply.completeExceptionally(e);
                  }
                });
        if (turn.reply.isDone()) turn.cancel();
      } catch (RejectedExecutionException e) {
        turn.reply.completeExceptionally(e);
      }
      return turn.reply;
    }

    private JsonArray definitions() {
      var result = new JsonArray();
      for (var entry : NpcTools.definitions(personality.allowedSkills)) {
        var spec = entry.getAsJsonObject();
        result.add(
            anthropic
                ? object(
                    "name",
                    spec.get("name"),
                    "description",
                    spec.get("description"),
                    "input_schema",
                    spec.get("inputSchema"))
                : object(
                    "type",
                    "function",
                    "function",
                    object(
                        "name",
                        spec.get("name"),
                        "description",
                        spec.get("description"),
                        "parameters",
                        spec.get("inputSchema"))));
      }
      return result;
    }

    private void run(Active turn, String prompt, boolean speechOnly, boolean planning)
        throws Exception {
      var messages = new JsonArray();
      synchronized (this) {
        for (var part : history) messages.addAll(part.deepCopy());
      }
      int initial = messages.size();
      messages.add(
          object(
              "role",
              "user",
              "content",
              (planning
                      ? "Plan only; no speech or tools.\n"
                      : speechOnly ? "発言のみ。ツールは禁止。\n" : "所有者の指示に取り組んでください。\n")
                  + prompt));
      var completedCalls = new HashMap<String, JsonObject>();
      var signatures = new HashMap<String, String>();
      int calls = 0;
      for (; ; ) {
        check(turn);
        if (messages.toString().length() > MAX_CONTEXT)
          throw new IOException("AI context limit reached");
        var instructions =
            CodexBridge.instructions(personality)
                + "\n"
                + "Final response must be ONLY a JSON object"
                + " {\"speech\":\"短い日本語の発言\",\"taskComplete\":false,\"instruction\":\"\"}. Set"
                + " taskComplete true only when real tool results verify the owner's entire task is"
                + " complete. Inventory bag slots are 0..35; main hand 41, offhand 40. Use"
                + " npc_find_chests, npc_deposit, npc_withdraw and npc_give_item for transfers."
                + (speechOnly ? " This turn is speech-only. Never request any tools." : "");
        if (planning)
          instructions =
              TaskPlan.instructions() + "\nPersonality/allowedSkills: " + personality.json();
        var wire = messages.deepCopy();
        JsonObject request;
        if (anthropic)
          request =
              object(
                  "model",
                  advanced ? config.planningModel() : config.model,
                  "system",
                  instructions,
                  "messages",
                  wire,
                  "max_tokens",
                  config.maxOutputTokens,
                  "stream",
                  false);
        else {
          var withSystem = new JsonArray();
          withSystem.add(object("role", "system", "content", instructions));
          withSystem.addAll(wire);
          request =
              object(
                  "model",
                  advanced ? config.planningModel() : config.model,
                  "messages",
                  withSystem,
                  "stream",
                  false);
          request.addProperty(
              "api.openai.com".equals(endpoint.getHost()) ? "max_completion_tokens" : "max_tokens",
              config.maxOutputTokens);
        }
        if (!speechOnly && !personality.allowedSkills.isEmpty())
          request.add("tools", definitions());
        var response = post(turn, request);
        var requested = new JsonArray();
        String text;
        if (anthropic) {
          String reason = response.get("stop_reason").getAsString();
          if (!Set.of("end_turn", "tool_use", "stop_sequence").contains(reason))
            throw new IOException("AI response did not finish");
          var content = response.getAsJsonArray("content");
          var plain = new StringBuilder();
          for (var block : content) {
            var b = block.getAsJsonObject();
            if ("text".equals(b.get("type").getAsString()))
              plain.append(b.get("text").getAsString());
            if ("tool_use".equals(b.get("type").getAsString()))
              requested.add(
                  object("id", b.get("id"), "name", b.get("name"), "arguments", b.get("input")));
          }
          if (reason.equals("tool_use") != !requested.isEmpty())
            throw new IOException("Invalid AI tool response");
          messages.add(object("role", "assistant", "content", content.deepCopy()));
          text = plain.toString();
        } else {
          var choice = response.getAsJsonArray("choices").get(0).getAsJsonObject();
          String reason = choice.get("finish_reason").getAsString();
          if (!Set.of("stop", "tool_calls").contains(reason))
            throw new IOException("AI response did not finish");
          var message = choice.getAsJsonObject("message").deepCopy();
          message.addProperty("role", "assistant");
          if (message.has("tool_calls") && !message.get("tool_calls").isJsonNull())
            for (var call : message.getAsJsonArray("tool_calls")) {
              var c = call.getAsJsonObject();
              if (!"function".equals(c.get("type").getAsString()))
                throw new IOException("Unsupported AI tool type");
              var f = c.getAsJsonObject("function");
              String arguments = f.get("arguments").getAsString();
              if (arguments.length() > 32768) throw new IOException("Tool arguments exceed limit");
              requested.add(
                  object(
                      "id",
                      c.get("id"),
                      "name",
                      f.get("name"),
                      "arguments",
                      JsonParser.parseString(arguments)));
            }
          messages.add(message);
          text =
              message.has("content") && !message.get("content").isJsonNull()
                  ? message.get("content").getAsString()
                  : "";
        }
        if (requested.isEmpty()) {
          var reply =
              planning
                  ? new Reply("", false, "", TaskPlan.parse(unfence(text)))
                  : parseReply(text, speechOnly);
          synchronized (this) {
            check(turn);
            var chunk = new JsonArray();
            for (int i = initial; i < messages.size(); i++) chunk.add(messages.get(i).deepCopy());
            history.addLast(chunk);
            while (history.size() > 4
                || history.stream().mapToInt(p -> p.toString().length()).sum() > 98304)
              history.removeFirst();
            turn.reply.complete(reply);
          }
          return;
        }
        if (speechOnly || calls + requested.size() > config.maxToolCalls)
          throw new IOException("NPC tool-call limit or speech-only restriction");
        calls += requested.size();
        var anthropicResults = new JsonArray();
        for (var entry : requested) {
          check(turn);
          var c = entry.getAsJsonObject();
          String id = c.get("id").getAsString(), name = c.get("name").getAsString();
          if (id.isBlank() || id.length() > 128) throw new IOException("Invalid tool call ID");
          String signature = name + ":" + c.get("arguments");
          JsonObject result = completedCalls.get(id);
          if (result != null && !signature.equals(signatures.get(id)))
            throw new IOException("Tool ID was reused with different arguments");
          if (result == null) {
            try {
              String local = NpcTools.localName(name);
              var args = c.getAsJsonObject("arguments");
              NpcTools.validate(local, args, personality.allowedSkills);
              check(turn);
              result =
                  tools
                      .apply(local, args.deepCopy())
                      .toCompletableFuture()
                      .get(turn.remaining(), TimeUnit.NANOSECONDS);
              check(turn);
              if (result == null || result.toString().length() > 32768)
                result = object("ok", false, "error", "Game result exceeds its bounds");
            } catch (RuntimeException | ExecutionException e) {
              result = object("ok", false, "error", "Skill request rejected or game action failed");
            }
            completedCalls.put(id, result.deepCopy());
            signatures.put(id, signature);
          }
          if (anthropic)
            anthropicResults.add(
                object(
                    "type",
                    "tool_result",
                    "tool_use_id",
                    id,
                    "content",
                    result.toString(),
                    "is_error",
                    result.has("ok") && !result.get("ok").getAsBoolean()));
          else
            messages.add(object("role", "tool", "tool_call_id", id, "content", result.toString()));
        }
        if (anthropic) messages.add(object("role", "user", "content", anthropicResults));
      }
    }

    private JsonObject post(Active turn, JsonObject json) throws Exception {
      check(turn);
      var request =
          HttpRequest.newBuilder(endpoint)
              .timeout(
                  Duration.ofNanos(
                      Math.min(
                          turn.remaining(),
                          TimeUnit.SECONDS.toNanos(config.requestTimeoutSeconds))))
              .header("Content-Type", "application/json")
              .header("Accept", "application/json");
      if (!key.isEmpty())
        request.header(
            anthropic ? "x-api-key" : "Authorization", anthropic ? key : "Bearer " + key);
      if (anthropic) request.header("anthropic-version", "2023-06-01");
      var response =
          http.send(
              request
                  .POST(
                      HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8))
                  .build(),
              HttpResponse.BodyHandlers.ofInputStream());
      turn.body = response.body();
      try (var input = turn.body) {
        check(turn);
        if (response.statusCode() < 200 || response.statusCode() >= 300)
          throw new IOException("AI HTTP " + response.statusCode() + " (check host settings)");
        byte[] bytes = input.readNBytes(MAX_RESPONSE + 1);
        if (bytes.length > MAX_RESPONSE) throw new IOException("AI response exceeds limit");
        check(turn);
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
      } finally {
        turn.body = null;
      }
    }

    public void stop() {
      var t = active.get();
      if (t != null) t.reply.completeExceptionally(new CancellationException("NPC stopped"));
    }

    public void close() {
      disposed = true;
      stop();
      sessions.remove(this);
      synchronized (this) {
        history.clear();
      }
    }
  }

  static Reply parseReply(String text, boolean speechOnly) throws IOException {
    String raw = unfence(text);
    try {
      var value = JsonParser.parseString(raw).getAsJsonObject();
      if (!value.get("speech").isJsonPrimitive()
          || !value.getAsJsonPrimitive("speech").isString()
          || !value.get("taskComplete").isJsonPrimitive()
          || !value.getAsJsonPrimitive("taskComplete").isBoolean())
        throw new IllegalArgumentException();
      String speech = value.get("speech").getAsString();
      if (speech.length() > 512) throw new IllegalArgumentException();
      String instruction = value.has("instruction") ? value.get("instruction").getAsString() : "";
      if (instruction.length() > 2048) throw new IllegalArgumentException();
      return new Reply(
          speech, !speechOnly && value.get("taskComplete").getAsBoolean(), instruction);
    } catch (RuntimeException e) {
      throw new IOException("Invalid NPC JSON reply");
    }
  }

  private static String unfence(String text) {
    String raw = text.strip();
    if (raw.startsWith("```")) {
      int line = raw.indexOf('\n');
      if (line >= 0 && raw.endsWith("```")) raw = raw.substring(line + 1, raw.length() - 3).strip();
    }
    return raw;
  }

  public void close() {
    closed = true;
    sessions.forEach(HttpSession::close);
    sessions.clear();
    workers.shutdownNow();
    http.shutdownNow();
  }
}

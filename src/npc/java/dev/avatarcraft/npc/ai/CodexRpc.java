package dev.avatarcraft.npc.ai;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Bounded newline JSON-RPC transport. Never reads credential files or logs process output. */
public final class CodexRpc implements AutoCloseable {
  static final List<String> DISABLED_FEATURES =
      List.of(
          "apps",
          "shell_tool",
          "multi_agent",
          "multi_agent_v2",
          "skill_search",
          "tool_suggest",
          "plugins",
          "remote_plugin",
          "hooks",
          "browser_use",
          "in_app_browser",
          "skill_mcp_dependency_install",
          "sleep_tool");
  private final Reader input;
  private final Writer output;
  private final Process process;
  private final Duration timeout;
  private final AtomicInteger sequence = new AtomicInteger();
  private final ConcurrentMap<String, CompletableFuture<JsonObject>> pending =
      new ConcurrentHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile Consumer<JsonObject> notifications = ignored -> {};
  private volatile Function<JsonObject, CompletionStage<JsonObject>> requests =
      ignored -> CompletableFuture.failedFuture(new IOException("Unexpected server request"));
  private volatile Consumer<Throwable> failures = ignored -> {};

  public static CodexRpc launch(CodexConfig config, Path workspace) throws IOException {
    config.validate();
    Files.createDirectories(workspace);
    var command =
        new ArrayList<>(
            List.of(
                config.executable,
                "app-server",
                "--listen",
                "stdio://",
                "-c",
                "mcp_servers={}",
                "-c",
                "web_search=\"disabled\"",
                "-c",
                "model_provider=\"openai\""));
    for (String feature : DISABLED_FEATURES) command.addAll(List.of("--disable", feature));
    var builder = new ProcessBuilder(command).directory(workspace.toFile());
    builder.environment().remove("OPENAI_API_KEY");
    builder.environment().remove("AZURE_OPENAI_API_KEY");
    var process = builder.start();
    Thread.ofVirtual()
        .name("avatarcraft-codex-stderr")
        .start(
            () -> {
              try (var stream = process.getErrorStream()) {
                stream.transferTo(OutputStream.nullOutputStream());
              } catch (IOException ignored) {
              }
            });
    return new CodexRpc(
        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8),
        new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8),
        Duration.ofSeconds(config.requestTimeoutSeconds),
        process);
  }

  CodexRpc(Reader input, Writer output, Duration timeout, Process process) {
    this.input = input;
    this.output = output;
    this.timeout = timeout;
    this.process = process;
  }

  public void handlers(
      Consumer<JsonObject> notification,
      Function<JsonObject, CompletionStage<JsonObject>> request,
      Consumer<Throwable> failure) {
    notifications = notification;
    requests = request;
    failures = failure;
  }

  public void start() {
    Thread.ofVirtual().name("avatarcraft-codex-rpc").start(this::readLoop);
  }

  public boolean connected() {
    return !closed.get();
  }

  public CompletableFuture<JsonObject> request(String method, JsonObject params) {
    String id = Integer.toString(sequence.incrementAndGet());
    var message = new JsonObject();
    message.addProperty("id", id);
    message.addProperty("method", method);
    message.add("params", params);
    var future = new CompletableFuture<JsonObject>();
    pending.put(id, future);
    future
        .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
        .whenComplete((value, error) -> pending.remove(id, future));
    try {
      send(message);
    } catch (IOException e) {
      future.completeExceptionally(e);
    }
    return future;
  }

  public void notify(String method, JsonObject params) throws IOException {
    var message = new JsonObject();
    message.addProperty("method", method);
    message.add("params", params);
    send(message);
  }

  private synchronized void send(JsonObject message) throws IOException {
    if (closed.get()) throw new IOException("Codex connection is closed");
    String line = message.toString();
    if (line.length() > 1 << 20) throw new IOException("RPC message exceeds 1 MiB");
    output.write(line);
    output.write('\n');
    output.flush();
  }

  private void readLoop() {
    try {
      var line = new StringBuilder();
      for (int next; !closed.get() && (next = input.read()) >= 0; ) {
        if (next != '\n') {
          if (line.length() >= 1 << 20) throw new IOException("Codex RPC line exceeds 1 MiB");
          line.append((char) next);
          continue;
        }
        if (!line.isEmpty()) receive(JsonParser.parseString(line.toString()).getAsJsonObject());
        line.setLength(0);
      }
      if (!closed.get()) throw new EOFException("Codex app-server exited");
    } catch (Throwable e) {
      if (!closed.get()) {
        try {
          failures.accept(e);
        } finally {
          failPending(e);
          close();
        }
      }
    }
  }

  private void receive(JsonObject message) {
    if (message.has("method")) {
      if (!message.has("id")) {
        notifications.accept(message);
        return;
      }
      CompletionStage<JsonObject> result;
      try {
        result = requests.apply(message);
      } catch (Exception e) {
        result = CompletableFuture.failedFuture(e);
      }
      result.whenComplete(
          (value, error) -> {
            var response = new JsonObject();
            response.add("id", message.get("id"));
            if (error == null) response.add("result", value);
            else {
              var failure = new JsonObject();
              failure.addProperty("code", -32601);
              failure.addProperty("message", "NPC client refused this request");
              response.add("error", failure);
            }
            try {
              send(response);
            } catch (IOException ignored) {
            }
          });
    } else if (message.has("id")) {
      var future = pending.remove(message.get("id").getAsString());
      if (future == null) return;
      if (message.has("error"))
        future.completeExceptionally(
            new IOException(
                "Codex RPC request failed (code "
                    + message.getAsJsonObject("error").get("code")
                    + ")"));
      else
        future.complete(
            message.has("result") && message.get("result").isJsonObject()
                ? message.getAsJsonObject("result")
                : new JsonObject());
    }
  }

  private void failPending(Throwable error) {
    pending.values().forEach(f -> f.completeExceptionally(error));
    pending.clear();
  }

  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    if (process != null) process.destroy();
    failPending(new IOException("Codex connection closed"));
    try {
      output.close();
    } catch (IOException ignored) {
    }
    // Destroying the process unblocks its pipe reader before closing the Reader lock.
    try {
      input.close();
    } catch (IOException ignored) {
    }
  }
}

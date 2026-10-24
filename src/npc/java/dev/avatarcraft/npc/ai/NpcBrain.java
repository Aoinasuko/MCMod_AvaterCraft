package dev.avatarcraft.npc.ai;

import com.google.gson.JsonObject;
import dev.avatarcraft.npc.Personality;
import java.util.concurrent.*;
import java.util.function.BiFunction;

/** Provider-independent bounded game-tool sessions. */
public interface NpcBrain extends AutoCloseable {
  record Reply(String speech, boolean taskComplete, String instruction, TaskPlan plan) {
    public Reply(String speech, boolean taskComplete) {
      this(speech, taskComplete, "", null);
    }

    public Reply(String speech, boolean taskComplete, String instruction) {
      this(speech, taskComplete, instruction, null);
    }
  }

  CompletableFuture<Void> initialize();

  CompletableFuture<? extends Session> open(
      Personality personality, BiFunction<String, JsonObject, CompletionStage<JsonObject>> tools);

  default CompletableFuture<? extends Session> openAdvanced(
      Personality personality, BiFunction<String, JsonObject, CompletionStage<JsonObject>> tools) {
    return open(personality, tools);
  }

  boolean connected();

  String status();

  void close();

  interface Session extends AutoCloseable {
    boolean busy();

    boolean connected();

    boolean canStart(boolean speechOnly);

    CompletableFuture<Reply> turn(String prompt, boolean speechOnly);

    CompletableFuture<TaskPlan> plan(String prompt);

    void stop();

    void close();
  }
}

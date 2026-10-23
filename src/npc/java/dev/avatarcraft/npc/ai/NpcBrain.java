package dev.avatarcraft.npc.ai;

import com.google.gson.JsonObject;
import dev.avatarcraft.npc.Personality;
import java.util.concurrent.*;
import java.util.function.BiFunction;

/** Provider-independent bounded game-tool sessions. */
public interface NpcBrain extends AutoCloseable {
  record Reply(String speech, boolean taskComplete, String instruction) {
    public Reply(String speech, boolean taskComplete) {
      this(speech, taskComplete, "");
    }
  }

  CompletableFuture<Void> initialize();

  CompletableFuture<? extends Session> open(
      Personality personality, BiFunction<String, JsonObject, CompletionStage<JsonObject>> tools);

  boolean connected();

  String status();

  void close();

  interface Session extends AutoCloseable {
    boolean busy();

    boolean connected();

    boolean canStart(boolean speechOnly);

    CompletableFuture<Reply> turn(String prompt, boolean speechOnly);

    void stop();

    void close();
  }
}

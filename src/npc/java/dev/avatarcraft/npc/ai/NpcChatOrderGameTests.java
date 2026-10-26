package dev.avatarcraft.npc.ai;

import dev.avatarcraft.npc.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** An owner's order heard by the NPC must not vanish while the model is answering. */
final class NpcChatOrderGameTests {
  static final String ORDER = "原木を集めて";

  static void register(RegisterGameTestsEvent event) {
    var env =
        event.registerEnvironment(
            bep_3davater_companion.id("chat_order_qa"), new TestEnvironmentDefinition.AllOf());
    test(event, env, "chat_order_survives_owner_walking_away", h -> order(h, false));
    test(event, env, "chat_order_survives_a_later_small_talk", h -> order(h, true));
  }

  private static void test(
      RegisterGameTestsEvent event,
      Holder<TestEnvironmentDefinition<?>> env,
      String name,
      Consumer<GameTestHelper> body) {
    event.registerTest(
        bep_3davater_companion.id(name),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(env, bep_3davater_companion.id("qa_navigation"), 1200, 1, true)) {
          public void run(GameTestHelper h) {
            body.accept(h);
          }
        });
  }

  /** Replies are released by the test, so it controls what happens while they are pending. */
  private static final class Brain implements NpcBrain {
    final List<String> prompts = new ArrayList<>();
    final List<CompletableFuture<Reply>> replies = new ArrayList<>();

    public CompletableFuture<Void> initialize() {
      return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<? extends Session> open(
        Personality personality,
        java.util.function.BiFunction<
                String, com.google.gson.JsonObject, CompletionStage<com.google.gson.JsonObject>>
            tools) {
      return CompletableFuture.completedFuture(
          new Session() {
            public boolean busy() {
              return replies.stream().anyMatch(r -> !r.isDone());
            }

            public boolean connected() {
              return true;
            }

            public boolean canStart(boolean speechOnly) {
              return !busy();
            }

            public CompletableFuture<Reply> turn(String prompt, boolean speechOnly) {
              prompts.add(prompt);
              var reply = new CompletableFuture<Reply>();
              replies.add(reply);
              return reply;
            }

            public CompletableFuture<TaskPlan> plan(String prompt) {
              return new CompletableFuture<>();
            }

            public void stop() {}

            public void close() {}
          });
    }

    public boolean connected() {
      return true;
    }

    public String status() {
      return "fixture";
    }

    public void close() {}
  }

  private static void order(GameTestHelper h, boolean smallTalk) {
    for (int x = 0; x < 40; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.STONE);
    ServerPlayer owner = h.makeMockServerPlayerInLevel();
    owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(4, 1, 4))));
    var n = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    n.initialize(owner, "tortobu");
    n.setFollowsOwner(false);
    n.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(6, 1, 4))));
    h.getLevel().addFreshEntity(n);
    var service = NpcService.get(h.getLevel().getServer());
    service.ownerJoined(owner.getUUID());
    var brain = new Brain();
    var mind = new NpcMind(n, service, brain);
    n.gameTestMind(mind);
    mind.hearPlayer(owner, ORDER);
    boolean[] talked = {false};
    h.onEachTick(
        () -> {
          if (brain.replies.size() == 1 && !brain.replies.get(0).isDone()) {
            if (smallTalk && !talked[0]) {
              // The owner adds an impatient remark while the first answer is still pending.
              talked[0] = true;
              mind.hearPlayer(owner, "急いで");
            } else {
              // The owner wanders off before the model answers.
              owner.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(36, 1, 10))));
              brain.replies.get(0).complete(new NpcBrain.Reply("取り組むね", false, ORDER));
            }
          }
          if (smallTalk && brain.replies.size() == 2 && !brain.replies.get(1).isDone())
            brain.replies.get(1).complete(new NpcBrain.Reply("急ぐね", false, ""));
          if (!n.instruction().equals(ORDER)) return;
          n.discard();
          h.succeed();
        });
  }
}

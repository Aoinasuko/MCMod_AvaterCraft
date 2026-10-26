package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;
import dev.avatarcraft.npc.*;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.EntitySpawnReason;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

final class NpcJournalGameTests {
  static void register(RegisterGameTestsEvent event) {
    var env = event.registerEnvironment(bep_3davater_companion.id("journal_qa"), new TestEnvironmentDefinition.AllOf());
    for (boolean offline : new boolean[] {true, false})
      event.registerTest(bep_3davater_companion.id(offline ? "offline_owner_never_opens_speech" : "death_speech_retains_death_location"),
          new FunctionGameTestInstance(BuiltinTestFunctions.ALWAYS_PASS,
              new TestData<>(env, bep_3davater_companion.id("qa_empty"), 200, 1, true)) {
            public void run(GameTestHelper h) { scenario(h, offline); }
          });
  }
  private static void scenario(GameTestHelper h, boolean offline) {
    var owner = h.makeMockServerPlayerInLevel();
    var npc = bep_3davater_companion.COMPANION.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
    npc.initialize(owner, "tortobu"); npc.setFollowsOwner(false);
    var position = h.absolutePos(new BlockPos(3, 1, 3));
    npc.setPos(position.getX() + .5, position.getY(), position.getZ() + .5);
    h.getLevel().addFreshEntity(npc);
    var service = NpcService.get(h.getLevel().getServer());
    int[] opens = {0}, turns = {0};
    NpcBrain brain = new NpcBrain() {
      public CompletableFuture<Void> initialize() { return CompletableFuture.completedFuture(null); }
      public CompletableFuture<? extends Session> open(dev.avatarcraft.npc.Personality p,
          java.util.function.BiFunction<String, com.google.gson.JsonObject, CompletionStage<com.google.gson.JsonObject>> tools) {
        opens[0]++; h.assertTrue(p.speakingStyle.contains("ぼく") && p.allowedSkills.isEmpty(), "Character speech has personality but no tools");
        return CompletableFuture.completedFuture(new Session() {
          public boolean busy() { return false; } public boolean connected() { return true; }
          public boolean canStart(boolean voice) { return true; }
          public CompletableFuture<Reply> turn(String prompt, boolean voice) {
            turns[0]++; h.assertTrue(voice && prompt.contains("VERIFIED_NPC_DEATH") && prompt.contains(Integer.toString(position.getX())), "AI receives captured death position");
            return CompletableFuture.completedFuture(new Reply("ぼく、そこで倒れちゃった。戻って待ってるね。", false));
          }
          public CompletableFuture<TaskPlan> plan(String prompt) { throw new AssertionError("No planning"); }
          public void stop() {} public void close() {}
        });
      }
      public boolean connected() { return true; } public String status() { return "fixture"; } public void close() {}
    };
    if (offline) {
      service.ownerLeft(owner.getUUID());
      var mind = new NpcMind(npc, service, brain); npc.gameTestMind(mind);
      mind.hearPlayer(owner, "こんにちは");
      for (int i = 0; i < 100; i++) mind.tick();
      h.assertTrue(mind.pendingPlayerChats() == 0, "Offline owner's NPC does not queue chat");
      service.say(npc, "Must not be emitted", -1);
    }
    service.reportDeath(npc, "fixture", CompletableFuture.completedFuture(brain));
    npc.setPos(position.getX() + 2.5, position.getY(), position.getZ() + .5);
    var read = new java.util.concurrent.atomic.AtomicReference<java.util.List<dev.avatarcraft.npc.memory.NpcJournal.Entry>>();
    var readError = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    h.runAfterDelay(10, () -> service.readJournal(() -> service.journal.recent(owner.getUUID(), npc.getUUID(), true))
        .whenComplete((entries, error) -> { readError.set(error); read.set(entries); }));
    h.succeedWhen(() -> {
          h.assertTrue(readError.get() == null, "Journal read succeeds");
          h.assertTrue(read.get() != null, "Journal read is pending");
          var entries = read.get();
          var death = entries.stream().filter(e -> e.kind().equals("death")).findFirst().orElseThrow();
          h.assertTrue(death.details().get("x").getAsInt() == position.getX(), "Death record preserves original coordinates");
          if (offline) {
            h.assertTrue(opens[0] == 0 && turns[0] == 0, "Absent owner causes zero AI sessions/turns");
            h.assertTrue(entries.stream().noneMatch(e -> e.kind().equals("death_notice") || e.kind().equals("speech")), "Absent owner causes no speech");
          } else {
            h.assertTrue(opens[0] == 1 && turns[0] == 1, "Exactly one character speech request");
            var notice = entries.stream().filter(e -> e.kind().equals("death_notice")).findFirst().orElseThrow();
            h.assertTrue(notice.text().contains("ぼく") && notice.text().contains("X:" + position.getX())
                && notice.details().get("x").getAsInt() != npc.blockPosition().getX(), "Owner notice preserves original death facts after NPC moves");
          }
          service.ownerJoined(owner.getUUID()); npc.discard();
    });
  }
}

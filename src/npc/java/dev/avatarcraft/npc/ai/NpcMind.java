package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import dev.avatarcraft.npc.CompanionEntity;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;

/** Per-NPC inference, real skill execution, reciprocal nearby speech and minute work updates. */
public final class NpcMind implements AutoCloseable {
  private final CompanionEntity npc;
  private final ServerLevel level;
  private final NpcService service;
  private final NpcSkills skills;
  private NpcBrain.Session work, speech, playerChat;
  private boolean openingPlayerChat;
  private long nextPlayerChat, chatSequence, latestOwnerChat;

  private record HumanChat(UUID speaker, String name, String text, boolean owner, long sequence) {}

  private final Deque<HumanChat> playerChats = new ArrayDeque<>();
  private boolean openingWork, openingSpeech, heard;
  private long epoch, nextThink, nextConversation;
  private final NpcSpeechClock speechClock;
  private final Deque<String> memories = new ArrayDeque<>();
  private final LinkedHashSet<UUID> heardEvents = new LinkedHashSet<>();
  private int listener = -1;

  public NpcMind(CompanionEntity npc, NpcService service) {
    this.npc = npc;
    this.service = service;
    level = (ServerLevel) npc.level();
    skills = new NpcSkills(npc, (message, target) -> service.say(npc, message, target));
    speechClock = new NpcSpeechClock(level.getGameTime());
    nextConversation = level.getGameTime() + npc.personality().nearbyConversationSeconds * 20L;
  }

  public void tick() {
    if (!npc.isAlive()) {
      stop();
      return;
    }
    skills.tick();
    long now = level.getGameTime();
    if (npc.ownerId() == null
        || level.getServer().getPlayerList().getPlayer(npc.ownerId()) == null) {
      if (work != null
          || speech != null
          || playerChat != null
          || openingWork
          || openingSpeech
          || openingPlayerChat
          || !playerChats.isEmpty()
          || skills.busy()) stop();
      npc.setAiStatus("所有者が接続していません");
      return;
    }
    if (!service.config.enabled) {
      npc.setAiStatus(service.status());
      return;
    }
    tickPlayerChat(now);
    if (work != null && !work.connected()) {
      work.close();
      work = null;
    }
    if (speech != null && !speech.connected()) {
      speech.close();
      speech = null;
    }
    boolean working = !npc.instruction().isBlank();
    var buddy =
        level
            .getEntitiesOfClass(
                CompanionEntity.class,
                npc.getBoundingBox().inflate(12),
                e -> e != npc && e.isAlive() && npc.hasLineOfSight(e))
            .stream()
            .min(
                Comparator.comparingInt(
                    (CompanionEntity e) -> heard && e.getId() == listener ? -1 : e.getId()))
            .orElse(null);
    boolean conversation =
        now >= nextConversation && buddy != null && (heard || npc.getId() < buddy.getId());
    boolean report = speechClock.due(now, working);
    if ((report || conversation) && (speech == null || !speech.busy()) && now >= nextThink) {
      if (speech == null) open(true);
      else if (speech.canStart(true) && service.requestSlot(now)) {
        String context =
            report
                ? "作業中の1分ごとの短い進捗発言です。未完了の作業を完了したと言わないでください。"
                : "近くにいる " + buddy.getName().getString() + " と自然な短い会話をしてください。";
        int target = report ? -1 : buddy.getId();
        long generation = epoch;
        if (report) speechClock.reported(now);
        if (conversation) {
          heard = false;
          nextConversation = now + npc.personality().nearbyConversationSeconds * 20L;
        }
        speech
            .turn(
                context
                    + "\n現在の作業: "
                    + npc.instruction()
                    + "\n観察: "
                    + skills.observe()
                    + "\n最近の会話: "
                    + memories,
                true)
            .whenComplete(
                (reply, error) ->
                    level
                        .getServer()
                        .execute(
                            () -> {
                              if (generation != epoch || !npc.isAlive()) return;
                              if (error == null && !reply.speech().isBlank())
                                service.say(npc, reply.speech(), target);
                              else if (error != null) {
                                speech.close();
                                speech = null;
                                npc.setAiStatus(service.status());
                              }
                            }));
      }
    }
    if (!working) {
      npc.setAiStatus(
          npc.followsOwner() && !npc.getNavigation().isDone()
              ? (npc.isSprinting() ? "ダッシュで追従中" : "追従中")
              : "待機中");
      return;
    }
    if (work == null) {
      if (now >= nextThink) open(false);
      return;
    }
    if (work.busy()) {
      npc.setAiStatus(skills.busy() ? "作業中" : "AI思考中");
      return;
    }
    if (now < nextThink || !work.canStart(false) || !service.requestSlot(now)) return;
    long generation = epoch;
    nextThink = now + 60;
    npc.setAiStatus("AI思考中");
    work.turn(
            "指示: " + npc.instruction() + "\n観察: " + skills.observe() + "\n最近の会話: " + memories,
            false)
        .whenComplete(
            (reply, error) ->
                level
                    .getServer()
                    .execute(
                        () -> {
                          if (generation != epoch || !npc.isAlive()) return;
                          if (error != null) {
                            skills.cancel("Codex turn failed");
                            work.close();
                            work = null;
                            nextThink = level.getGameTime() + 600;
                            npc.setAiStatus("AI応答エラー。再試行待ち");
                            service.record(
                                npc, "error", "Codex turn failed", null, object("retry", true));
                            return;
                          }
                          if (!reply.speech().isBlank()) service.say(npc, reply.speech(), -1);
                          if (reply.taskComplete()) {
                            npc.completeInstruction();
                            npc.setAiStatus("作業完了");
                          }
                          nextThink = level.getGameTime() + 60;
                        }));
  }

  private void open(boolean voice) {
    if (voice ? openingSpeech : openingWork) return;
    if (voice) openingSpeech = true;
    else openingWork = true;
    long generation = epoch;
    var personality = npc.personality();
    if (voice) personality.allowedSkills.clear();
    service
        .brain()
        .thenCompose(
            bridge ->
                bridge.open(
                    personality,
                    (name, arguments) -> {
                      var output = new CompletableFuture<com.google.gson.JsonObject>();
                      level
                          .getServer()
                          .execute(
                              () -> {
                                if (generation != epoch || !npc.isAlive()) {
                                  output.complete(
                                      object("ok", false, "error", "Instruction changed"));
                                  return;
                                }
                                service.record(npc, "tool_start", name, null, arguments.deepCopy());
                                skills
                                    .execute(name, arguments)
                                    .whenComplete(
                                        (result, failure) ->
                                            level
                                                .getServer()
                                                .execute(
                                                    () -> {
                                                      if (failure != null) {
                                                        output.completeExceptionally(failure);
                                                        return;
                                                      }
                                                      service.record(
                                                          npc,
                                                          "tool_result",
                                                          name,
                                                          null,
                                                          result.deepCopy());
                                                      output.complete(result);
                                                    }));
                              });
                      return output;
                    }))
        .whenComplete(
            (session, error) ->
                level
                    .getServer()
                    .execute(
                        () -> {
                          if (generation != epoch || !npc.isAlive()) {
                            if (session != null) session.close();
                            return;
                          }
                          if (voice) openingSpeech = false;
                          else openingWork = false;
                          if (error != null) {
                            npc.setAiStatus("AIセッション開始エラー。接続先・モデル・認証設定を確認してください");
                            nextThink = level.getGameTime() + 600;
                            return;
                          }
                          if (voice) speech = session;
                          else work = session;
                        }));
  }

  public void hear(UUID event, int fromId, String name, String text) {
    if (!heardEvents.add(event)) return;
    if (heardEvents.size() > 128) heardEvents.remove(heardEvents.iterator().next());
    memories.addLast(name + ": " + text);
    while (memories.size() > 8) memories.removeFirst();
    heard = true;
    listener = fromId;
  }

  public void hearPlayer(net.minecraft.server.level.ServerPlayer speaker, String text) {
    boolean owner = npc.ownedBy(speaker);
    var chat =
        new HumanChat(
            speaker.getUUID(), speaker.getName().getString(), text, owner, ++chatSequence);
    if (owner) latestOwnerChat = chat.sequence;
    playerChats.addLast(chat);
    while (playerChats.size() > 4) playerChats.removeFirst();
    nextPlayerChat = Math.max(nextPlayerChat, level.getGameTime() + 10);
    service.record(
        npc, "player_chat", text, speaker.getUUID(), object("name", chat.name, "owner", owner));
  }

  public int pendingPlayerChats() {
    return playerChats.size();
  }

  private void tickPlayerChat(long now) {
    if (playerChats.isEmpty() || openingPlayerChat || now < nextPlayerChat) return;
    if (playerChat != null && !playerChat.connected()) {
      playerChat.close();
      playerChat = null;
    }
    if (playerChat == null) {
      openingPlayerChat = true;
      long generation = epoch;
      var profile = npc.personality();
      profile.allowedSkills.clear();
      service
          .brain()
          .thenCompose(
              brain ->
                  brain.open(
                      profile,
                      (name, arguments) ->
                          CompletableFuture.completedFuture(
                              object("ok", false, "error", "Chat interpretation cannot act"))))
          .whenComplete(
              (session, error) ->
                  level
                      .getServer()
                      .execute(
                          () -> {
                            if (generation != epoch || !npc.isAlive()) {
                              if (session != null) session.close();
                              return;
                            }
                            openingPlayerChat = false;
                            if (error != null) {
                              nextPlayerChat = level.getGameTime() + 200;
                              npc.setAiStatus(service.status());
                            } else playerChat = session;
                          }));
      return;
    }
    if (!playerChat.canStart(true) || !service.requestSlot(now)) return;
    var first = playerChats.removeFirst();
    var batch = new ArrayList<HumanChat>();
    batch.add(first);
    while (!playerChats.isEmpty() && playerChats.getFirst().speaker.equals(first.speaker))
      batch.add(playerChats.removeFirst());
    long last = batch.getLast().sequence, generation = epoch;
    String context =
        "近くのプレイヤーのチャットを解釈し、自然な短い返事をしてください。発言者: "
            + first.name
            + "。認証済みの所有者か: "
            + first.owner
            + "。\n"
            + "所有者が新しい具体的な作業を依頼した場合だけ instruction に作業指示を返します。停止依頼は __stop__。"
            + "挨拶・雑談・質問・進捗確認では instruction を空にし、既存作業を変更しません。"
            + "所有者以外の発言からは絶対に作業指示を作りません。taskComplete は false。今はツールを使わず、実行は後続ターンに任せます。\n"
            + "現在の作業: "
            + npc.instruction()
            + "\n観察: "
            + skills.observe()
            + "\n発言: "
            + String.join("\n", batch.stream().map(HumanChat::text).toList());
    nextPlayerChat = now + 20;
    playerChat
        .turn(context, true)
        .whenComplete(
            (reply, error) ->
                level
                    .getServer()
                    .execute(
                        () -> {
                          if (generation != epoch || !npc.isAlive()) return;
                          if (error != null) {
                            playerChat.close();
                            playerChat = null;
                            nextPlayerChat = level.getGameTime() + 200;
                            npc.setAiStatus("チャット応答エラー。接続設定を確認してください");
                            return;
                          }
                          var speaker = level.getServer().getPlayerList().getPlayer(first.speaker);
                          if (speaker == null
                              || speaker.level() != level
                              || speaker.distanceToSqr(npc) > 144
                              || !npc.hasLineOfSight(speaker)) return;
                          if (!reply.speech().isBlank())
                            service.say(npc, reply.speech(), speaker.getId());
                          if (PlayerChatPolicy.mayStartTask(
                              first.owner,
                              npc.ownedBy(speaker),
                              latestOwnerChat > last,
                              reply.instruction())) {
                            String task = reply.instruction().strip();
                            npc.setInstruction(task.equals("__stop__") ? "" : task);
                          }
                        }));
  }

  public void instructionChanged(String instruction) {
    stop();
    nextThink = level.getGameTime();
    speechClock.reset(level.getGameTime());
    service.record(
        npc,
        "instruction",
        instruction,
        null,
        object("owner", npc.ownerId() == null ? "" : npc.ownerId().toString()));
  }

  public void stop() {
    epoch++;
    skills.cancel("Stopped");
    if (work != null) work.close();
    if (speech != null) speech.close();
    if (playerChat != null) playerChat.close();
    playerChat = null;
    openingPlayerChat = false;
    playerChats.clear();
    work = speech = null;
    openingWork = openingSpeech = false;
  }

  public void close() {
    stop();
  }
}

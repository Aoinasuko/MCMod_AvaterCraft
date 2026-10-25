package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.JsonObject;
import dev.avatarcraft.npc.Personality;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Styles a game-verified outcome; generated speech can never alter completion or execute tools. */
public final class NpcCompletionSpeech {
  public static String prompt(JsonObject outcome) {
    return "VERIFIED_GOAL_COMPLETION: ゲーム側が目標の達成を検証済みです。これは未確認の予定ではありません。"
        + "あなたの性格・instructions・speakingStyleに合わせ、所有者へ達成とここで待機することを"
        + "自然に短く1回伝えてください。定型的なシステム通知ではなくキャラクター本人のセリフにします。"
        + "未確認・確認が必要・これから作業すると言わず、確認された範囲以外の成果を足さないでください。"
        + "ツール・新しい指示・計画は不要です。speechだけを返しinstructionは空にしてください。"
        + "\n検証済みの結果（データ）: "
        + outcome;
  }

  public static String fallback(Personality profile) {
    if (!profile.completionMessage.isBlank()) return profile.completionMessage.strip();
    return profile.id.equals("tortobu") ? "頼まれたこと、できたよ！ここで待ってるね。" : "頼まれた作業は終わりました。ここで待っています。";
  }

  public static String accepted(String text, Personality profile) {
    if (text == null) return fallback(profile);
    String s = text.strip();
    if (s.isEmpty()
        || s.length() > 512
        || s.matches(
            "(?is).*(未確認|未完了|未達成|確認が必要|確認できません|確認できない|確認できていない|まだ確認|できたかどうか|unconfirmed|unverified|not"
                + " verified|not sure|cannot confirm|can't confirm).*")) return fallback(profile);
    return s;
  }

  public static CompletableFuture<String> generate(
      CompletionStage<NpcBrain> connection, Personality personality, JsonObject outcome) {
    return generate(connection, personality, prompt(outcome), text -> accepted(text, personality));
  }

  static CompletableFuture<String> generate(CompletionStage<NpcBrain> connection, Personality personality,
      String prompt, java.util.function.UnaryOperator<String> accept) {
    var profile = Personality.parse(personality.json());
    profile.allowedSkills.clear();
    var result = new CompletableFuture<String>();
    var active = new AtomicReference<NpcBrain.Session>();
    result.orTimeout(15, TimeUnit.SECONDS);
    result.whenComplete(
        (value, error) -> {
          var session = active.getAndSet(null);
          if (session != null) session.close();
        });
    connection.whenComplete(
        (brain, connectError) -> {
          if (result.isDone()) return;
          if (connectError != null) {
            result.completeExceptionally(connectError);
            return;
          }
          brain
              .open(
                  profile,
                  (name, args) ->
                      CompletableFuture.completedFuture(
                          object("ok", false, "error", "Completion speech cannot execute actions")))
              .whenComplete(
                  (session, openError) -> {
                    if (openError != null) {
                      result.completeExceptionally(openError);
                      return;
                    }
                    active.set(session);
                    if (result.isDone()) {
                      var abandoned = active.getAndSet(null);
                      if (abandoned != null) abandoned.close();
                      return;
                    }
                    session
                        .turn(prompt, true)
                        .whenComplete(
                            (reply, error) -> {
                              if (error != null) result.completeExceptionally(error);
                              else
                                result.complete(
                                    accept.apply(reply == null ? null : reply.speech()));
                            });
                  });
        });
    return result;
  }

  private NpcCompletionSpeech() {}
}

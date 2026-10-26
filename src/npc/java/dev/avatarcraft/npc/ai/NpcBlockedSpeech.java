package dev.avatarcraft.npc.ai;

import com.google.gson.JsonObject;
import dev.avatarcraft.npc.Personality;
import java.util.concurrent.*;

/** Character speech describes a verified pause; it cannot change the job or run tools. */
public final class NpcBlockedSpeech {
  public static String prompt(JsonObject outcome) {
    return "VERIFIED_WORK_PAUSE: ゲーム側が作業を安全に続けられないことを確認し、いったん停止しました。"
        + "あなたの性格・instructions・speakingStyleと一人称に合わせて、所有者に短く1回伝えてください。"
        + "『作業を保留しています』というシステム定型文を使わず、本人の自然なセリフにします。"
        + "分かっている障害とここで待つことを伝え、原因が不明なら断定せず、成功・完了したと言わないでください。"
        + "自動で続行する約束や、未確認の道具・危険の創作はしないでください。"
        + "ツール・計画・新しい命令は不要。speechだけを返しinstructionは空にします。\n確認済みの状態（データ）: "
        + outcome;
  }

  public static String fallback(Personality p) {
    if (!p.blockedMessage.isBlank()) return p.blockedMessage.strip();
    return p.id.equals("tortobu") ? "ぼく、今は先に進めないみたい。いったんここで待ってるね。"
        : "今は作業を続けられそうにありません。いったんここで待ちます。";
  }

  public static String accepted(String text, Personality p) {
    if (text == null || text.isBlank() || text.length() > 512
        || text.matches("(?is).*(目標を達成|作業は完了|作業が完了|mission complete|goal achieved).*")) return fallback(p);
    return text.strip();
  }

  public static CompletableFuture<String> generate(CompletionStage<NpcBrain> connection,
      Personality p, JsonObject outcome) {
    return NpcCompletionSpeech.generate(connection, p, prompt(outcome), s -> accepted(s, p));
  }

  private NpcBlockedSpeech() {}
}

package dev.avatarcraft.npc.ai;

import com.google.gson.JsonObject;
import dev.avatarcraft.npc.Personality;
import java.util.concurrent.*;

/** Captured death facts survive respawn/dimension changes. A deterministic suffix preserves coordinates. */
public final class NpcDeathSpeech {
  public static String location(JsonObject facts) {
    return "[" + facts.get("dimension").getAsString() + "] X:" + facts.get("x").getAsInt()
        + " Y:" + facts.get("y").getAsInt() + " Z:" + facts.get("z").getAsInt();
  }
  public static String prompt(JsonObject facts) {
    return "VERIFIED_NPC_DEATH: ゲームであなたが倒れたことを確認しました。下記は死亡時点の確定データです。"
        + "現在位置や復帰先と混同せず、どの次元・座標で倒れたかを、性格・instructions・speakingStyleに沿った"
        + "本人の短いセリフで所有者に1回伝えてください。作業は中断され、所有者のリスポーン地点へ戻り待機します。"
        + "存在しない敵・回収済みの所持品・達成していない成果を足さないでください。"
        + "ツール・計画・新しい指示は不要です。speechだけを返しinstructionは空にします。\n死亡時の事実: " + facts;
  }
  public static String accepted(String speech, Personality profile, JsonObject facts) {
    String text = speech == null ? "" : speech.replaceAll("[\\p{Cntrl}§]", " ").strip();
    if (text.isBlank()) text = profile.id.equals("tortobu")
        ? "ぼく、ここで倒れちゃった。きみのリスポーン地点に戻って、待ってるね。"
        : "ここで倒れました。作業を中断して、あなたのリスポーン地点へ戻り待機します。";
    String location = location(facts);
    int limit = Math.max(1, 510 - location.length());
    if (text.length() > limit) text = text.substring(0, limit);
    return text + "\n" + location;
  }
  public static CompletableFuture<String> generate(CompletionStage<NpcBrain> connection, Personality profile, JsonObject facts) {
    var captured = facts.deepCopy();
    var personality = Personality.parse(profile.json());
    return NpcCompletionSpeech.generate(connection, personality, prompt(captured), text -> accepted(text, personality, captured));
  }
  private NpcDeathSpeech() {}
}

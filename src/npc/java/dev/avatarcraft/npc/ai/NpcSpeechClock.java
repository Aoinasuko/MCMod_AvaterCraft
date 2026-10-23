package dev.avatarcraft.npc.ai;

/** Conversation must never postpone the independent one-minute work report. */
public final class NpcSpeechClock {
  private long reportAt;

  public NpcSpeechClock(long now) {
    reset(now);
  }

  public void reset(long now) {
    reportAt = now + 1200;
  }

  public boolean due(long now, boolean working) {
    return working && now >= reportAt;
  }

  public void reported(long now) {
    reportAt += (Math.max(0, now - reportAt) / 1200 + 1) * 1200;
  }
}

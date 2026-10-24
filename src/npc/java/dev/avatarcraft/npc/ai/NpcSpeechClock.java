package dev.avatarcraft.npc.ai;

/** Independent game-time speech schedule; network/planning delays never stop local actions. */
public final class NpcSpeechClock {
  private long reportAt;
  private int interval = 60;

  public NpcSpeechClock(long now) {
    reset(now);
  }

  public NpcSpeechClock(long now, int seconds) {
    reset(now, seconds);
  }

  public void reset(long now, int seconds) {
    if (seconds < 30 || seconds > 60)
      throw new IllegalArgumentException("Speech interval must be 30..60 seconds");
    interval = seconds;
    reset(now);
  }

  public void reset(long now) {
    reportAt = now + interval * 20L;
  }

  public boolean due(long now, boolean working) {
    return working && now >= reportAt;
  }

  public void reported(long now) {
    long ticks = interval * 20L;
    reportAt += (Math.max(0, now - reportAt) / ticks + 1) * ticks;
  }
}

package dev.avatarcraft.npc.ai;

import java.util.*;

/** A bounded local watchdog: jitter and revisiting the same cells cannot renew a movement. */
public final class NpcMoveProgress {
  /** Give up after 10 s; the short rule below has already tried jumping and re-searching. */
  public static final long STALL_TICKS = 200;
  /** Idle ticks before a short recovery: jump first, then search again from here. */
  public static final long NUDGE_TICKS = 40;
  /** Work routes wait out long edit searches in place, so they keep the longer limit. */
  public static final long WORK_STALL_TICKS = 400;
  private final Set<String> visited = new HashSet<>();
  private final Map<String, Double> approaches = new HashMap<>();
  private long lastProgress;
  private final long limit;

  public NpcMoveProgress(long now) {
    this(now, STALL_TICKS);
  }

  public NpcMoveProgress(long now, long limit) {
    lastProgress = now;
    this.limit = limit;
  }

  public void pauseForSearch(long now) {
    lastProgress = now;
  }

  public void edited(long now) {
    lastProgress = now;
  }

  public boolean stalled(long now, double x, double y, double z, int tx, int ty, int tz) {
    String cell = (int) Math.floor(x) + "," + (int) Math.floor(y) + "," + (int) Math.floor(z);
    if (visited.size() < 4096 && visited.add(cell)) lastProgress = now;
    String edge = tx + "," + ty + "," + tz;
    double distance =
        Math.sqrt(Math.pow(x - tx - .5, 2) + Math.pow(y - ty, 2) + Math.pow(z - tz - .5, 2));
    double previous = approaches.getOrDefault(edge, Double.POSITIVE_INFINITY);
    if (distance < previous - .15 && (approaches.size() < 4096 || approaches.containsKey(edge))) {
      if (approaches.size() < 4096 || approaches.containsKey(edge)) approaches.put(edge, distance);
      lastProgress = now;
    }
    return now - lastProgress > limit;
  }

  public long idleTicks(long now) {
    return Math.max(0, now - lastProgress);
  }

  public int visitedCells() {
    return visited.size();
  }
}

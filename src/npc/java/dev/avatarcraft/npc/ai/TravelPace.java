package dev.avatarcraft.npc.ai;

/** Distance hysteresis for work/expedition paths, independent of following's arrival thresholds. */
public final class TravelPace {
  private boolean sprinting;

  public void update(double distanceSquared, boolean allowed, boolean precise) {
    // A player sprints anything longer than a few blocks.
    sprinting = allowed && !precise && distanceSquared >= (sprinting ? 3 * 3 : 5 * 5);
  }

  public boolean sprinting() {
    return sprinting;
  }

  public double speed() {
    return sprinting ? 2.2 : 1.6;
  }

  public void reset() {
    sprinting = false;
  }
}

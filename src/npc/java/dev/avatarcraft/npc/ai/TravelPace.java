package dev.avatarcraft.npc.ai;

/** Distance hysteresis for work/expedition paths, independent of following's arrival thresholds. */
public final class TravelPace {
  private boolean sprinting;

  public void update(double distanceSquared, boolean allowed, boolean precise) {
    sprinting = allowed && !precise && distanceSquared >= (sprinting ? 4.5 * 4.5 : 8 * 8);
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

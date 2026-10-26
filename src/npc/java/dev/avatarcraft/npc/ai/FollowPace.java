package dev.avatarcraft.npc.ai;

/** Distance hysteresis avoids repeatedly stopping or toggling sprint at the boundary. */
public final class FollowPace {
  private boolean moving, sprinting;

  public void update(double distanceSquared) {
    // Keep close behind the owner instead of waiting for a large gap, then stopping far back.
    moving = distanceSquared > (moving ? 2 * 2 : 3.5 * 3.5);
    sprinting = moving && distanceSquared > (sprinting ? 3.5 * 3.5 : 5 * 5);
  }

  public boolean moving() {
    return moving;
  }

  public boolean sprinting() {
    return sprinting;
  }

  public double speed() {
    return sprinting ? 2.2 : 1.6;
  }

  public void reset() {
    moving = sprinting = false;
  }
}

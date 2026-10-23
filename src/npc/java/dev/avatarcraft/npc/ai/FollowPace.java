package dev.avatarcraft.npc.ai;

/** Distance hysteresis avoids repeatedly stopping or toggling sprint at the boundary. */
public final class FollowPace {
  private boolean moving, sprinting;

  public void update(double distanceSquared) {
    moving = distanceSquared > (moving ? 2.5 * 2.5 : 6 * 6);
    sprinting = moving && distanceSquared > (sprinting ? 4.5 * 4.5 : 8 * 8);
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

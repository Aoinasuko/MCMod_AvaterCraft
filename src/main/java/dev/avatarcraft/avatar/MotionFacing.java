package dev.avatarcraft.avatar;

/** Keep human sidesteps relative to the look direction; vanilla turns its torso toward velocity. */
public final class MotionFacing {
  private float yaw;
  private double lastTime = Double.NaN;

  public float evaluate(float vanillaBody, float head, float speed, boolean free, double time) {
    float target = free && speed > .012f ? head : vanillaBody;
    if (!Double.isFinite(lastTime) || time < lastTime) yaw = target;
    else {
      double dt = Math.clamp(time - lastTime, 0, .1);
      yaw += wrap(target - yaw) * (float) (1 - Math.exp(-12 * dt));
    }
    lastTime = time;
    return yaw = wrap(yaw);
  }

  public static float wrap(float angle) {
    return ((angle + 180) % 360 + 360) % 360 - 180;
  }
}

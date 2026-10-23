package dev.avatarcraft.avatar;

/** Gameplay facts only. Vanilla per-bone animation angles never enter the motion player. */
public final class MotionSignals {
  public boolean grounded = true,
      sprinting,
      crouching,
      inWater,
      swimming,
      fallFlying,
      creativeFlying,
      climbing,
      passenger,
      sleeping,
      crawling,
      dead,
      hurt;
  public float speed, forward, side, verticalSpeed, headYaw, headPitch, swingProgress;
  public boolean hasItem, leftHand, mining, chargedCrossbow;
  public String useAction = "";

  public String baseAction() {
    if (dead) return "death";
    if (sleeping) return "sleep";
    if (passenger) return "ride";
    if (fallFlying) return "elytra";
    if (creativeFlying) return "creative_fly";
    if (inWater || swimming) return speed > .012f ? "swim" : "swim_idle";
    if (climbing) return "climb";
    if (crawling) return "crawl";
    if (!grounded) return verticalSpeed > .015f ? "jump" : "fall";
    if (crouching) return speed > .012f ? "crouch_walk" : "crouch_idle";
    if (speed <= .012f) return "idle";
    if (forward < -.015f && Math.abs(forward) >= Math.abs(side)) return "backward";
    if (Math.abs(side) > Math.abs(forward) * 1.3f) return side > 0 ? "strafe_left" : "strafe_right";
    return sprinting ? "run" : "walk";
  }

  public String overlayAction() {
    if (dead || sleeping) return "";
    if (!useAction.isEmpty()) return useAction;
    if (swingProgress > 0) return mining ? "mine" : "attack";
    if (chargedCrossbow) return "crossbow_hold";
    return hasItem ? "hold" : "";
  }
}

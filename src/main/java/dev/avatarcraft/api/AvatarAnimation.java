package dev.avatarcraft.api;

import dev.avatarcraft.avatar.*;
import java.util.*;
import org.joml.Vector3f;

/**
 * Independent per-actor animation state. No Minecraft player, client singleton, or GPU ownership.
 */
public final class AvatarAnimation {
  public final AvatarArchive archive;
  public final AvatarPose pose;
  private final ExpressionPlayer expressions;
  private ExpressionConfig config;
  private Map<String, Float> weights;

  public AvatarAnimation(AvatarArchive archive, long seed) {
    this.archive = Objects.requireNonNull(archive);
    pose = new AvatarPose(archive.avatar());
    expressions = new ExpressionPlayer(new Random(seed));
    config = archive.expressions().copy();
    weights = Map.copyOf(archive.avatar().defaults);
  }

  public void expressions(ExpressionConfig value) throws java.io.IOException {
    value.validate(archive.avatar());
    config = value.copy();
  }

  public void update(MotionSignals signals, double time, Vector3f position, float bodyYaw) {
    boolean eating = signals.useAction.equals("eat") || signals.useAction.equals("drink");
    weights =
        Map.copyOf(
            expressions.evaluate(
                config, archive.avatar().defaults, time, eating, signals.hurt, null));
    if (archive.avatar().motions != null) pose.animateMotion(signals, time, position, bodyYaw);
    else
      pose.animate(
          (float) time * signals.speed * 20,
          signals.speed * 4,
          signals.headYaw,
          signals.headPitch,
          signals.crouching,
          signals.swimming || signals.fallFlying,
          signals.swingProgress,
          eating,
          time,
          position,
          bodyYaw);
  }

  public Map<String, Float> weights() {
    return weights;
  }
}

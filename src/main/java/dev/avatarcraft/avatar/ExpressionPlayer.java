package dev.avatarcraft.avatar;

import java.util.*;
import java.util.random.RandomGenerator;

/** Independent monotonic clock, no per-frame random sampling or tick-rate assumptions. */
public final class ExpressionPlayer {
  private final RandomGenerator random;
  private double nextBlink = Double.NaN;

  public ExpressionPlayer(RandomGenerator random) {
    this.random = random;
  }

  public Map<String, Float> evaluate(
      ExpressionConfig c,
      Map<String, Float> defaults,
      double now,
      java.util.Collection<String> keys,
      String preview) {
    if (!Double.isFinite(nextBlink) || now > nextBlink + 10) nextBlink = now + interval(c);
    var result = new HashMap<>(defaults);
    apply(result, c, "idle", 1);
    double elapsed = now - nextBlink;
    if (elapsed >= c.blinkDurationSeconds) {
      nextBlink = now + interval(c);
      elapsed = -1;
    }
    if (elapsed >= 0) {
      float t = (float) (elapsed / c.blinkDurationSeconds);
      float weight = 1 - Math.abs(2 * t - 1);
      weight = weight * weight * (3 - 2 * weight);
      apply(result, c, "blink", weight);
    }
    for (String key : keys) apply(result, c, key, 1);
    if (preview != null) apply(result, c, preview, 1);
    return result;
  }

  private double interval(ExpressionConfig c) {
    return c.blinkMinSeconds + random.nextDouble() * (c.blinkMaxSeconds - c.blinkMinSeconds);
  }

  private static void apply(
      Map<String, Float> out, ExpressionConfig c, String event, float factor) {
    for (var e : c.events.getOrDefault(event, Map.of()).entrySet())
      out.put(e.getKey(), out.getOrDefault(e.getKey(), 0f) * (1 - factor) + e.getValue() * factor);
  }
}

package dev.avatarcraft.avatar;

import dev.avatarcraft.avatar.MotionPack.BonePose;
import java.util.*;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Independent base, upper-body and additive timelines with snapshot-based crossfades. */
public final class MotionPlayer {
  private final MotionPack pack;
  private final Layer base = new Layer(), upper = new Layer(), hurt = new Layer();
  private boolean initialized, wasGrounded = true;
  private float previousVertical;
  private double lastTime = Double.NaN, landUntil;

  public MotionPlayer(MotionPack pack) {
    this.pack = pack;
  }

  private static final class Layer {
    String action = "", clip = "";
    boolean mirrored;
    double elapsed, transition;
    Map<String, BonePose> from = Map.of(), last = Map.of();
  }

  public String baseAction() {
    return base.action;
  }

  public String overlayAction() {
    return upper.action;
  }

  public float duration(String action) {
    var b = pack.actions.get(action);
    return b == null ? 0 : pack.clips.get(b.clip).duration / b.speed;
  }

  public Map<String, BonePose> evaluate(MotionSignals input, double now) {
    double dt = Double.isFinite(lastTime) ? Math.clamp(now - lastTime, 0, .1) : 0;
    if (Double.isFinite(lastTime) && now < lastTime) {
      initialized = false;
      landUntil = 0;
      base.action = upper.action = hurt.action = "";
    }
    lastTime = now;
    if (initialized && !wasGrounded && input.grounded && previousVertical < -.04f)
      landUntil = now + duration("land");
    wasGrounded = input.grounded;
    previousVertical = input.verticalSpeed;
    String state = input.baseAction();
    if (input.grounded
        && !input.dead
        && !input.sleeping
        && !input.passenger
        && !input.inWater
        && !input.climbing
        && now < landUntil) state = "land";
    float rate =
        switch (state) {
          case "walk", "backward", "strafe_left", "strafe_right", "crouch_walk" ->
              Math.clamp(input.speed / .1f, .45f, 1.8f);
          case "run" -> Math.clamp(input.speed / .15f, .55f, 1.8f);
          case "swim", "crawl" -> Math.clamp(input.speed / .07f, .45f, 1.6f);
          case "climb" ->
              Math.abs(input.verticalSpeed) < .005f
                  ? 0
                  : Math.clamp(Math.abs(input.verticalSpeed) / .08f, .4f, 1.6f);
          default -> 1;
        };
    var output = new LinkedHashMap<>(layer(base, state, false, dt, rate, -1, true));
    String overlay = input.overlayAction();
    float phase = (overlay.equals("attack") || overlay.equals("mine")) ? input.swingProgress : -1;
    var overlayPose = layer(upper, overlay, input.leftHand, dt, 1, phase, false);
    for (var e : overlayPose.entrySet())
      if (MotionPack.upperBone(e.getKey())) {
        var underlying = output.getOrDefault(e.getKey(), BonePose.identity());
        var p = e.getValue();
        output.put(
            e.getKey(),
            new BonePose(
                new Quaternionf(underlying.rotation()).slerp(p.rotation(), p.weight()),
                new Vector3f(underlying.position()).lerp(p.position(), p.weight()),
                1));
      }
    var hurtPose = layer(hurt, input.hurt && !input.dead ? "hurt" : "", false, dt, 1, -1, false);
    for (var e : hurtPose.entrySet()) {
      var underlying = output.getOrDefault(e.getKey(), BonePose.identity());
      var p = e.getValue();
      var q = new Quaternionf().slerp(p.rotation(), p.weight());
      output.put(
          e.getKey(),
          new BonePose(
              new Quaternionf(underlying.rotation()).mul(q).normalize(),
              new Vector3f(underlying.position()).fma(p.weight(), p.position()),
              1));
    }
    initialized = true;
    return output;
  }

  private Map<String, BonePose> layer(
      Layer layer,
      String action,
      boolean mirrored,
      double dt,
      float rate,
      float phase,
      boolean isBase) {
    var binding = pack.actions.get(action);
    String clip = binding == null ? "" : mirrored ? binding.mirroredClip : binding.clip;
    if (!layer.action.equals(action) || !layer.clip.equals(clip) || layer.mirrored != mirrored) {
      layer.from = layer.last;
      layer.action = action;
      layer.clip = clip;
      layer.mirrored = mirrored;
      layer.elapsed = 0;
      layer.transition = 0;
    } else {
      layer.elapsed += dt * (binding == null ? 1 : binding.speed) * rate;
      layer.transition += dt;
    }
    Map<String, BonePose> target = isBase ? pack.neutralPose() : new LinkedHashMap<>();
    if (binding != null) {
      double t =
          phase >= 0
              ? Math.clamp(phase * binding.speed, 0, 1) * pack.clips.get(clip).duration
              : layer.elapsed;
      target.putAll(pack.sample(clip, t));
    }
    float fade = binding == null ? .15f : binding.blendSeconds;
    float alpha = !initialized || fade <= 0 ? 1 : (float) Math.clamp(layer.transition / fade, 0, 1);
    alpha = alpha * alpha * (3 - 2 * alpha);
    var result = new LinkedHashMap<String, BonePose>();
    var names = new HashSet<>(layer.from.keySet());
    names.addAll(target.keySet());
    for (String name : names) {
      var a = layer.from.get(name);
      var b = target.get(name);
      if (a == null)
        a =
            isBase
                ? BonePose.identity()
                : new BonePose(new Quaternionf(b.rotation()), new Vector3f(b.position()), 0);
      if (b == null)
        b =
            isBase
                ? BonePose.identity()
                : new BonePose(new Quaternionf(a.rotation()), new Vector3f(a.position()), 0);
      float weight = a.weight() + (b.weight() - a.weight()) * alpha;
      if (weight <= .00001f) continue;
      result.put(
          name,
          new BonePose(
              new Quaternionf(a.rotation()).slerp(b.rotation(), alpha).normalize(),
              new Vector3f(a.position()).lerp(b.position(), alpha),
              weight));
    }
    layer.last = result;
    return result;
  }
}

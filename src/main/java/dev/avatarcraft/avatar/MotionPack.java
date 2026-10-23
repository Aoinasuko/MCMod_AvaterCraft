package dev.avatarcraft.avatar;

import java.io.IOException;
import java.util.*;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Baked, avatar-local motion data. Does not depend on Minecraft or Unity at runtime. */
public final class MotionPack {
  public int version = 1;
  public String space = "localDelta", library;
  public Map<String, Key> neutral = new LinkedHashMap<>();
  public Map<String, Clip> clips = new LinkedHashMap<>();
  public Map<String, Binding> actions = new LinkedHashMap<>();
  public static final Set<String> BASE =
      Set.of(
          "idle",
          "walk",
          "run",
          "backward",
          "strafe_left",
          "strafe_right",
          "crouch_idle",
          "crouch_walk",
          "jump",
          "fall",
          "land",
          "swim",
          "swim_idle",
          "crawl",
          "climb",
          "ride",
          "sleep",
          "elytra",
          "creative_fly",
          "death");
  public static final Set<String> UPPER =
      Set.of(
          "hold",
          "attack",
          "mine",
          "eat",
          "drink",
          "block",
          "bow",
          "crossbow_charge",
          "crossbow_hold",
          "trident",
          "use",
          "spyglass",
          "horn");

  public static final class Key {
    public float time;
    public float[] rotation = {0, 0, 0, 1}, position = {0, 0, 0};
  }

  public static final class Clip {
    public String name, layer, interpolation = "linear";
    public float duration;
    public boolean loop;
    public Map<String, Key[]> tracks = new LinkedHashMap<>();
  }

  public static final class Binding {
    public String clip, mirroredClip, layer;
    public float speed = 1, blendSeconds = .18f;
  }

  public record BonePose(Quaternionf rotation, Vector3f position, float weight) {
    public static BonePose identity() {
      return new BonePose(new Quaternionf(), new Vector3f(), 1);
    }
  }

  public static boolean upperBone(String bone) {
    return Set.of("Spine", "Chest", "UpperChest", "Neck", "Head").contains(bone)
        || bone.contains("Shoulder")
        || bone.contains("Arm")
        || bone.contains("Hand")
        || bone.contains("Thumb")
        || bone.contains("Index")
        || bone.contains("Middle")
        || bone.contains("Ring")
        || bone.contains("Little");
  }

  public void validate(Avatar avatar) throws IOException {
    if (version != 1
        || !"localDelta".equals(space)
        || neutral == null
        || clips == null
        || actions == null
        || clips.size() > 128
        || actions.size() > 64) throw new IOException("Invalid motion pack");
    var required = new HashSet<>(BASE);
    required.addAll(UPPER);
    required.add("hurt");
    if (!actions.keySet().equals(required))
      throw new IOException("Motion action assignments are incomplete or unknown");
    for (var entry : neutral.entrySet()) {
      bone(entry.getKey(), avatar);
      key(entry.getValue());
    }
    int count = 0;
    for (var entry : clips.entrySet()) {
      if (!entry.getKey().matches("[A-Za-z0-9_-]{1,128}"))
        throw new IOException("Invalid motion ID");
      var clip = entry.getValue();
      if (clip == null
          || !Float.isFinite(clip.duration)
          || clip.duration <= 0
          || clip.duration > 30
          || clip.tracks == null
          || clip.tracks.size() > 96
          || !Set.of("base", "upper", "additive").contains(clip.layer)
          || !Set.of("linear", "smooth").contains(clip.interpolation))
        throw new IOException("Invalid motion clip");
      for (var track : clip.tracks.entrySet()) {
        bone(track.getKey(), avatar);
        if ("upper".equals(clip.layer) && !upperBone(track.getKey()))
          throw new IOException("Upper-body clip contains lower-body motion");
        var keys = track.getValue();
        if (keys == null
            || keys.length < 2
            || keys.length > 1801
            || (count += keys.length) > 120000)
          throw new IOException("Motion key count exceeds limit");
        float last = -1;
        for (var k : keys) {
          key(k);
          if (k.time <= last || k.time > clip.duration + .001f)
            throw new IOException("Unordered motion keys");
          last = k.time;
        }
        if (keys[0].time != 0 || Math.abs(keys[keys.length - 1].time - clip.duration) > .001f)
          throw new IOException("Motion must cover its duration");
      }
    }
    for (var a : actions.entrySet()) {
      var b = a.getValue();
      String layer =
          BASE.contains(a.getKey()) ? "base" : UPPER.contains(a.getKey()) ? "upper" : "additive";
      if (b == null
          || !layer.equals(b.layer)
          || !Float.isFinite(b.speed)
          || b.speed < .1f
          || b.speed > 4
          || !Float.isFinite(b.blendSeconds)
          || b.blendSeconds < 0
          || b.blendSeconds > 2) throw new IOException("Invalid motion binding");
      for (String id : new String[] {b.clip, b.mirroredClip})
        if (id == null || !clips.containsKey(id) || !layer.equals(clips.get(id).layer))
          throw new IOException("Motion binding references an incompatible clip");
    }
  }

  private static void bone(String name, Avatar avatar) throws IOException {
    if (!avatar.humanoid.containsKey(name))
      throw new IOException("Motion references missing Humanoid bone: " + name);
  }

  private static void key(Key k) throws IOException {
    if (k == null
        || !Float.isFinite(k.time)
        || k.time < 0
        || k.rotation == null
        || k.rotation.length != 4
        || k.position == null
        || k.position.length != 3) throw new IOException("Invalid motion key");
    float norm = 0;
    for (float v : k.rotation) {
      if (!Float.isFinite(v)) throw new IOException("Invalid motion rotation");
      norm += v * v;
    }
    if (norm < .99f || norm > 1.01f) throw new IOException("Motion quaternion is not normalized");
    for (float v : k.position)
      if (!Float.isFinite(v) || Math.abs(v) > 32)
        throw new IOException("Invalid motion translation");
  }

  public Map<String, BonePose> neutralPose() {
    var pose = new LinkedHashMap<String, BonePose>();
    for (var e : neutral.entrySet()) pose.put(e.getKey(), pose(e.getValue()));
    return pose;
  }

  public static BonePose pose(Key key) {
    return new BonePose(
        new Quaternionf(key.rotation[0], key.rotation[1], key.rotation[2], key.rotation[3]),
        new Vector3f(key.position[0], key.position[1], key.position[2]),
        1);
  }

  public Map<String, BonePose> sample(String id, double time) {
    var clip = clips.get(id);
    if (clip == null) return Map.of();
    float t =
        (float)
            (clip.loop
                ? ((time % clip.duration) + clip.duration) % clip.duration
                : Math.clamp(time, 0, clip.duration));
    var result = new LinkedHashMap<String, BonePose>();
    for (var track : clip.tracks.entrySet()) {
      var keys = track.getValue();
      int low = 0, high = keys.length - 1;
      while (high - low > 1) {
        int mid = (low + high) / 2;
        if (keys[mid].time <= t) low = mid;
        else high = mid;
      }
      float alpha = Math.clamp((t - keys[low].time) / (keys[high].time - keys[low].time), 0, 1);
      if ("smooth".equals(clip.interpolation)) alpha = alpha * alpha * (3 - 2 * alpha);
      var a = pose(keys[low]);
      var b = pose(keys[high]);
      result.put(
          track.getKey(),
          new BonePose(
              a.rotation.slerp(b.rotation, alpha).normalize(),
              a.position.lerp(b.position, alpha),
              1));
    }
    return result;
  }
}

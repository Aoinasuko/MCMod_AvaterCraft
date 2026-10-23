package dev.avatarcraft.avatar;

import java.io.IOException;
import java.util.*;

public final class ExpressionConfig {
  public int formatVersion = 1;
  public double blinkMinSeconds = 2, blinkMaxSeconds = 4, blinkDurationSeconds = .16;
  public Map<String, Map<String, Float>> events = new LinkedHashMap<>();
  public static final List<String> EVENTS = List.of("idle", "blink", "eat", "hurt");

  public ExpressionConfig() {
    for (String e : EVENTS) events.put(e, new LinkedHashMap<>());
  }

  public Set<String> shapes(Avatar a) {
    var set = new TreeSet<String>();
    for (var m : a.meshes)
      if (m.geometry != null) for (var s : m.geometry.morphs()) set.add(m.name + ":" + s.name());
    return set;
  }

  public void validate(Avatar a) throws IOException {
    if (formatVersion != 1
        || !Double.isFinite(blinkMinSeconds)
        || !Double.isFinite(blinkMaxSeconds)
        || !Double.isFinite(blinkDurationSeconds)
        || blinkMinSeconds < .2
        || blinkMaxSeconds < blinkMinSeconds
        || blinkMaxSeconds > 60
        || blinkDurationSeconds < .05
        || blinkDurationSeconds > 1) throw new IOException("Invalid blink timing");
    if (events == null) throw new IOException("Missing expression events");
    var shapes = shapes(a);
    for (var e : events.entrySet()) {
      if (!EVENTS.contains(e.getKey()) || e.getValue() == null)
        throw new IOException("Unknown expression event");
      for (var v : e.getValue().entrySet())
        if (!shapes.contains(v.getKey())
            || v.getValue() == null
            || !Float.isFinite(v.getValue())
            || v.getValue() < 0
            || v.getValue() > 100)
          throw new IOException("Invalid expression shape/weight: " + v.getKey());
    }
    for (String e : EVENTS) events.computeIfAbsent(e, k -> new LinkedHashMap<>());
  }

  public ExpressionConfig copy() {
    return AvatarArchive.GSON.fromJson(AvatarArchive.GSON.toJson(this), ExpressionConfig.class);
  }
}

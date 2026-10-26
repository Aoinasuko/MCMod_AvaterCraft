package dev.avatarcraft.npc.ai;

import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Five trained FP32 experts; one routed 56→1419→1419→1 ranker per candidate. */
public final class LocalActionPolicy {
  public static final int VERSION = 7, EXPERTS = 5, HIDDEN = 1419, WEIGHT_BYTES = 41945660, PARAMETERS = 10486415;
  public static final List<String> FEATURES =
      List.of(
          "urgent",
          "goal",
          "ready",
          "setup",
          "verify",
          "scan",
          "approach",
          "work",
          "distance",
          "failures",
          "blocked",
          "replan",
          "idle",
          "food_need",
          "progress",
          "repeat",
          "target_missing",
          "tool_missing",
          "bag_free",
          "candidate_reachable",
          "candidate_loaded",
          "unvisited",
          "depth_gain",
          "exploration_fraction",
          "owner_distance",
          "health",
          "inventory_fraction",
          "ore_target",
          "diamond_target",
          "ingredients_ready",
          "chest_source",
          "remaining_budget",
          "combat",
          "target_health",
          "hostile_distance",
          "weapon_ready",
          "ranged",
          "ammunition",
          "needs_aim",
          "use_charge",
          "smelt_ready",
          "fuel_available",
          "team_waiting",
          "delegated",
          "dimension_travel",
          "portal_visible",
          "structure_observed",
          "danger", "height_delta", "path_known", "edit_fraction", "target_failed",
          "station_distance", "team_stalled", "supply_shortfall", "travel_cost");
  private final float[][] w1 = new float[EXPERTS][],
      b1 = new float[EXPERTS][],
      w2 = new float[EXPERTS][],
      b2 = new float[EXPERTS][],
      w3 = new float[EXPERTS][],
      b3 = new float[EXPERTS][];

  private static final class Scratch {
    final float[] input = new float[56], a = new float[HIDDEN], b = new float[HIDDEN];
  }

  private final ThreadLocal<Scratch> scratch = ThreadLocal.withInitial(Scratch::new);

  private static final class Bundled {
    static final LocalActionPolicy INSTANCE = load();
  }

  public static LocalActionPolicy bundled() {
    return Bundled.INSTANCE;
  }

  private static LocalActionPolicy load() {
    try (var meta =
            LocalActionPolicy.class.getResourceAsStream(
                "/data/bep_3davater_companion/ai/action-policy.json");
        var weights =
            LocalActionPolicy.class.getResourceAsStream(
                "/data/bep_3davater_companion/ai/action-policy.bin")) {
      if (meta == null || weights == null) throw new IOException("Missing local policy");
      return new LocalActionPolicy(
          new String(meta.readNBytes(65537), StandardCharsets.UTF_8),
          weights.readNBytes(WEIGHT_BYTES + 1));
    } catch (IOException e) {
      throw new IllegalStateException("Cannot load local policy", e);
    }
  }

  public LocalActionPolicy(String json) {
    this(json, new byte[0]);
  }

  public LocalActionPolicy(String json, byte[] weights) {
    if (json.length() > 65536 || weights.length != WEIGHT_BYTES)
      throw new IllegalArgumentException("Invalid policy size");
    var o = JsonParser.parseString(json).getAsJsonObject();
    if (!o.keySet()
            .containsAll(
                Set.of(
                    "version",
                    "features",
                    "dimensions",
                    "activation",
                    "format",
                    "parameters",
                    "sha256",
                    "probes"))
        || o.get("version").getAsInt() != VERSION
        || !o.has("experts")
        || o.getAsJsonArray("experts").size() != EXPERTS
        || !o.has("routing")
        || !o.get("routing").getAsString().equals("context-v7")
        || o.get("parameters").getAsInt() != PARAMETERS
        || !o.get("activation").getAsString().equals("relu")
        || !o.get("format").getAsString().equals("float32-le")
        || !o.getAsJsonArray("features").asList().stream()
            .map(JsonElement::getAsString)
            .toList()
            .equals(FEATURES)
        || !o.getAsJsonArray("dimensions").asList().stream()
            .map(JsonElement::getAsInt)
            .toList()
            .equals(List.of(56, HIDDEN, HIDDEN, 1)))
      throw new IllegalArgumentException("Unknown policy contract");
    try {
      if (!HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(weights))
          .equals(o.get("sha256").getAsString()))
        throw new IllegalArgumentException("Policy hash mismatch");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    var bytes = ByteBuffer.wrap(weights).order(ByteOrder.LITTLE_ENDIAN);
    for (int expert = 0; expert < EXPERTS; expert++) {
      w1[expert] = read(bytes, 56 * HIDDEN);
      b1[expert] = read(bytes, HIDDEN);
      w2[expert] = read(bytes, HIDDEN * HIDDEN);
      b2[expert] = read(bytes, HIDDEN);
      w3[expert] = read(bytes, HIDDEN);
      b3[expert] = read(bytes, 1);
    }
    for (var entry : o.getAsJsonArray("probes")) {
      var p = entry.getAsJsonObject();
      var a = p.getAsJsonArray("input");
      double[] x = new double[a.size()];
      for (int i = 0; i < x.length; i++) x[i] = a.get(i).getAsDouble();
      double expected = p.get("score").getAsDouble();
      if (!Double.isFinite(expected) || Math.abs(score(x) - expected) > .001)
        throw new IllegalArgumentException("Policy inference mismatch");
    }
  }

  private static float[] read(ByteBuffer in, int size) {
    var values = new float[size];
    for (int i = 0; i < size; i++) {
      values[i] = in.getFloat();
      if (!Float.isFinite(values[i]) || Math.abs(values[i]) > 1000)
        throw new IllegalArgumentException("Invalid weight");
    }
    return values;
  }

  public double score(double[] x) {
    if (x.length != 56) throw new IllegalArgumentException("Expected 56 features");
    var s = scratch.get();
    for (int i = 0; i < 56; i++) {
      if (!Double.isFinite(x[i]) || Math.abs(x[i]) > 1)
        throw new IllegalArgumentException("Invalid feature");
      s.input[i] = (float) x[i];
    }
    int expert = expert(x);
    layer(s.input, s.a, w1[expert], b1[expert], 56);
    layer(s.a, s.b, w2[expert], b2[expert], HIDDEN);
    float result = b3[expert][0];
    for (int i = 0; i < HIDDEN; i++) result += s.b[i] * w3[expert][i];
    return result;
  }

  public static int expert(double[] x) {
    if (x[32] > .5 || x[36] > .5) return 2;
    if (x[40] > .5 || x[17] > .5 || x[3] > .5) return 1;
    if (x[44] > .5 || x[46] > .5) return 3;
    return x[27] > .5 ? 4 : 0;
  }

  private static void layer(
      float[] input, float[] output, float[] weights, float[] bias, int width) {
    for (int j = 0; j < output.length; j++) {
      float value = bias[j];
      int start = j * width;
      for (int i = 0; i < width; i++) value += weights[start + i] * input[i];
      output[j] = Math.max(0, value);
    }
  }

  public static double[] features(
      String kind, boolean urgent, double distance, int failures, int food, int done, int count) {
    return features(kind, urgent, distance, failures, food, done, count, new JsonObject());
  }

  public static double[] features(
      String kind,
      boolean urgent,
      double distance,
      int failures,
      int food,
      int done,
      int count,
      JsonObject context) {
    var x = new double[56];
    x[0] = urgent ? 1 : 0;
    x[1] = kind.equals("idle") ? 0 : 1;
    x[2] = Set.of("idle", "replan").contains(kind) ? 0 : 1;
    x[3] = Set.of("equip", "prepare").contains(kind) ? 1 : 0;
    x[4] = kind.equals("verify") ? 1 : 0;
    x[5] = kind.equals("scan") ? 1 : 0;
    x[6] = Set.of("approach", "explore").contains(kind) ? 1 : 0;
    x[7] = Set.of("execute", "eat", "excavate").contains(kind) ? 1 : 0;
    x[8] = Math.clamp(distance / 16., 0, 1);
    if (context.has("distance")) x[8] = Math.clamp(context.get("distance").getAsDouble(), 0, 1);
    x[9] = Math.clamp(failures / 3., 0, 1);
    x[10] = x[11] = kind.equals("replan") ? 1 : 0;
    x[12] = kind.equals("idle") ? 1 : 0;
    x[13] = Math.clamp((20 - food) / 20., 0, 1);
    x[14] = Math.clamp((double) done / count, 0, 1);
    for (int i = 16; i < 56; i++)
      x[i] =
          Math.clamp(
              context.has(FEATURES.get(i)) ? context.get(FEATURES.get(i)).getAsDouble() : 0, -1, 1);
    if (!context.has("candidate_reachable")) x[19] = 1;
    if (!context.has("candidate_loaded")) x[20] = 1;
    if (!context.has("health")) x[25] = 1;
    if (!context.has("remaining_budget")) x[31] = 1;
    return x;
  }
}

package dev.avatarcraft.avatar;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Shared exporter/runtime property vocabulary and pure lilToon-style blend arithmetic. */
public final class SurfaceMaterial {
  private static final JsonObject SCHEMA;
  public static final List<String> SLOTS;

  static {
    try (var in = SurfaceMaterial.class.getResourceAsStream("/assets/bep_3davater_loader/surface.json")) {
      if (in == null) throw new IOException("Missing material schema");
      SCHEMA =
          JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
      SLOTS =
          SCHEMA.getAsJsonArray("slots").asList().stream().map(JsonElement::getAsString).toList();
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  public static String key(String property) {
    return "S" + property.toUpperCase(Locale.ROOT);
  }

  public static Map<String, Float> defines(Avatar.Material material) {
    var result = new LinkedHashMap<String, Float>();
    for (var e : SCHEMA.getAsJsonObject("numbers").entrySet())
      result.put(key(e.getKey()), material.number(e.getKey(), e.getValue().getAsFloat()));
    for (var e : SCHEMA.getAsJsonObject("vectors").entrySet()) {
      float[] fallback = new float[4];
      for (int i = 0; i < 4; i++) fallback[i] = e.getValue().getAsJsonArray().get(i).getAsFloat();
      var value = material.color(e.getKey(), fallback);
      for (int i = 0; i < 4; i++) result.put(key(e.getKey()) + "_" + "XYZW".charAt(i), value[i]);
    }
    return result;
  }

  public static float blend(float dst, float src, float alpha, int mode) {
    float value =
        switch (mode) {
          case 1 -> dst + src;
          case 2 -> Math.max(dst + src - dst * src, dst);
          case 3 -> dst * src;
          default -> src;
        };
    return dst + (value - dst) * Math.clamp(alpha, 0, 1);
  }

  public static float layerAlpha(float base, float layer, int mode) {
    return MaterialAlpha.mask(base, layer, mode, 1, 0);
  }

  private SurfaceMaterial() {}
}

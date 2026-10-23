package dev.avatarcraft.avatar;

import java.util.Locale;

/** Material semantics independent of Minecraft/GPU classes, matching Unity's surface modes. */
public final class MaterialAlpha {
  public enum Mode {
    OPAQUE,
    CUTOUT,
    TRANSLUCENT
  }

  public static Mode mode(Avatar.Material m) {
    String shader = m.shader == null ? "" : m.shader.toLowerCase(Locale.ROOT);
    String tag = m.renderType == null ? "" : m.renderType.toLowerCase(Locale.ROOT);
    if (tag.equals("transparentcutout") || shader.contains("cutout")) return Mode.CUTOUT;
    if (tag.equals("transparent") || shader.contains("transparent") || shader.contains("fade"))
      return Mode.TRANSLUCENT;
    float lil = m.number("_TransparentMode", -1), standard = m.number("_Mode", -1);
    if (lil >= 2 || standard >= 2 || m.number("_Surface", 0) >= 1) return Mode.TRANSLUCENT;
    if (lil == 1 || standard == 1) return Mode.CUTOUT;
    if (tag.equals("opaque")) return Mode.OPAQUE;
    if (m.renderQueue >= 2500) return Mode.TRANSLUCENT;
    if (m.renderQueue >= 2450) return Mode.CUTOUT;
    return Mode.OPAQUE;
  }

  public static float cutoff(Avatar.Material m) {
    return mode(m) == Mode.CUTOUT ? clamp(m.number("_Cutoff", .5f)) : .001f;
  }

  public static boolean writeDepth(Avatar.Material m) {
    return m.number("_ZWrite", mode(m) == Mode.TRANSLUCENT ? 0 : 1) != 0;
  }

  public static float mask(float alpha, float sample, int mode, float scale, float offset) {
    float value = clamp(sample * scale + offset);
    return switch (mode) {
      case 1 -> value;
      case 2 -> alpha * value;
      case 3 -> clamp(alpha + value);
      case 4 -> clamp(alpha - value);
      default -> alpha;
    };
  }

  private static float clamp(float value) {
    return Math.clamp(value, 0, 1);
  }

  private MaterialAlpha() {}
}

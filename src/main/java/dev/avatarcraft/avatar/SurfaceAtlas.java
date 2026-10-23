package dev.avatarcraft.avatar;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * Bounded, padded atlas. Rectangles address pixel centres; wrapping happens before atlas lookup.
 */
public final class SurfaceAtlas {
  public static final int LIMIT = 4096;

  public record Rect(int x, int y, int width, int height) {}

  public record Image(BufferedImage pixels, Map<String, Rect> rects, boolean reduced) {}

  public record Uniforms(Map<String, Float> values, Set<String> present) {}

  public static Uniforms uniforms(Avatar.Material material, Image atlas) {
    var values = new LinkedHashMap<String, Float>();
    var present = new HashSet<String>();
    for (var slot : SurfaceMaterial.SLOTS) {
      String source = material.texture(slot);
      var rect = source == null ? null : atlas.rects.get(source);
      if (rect == null) continue;
      String key = "T" + slot.toUpperCase(Locale.ROOT);
      present.add(key);
      values.put(key + "_X", (rect.x + .5f) / atlas.pixels.getWidth());
      values.put(key + "_Y", (rect.y + .5f) / atlas.pixels.getHeight());
      values.put(key + "_W", (rect.width - 1f) / atlas.pixels.getWidth());
      values.put(key + "_H", (rect.height - 1f) / atlas.pixels.getHeight());
      var scale = material.uv(slot, "scale", 1, 1);
      var offset = material.uv(slot, "offset", 0, 0);
      values.put(key + "_SX", scale[0]);
      values.put(key + "_SY", scale[1]);
      values.put(key + "_OX", offset[0]);
      values.put(key + "_OY", offset[1]);
      var prop = material.properties.getAsJsonObject(slot);
      values.put(key + "_WU", prop.has("wrapU") ? prop.get("wrapU").getAsFloat() : 0);
      values.put(key + "_WV", prop.has("wrapV") ? prop.get("wrapV").getAsFloat() : 0);
    }
    return new Uniforms(Map.copyOf(values), Set.copyOf(present));
  }

  private record Source(String name, byte[] png, int width, int height) {}

  public static Image build(Map<String, byte[]> sources) throws IOException {
    var items = new ArrayList<Source>();
    for (var e : sources.entrySet()) {
      try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(e.getValue()))) {
        var readers = ImageIO.getImageReaders(stream);
        if (!readers.hasNext()) throw new IOException("Invalid atlas image");
        var reader = readers.next();
        try {
          reader.setInput(stream);
          items.add(new Source(e.getKey(), e.getValue(), reader.getWidth(0), reader.getHeight(0)));
        } finally {
          reader.dispose();
        }
      }
    }
    items.sort(
        Comparator.comparingInt((Source s) -> Math.max(s.width, s.height))
            .reversed()
            .thenComparing(Source::name));
    double scale = 1;
    Map<String, Rect> rects;
    int width, height;
    while (true) {
      rects = new LinkedHashMap<>();
      int x = 0, y = 0, row = 0;
      width = 1;
      height = 1;
      for (var item : items) {
        int w = Math.max(1, (int) (item.width * scale)),
            h = Math.max(1, (int) (item.height * scale));
        if (x + w + 2 > LIMIT) {
          x = 0;
          y += row;
          row = 0;
        }
        rects.put(item.name, new Rect(x + 1, y + 1, w, h));
        x += w + 2;
        row = Math.max(row, h + 2);
        width = Math.max(width, x);
        height = Math.max(height, y + row);
      }
      if (width <= LIMIT && height <= LIMIT) break;
      scale *= .5;
      if (scale < 1.0 / 16384) throw new IOException("Atlas image count exceeds limits");
    }
    var result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    for (var item : items) {
      var src = ImageIO.read(new ByteArrayInputStream(item.png));
      if (src == null) throw new IOException("Invalid atlas PNG");
      var r = rects.get(item.name);
      Graphics2D g = result.createGraphics();
      try {
        g.setComposite(java.awt.AlphaComposite.Src);
        g.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, r.x, r.y, r.width, r.height, null);
      } finally {
        g.dispose();
        src.flush();
      }
      for (int x = -1; x <= r.width; x++)
        for (int y : new int[] {-1, r.height})
          result.setRGB(
              r.x + x,
              r.y + y,
              result.getRGB(
                  r.x + Math.clamp(x, 0, r.width - 1), r.y + Math.clamp(y, 0, r.height - 1)));
      for (int y = 0; y < r.height; y++) {
        result.setRGB(r.x - 1, r.y + y, result.getRGB(r.x, r.y + y));
        result.setRGB(r.x + r.width, r.y + y, result.getRGB(r.x + r.width - 1, r.y + y));
      }
    }
    return new Image(result, Map.copyOf(rects), scale != 1);
  }

  private SurfaceAtlas() {}
}

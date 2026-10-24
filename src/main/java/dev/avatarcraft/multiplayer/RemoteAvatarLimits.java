package dev.avatarcraft.multiplayer;

import dev.avatarcraft.avatar.AvatarArchive;
import java.awt.AlphaComposite;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * Ciphertext is opaque to the server: each renderer must enforce its own decoded resource budget.
 */
public final class RemoteAvatarLimits {
  /**
   * Adapt high-resolution source textures to the remote display budget without modifying originals.
   */
  public static AvatarArchive prepare(AvatarArchive archive) throws IOException {
    if (archive.avatar().meshes.length > 64
        || archive.avatar().materials.length > 32
        || archive.images().size() > 128)
      throw new IOException("Remote avatar object budget exceeded");
    var sizes = new HashMap<String, Long>();
    long texels = 0, referenced = 0;
    for (var image : archive.images().entrySet()) {
      if (image.getKey().equals("preview.png")) continue;
      if (image.getValue().length < 24) throw new IOException("Invalid image");
      var header = ByteBuffer.wrap(image.getValue());
      int w = header.getInt(16), h = header.getInt(20);
      if (w < 1 || h < 1 || w > 4096 || h > 4096)
        throw new IOException("Remote source image dimension limit");
      long count = (long) w * h;
      sizes.put(image.getKey(), count);
      texels += count;
    }
    for (var material : archive.avatar().materials) {
      var used = new HashSet<String>();
      for (var entry : material.properties.entrySet())
        if (entry.getValue().isJsonObject()) {
          var t = entry.getValue().getAsJsonObject().get("texture");
          if (t != null && !t.isJsonNull()) used.add(t.getAsString());
        }
      for (var t : used) referenced += sizes.getOrDefault(t, 0L);
    }
    if (texels > 128L << 20 || referenced > 256L << 20)
      throw new IOException("Remote source image budget exceeded");
    double scale =
        Math.sqrt(
            Math.min(
                1,
                Math.min(
                    (16.0 * (1 << 20)) / Math.max(1, texels),
                    (32.0 * (1 << 20)) / Math.max(1, referenced))));
    if (scale >= 1) {
      validate(archive);
      return archive;
    }
    var images = new HashMap<String, byte[]>();
    var converted = new IdentityHashMap<byte[], byte[]>();
    for (var entry : archive.images().entrySet()) {
      if (entry.getKey().equals("preview.png")) continue;
      byte[] png = converted.get(entry.getValue());
      if (png == null) {
        BufferedImage src;
        try (var input =
            ImageIO.createImageInputStream(new ByteArrayInputStream(entry.getValue()))) {
          var reader = ImageIO.getImageReadersByFormatName("png").next();
          try {
            reader.setInput(input, false, true);
            src = reader.read(0);
          } finally {
            reader.dispose();
          }
        }
        if (src == null) throw new IOException("Invalid remote image");
        var dst =
            new BufferedImage(
                Math.max(1, (int) (src.getWidth() * scale)),
                Math.max(1, (int) (src.getHeight() * scale)),
                BufferedImage.TYPE_INT_ARGB);
        var graphics = dst.createGraphics();
        try {
          graphics.setComposite(AlphaComposite.Src);
          graphics.setRenderingHint(
              RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
          graphics.drawImage(src, 0, 0, dst.getWidth(), dst.getHeight(), null);
        } finally {
          graphics.dispose();
          src.flush();
        }
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(dst, "png", bytes);
        dst.flush();
        png = bytes.toByteArray();
        converted.put(entry.getValue(), png);
      }
      images.put(entry.getKey(), png);
    }
    var result = new AvatarArchive(archive.avatar(), Map.copyOf(images), archive.expressions());
    validate(result);
    return result;
  }

  public static void validate(AvatarArchive archive) throws IOException {
    var avatar = archive.avatar();
    if (avatar.meshes.length > 64 || avatar.materials.length > 32 || archive.images().size() > 128)
      throw new IOException("Remote avatar object budget exceeded");
    long vertices = 0, morphs = 0, texels = 0, referenced = 0;
    int draws = 0;
    for (var mesh : avatar.meshes) {
      vertices += mesh.geometry.vertexCount();
      draws += mesh.submeshes.length;
      for (var morph : mesh.geometry.morphs())
        for (var frame : morph.frames()) morphs += frame.indices().length;
    }
    if (vertices > 200000 || draws > 128 || morphs > 2000000)
      throw new IOException("Remote avatar geometry budget exceeded");
    var pixels = new HashMap<String, Long>();
    for (var image : archive.images().entrySet()) {
      if (image.getKey().equals("preview.png"))
        continue; // Display-only sessions never decode library thumbnails.
      if (image.getValue().length < 24) throw new IOException("Invalid image");
      var header = ByteBuffer.wrap(image.getValue());
      int width = header.getInt(16), height = header.getInt(20);
      if (width < 1 || height < 1 || width > 4096 || height > 4096)
        throw new IOException("Remote image dimension budget exceeded");
      long count = (long) width * height;
      texels += count;
      pixels.put(image.getKey(), count);
    }
    if (texels > 16L << 20) throw new IOException("Remote image pixel budget exceeded");
    for (var material : avatar.materials) {
      var unique = new HashSet<String>();
      for (var value : material.properties.entrySet())
        if (value.getValue().isJsonObject()) {
          var texture = value.getValue().getAsJsonObject().get("texture");
          if (texture != null && !texture.isJsonNull()) unique.add(texture.getAsString());
        }
      for (var texture : unique) referenced += pixels.getOrDefault(texture, 0L);
    }
    if (referenced > 32L << 20) throw new IOException("Remote material image budget exceeded");
  }

  private RemoteAvatarLimits() {}
}

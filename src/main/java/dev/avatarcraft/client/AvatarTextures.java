package dev.avatarcraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.avatarcraft.avatar.Avatar;
import dev.avatarcraft.avatar.AvatarArchive;
import dev.avatarcraft.avatar.SurfaceAtlas;
import dev.avatarcraft.avatar.SurfaceMaterial;
import java.io.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

public final class AvatarTextures implements AutoCloseable {
  private final AvatarArchive archive;
  private final Map<String, Identifier> ids = new HashMap<>();
  private final String prefix;

  public record Surface(Identifier texture, Map<String, Float> defines, Set<String> present) {}

  private final Map<Avatar.Material, Surface> surfaces = new IdentityHashMap<>();

  public AvatarTextures(AvatarArchive a) {
    archive = a;
    prefix = a.avatar().id + "/" + UUID.randomUUID();
  }

  public Identifier get(String entry) {
    String key = entry == null ? "white" : entry;
    return ids.computeIfAbsent(
        key,
        k -> {
          try {
            NativeImage image;
            if (entry == null) {
              image = new NativeImage(1, 1, false);
              image.setPixel(0, 0, -1);
            } else image = NativeImage.read(new ByteArrayInputStream(archive.images().get(entry)));
            var id =
                Identifier.fromNamespaceAndPath(
                    "bep_3davater_loader",
                    prefix
                        + "/"
                        + UUID.nameUUIDFromBytes(
                            key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            Minecraft.getInstance()
                .getTextureManager()
                .register(id, new DynamicTexture(() -> "bep_3davater_loader " + key, image));
            return id;
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
  }

  public Surface surface(Avatar.Material material) {
    return surfaces.computeIfAbsent(
        material,
        mat -> {
          var sources = new LinkedHashMap<String, byte[]>();
          for (var slot : SurfaceMaterial.SLOTS) {
            String entry = mat.texture(slot);
            if (entry != null) sources.put(entry, archive.images().get(entry));
          }
          try {
            var atlas = SurfaceAtlas.build(sources);
            var pixels = atlas.pixels();
            var image = new NativeImage(pixels.getWidth(), pixels.getHeight(), false);
            for (int y = 0; y < pixels.getHeight(); y++)
              for (int x = 0; x < pixels.getWidth(); x++) image.setPixel(x, y, pixels.getRGB(x, y));
            var id =
                Identifier.fromNamespaceAndPath(
                    "bep_3davater_loader", prefix + "/surface_" + surfaces.size());
            Minecraft.getInstance()
                .getTextureManager()
                .register(id, new DynamicTexture(() -> "bep_3davater_loader material atlas", image));
            ids.put("surface:" + surfaces.size(), id);
            var uniforms = SurfaceAtlas.uniforms(mat, atlas);
            pixels.flush();
            if (atlas.reduced())
              System.getLogger("bep_3davater_loader")
                  .log(
                      System.Logger.Level.WARNING,
                      "Reduced auxiliary textures to fit 4096px atlas: " + mat.name);
            return new Surface(id, uniforms.values(), uniforms.present());
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
  }

  @Override
  public void close() {
    for (var id : ids.values()) Minecraft.getInstance().getTextureManager().release(id);
    ids.clear();
    surfaces.clear();
  }
}

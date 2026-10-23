package dev.avatarcraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.avatarcraft.avatar.AvatarArchive;
import java.io.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

public final class AvatarTextures implements AutoCloseable {
  private final AvatarArchive archive;
  private final Map<String, Identifier> ids = new HashMap<>();
  private final String prefix;

  public AvatarTextures(AvatarArchive a) {
    archive = a;
    prefix = a.avatar().id;
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
                    "avatarcraft",
                    prefix
                        + "/"
                        + UUID.nameUUIDFromBytes(
                            key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            Minecraft.getInstance()
                .getTextureManager()
                .register(id, new DynamicTexture(() -> "AvatarCraft " + key, image));
            return id;
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
  }

  @Override
  public void close() {
    for (var id : ids.values()) Minecraft.getInstance().getTextureManager().release(id);
    ids.clear();
  }
}

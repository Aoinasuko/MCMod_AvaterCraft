package dev.avatarcraft.client;

import dev.avatarcraft.avatar.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class AvatarSession {
  public static AvatarSession active;
  public static Runnable selectionChanged = () -> {};
  public static java.util.function.Supplier<String> status = () -> "";
  public final dev.avatarcraft.api.ExpressionKeys expressionKeys = new dev.avatarcraft.api.ExpressionKeys();
  private AvatarActorVisual previewVisual;

  public AvatarActorVisual previewVisual() {
    if (previewVisual == null) previewVisual = new AvatarActorVisual(archive, 0);
    return previewVisual;
  }

  public void close() {
    if (previewVisual != null) previewVisual.close();
    textures.close();
  }
  public final AvatarArchive archive;
  public ExpressionConfig config;
  public String preview;
  public final AvatarTextures textures;

  public AvatarSession(AvatarArchive a) throws IOException {
    this(a, false);
  }

  public AvatarSession(AvatarArchive a, boolean remote) throws IOException {
    archive = a;
    config = a.expressions().copy();
    var path = AvatarLibrary.expressionPath(a.avatar().id);
    if (!remote && Files.exists(path)) {
      if (Files.size(path) > 1 << 20) throw new IOException("Expression config exceeds 1 MiB");
      config = AvatarArchive.GSON.fromJson(Files.readString(path), ExpressionConfig.class);
      config.validate(a.avatar());
    }
    textures = new AvatarTextures(a);
  }

  public static void select(AvatarArchive archive) throws IOException {
    AvatarSession next = archive == null ? null : new AvatarSession(archive);
    AvatarLibrary.atomicWrite(
        AvatarLibrary.ROOT.resolve("selection.json"),
        AvatarArchive.GSON.toJson(Map.of("avatar", archive == null ? "" : archive.avatar().id)));
    var old = active;
    active = next;
    if (old != null) old.close();
    selectionChanged.run();
  }

  public void saveExpressions() throws IOException {
    config.validate(archive.avatar());
    String json = AvatarArchive.GSON.toJson(config);
    if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1 << 20)
      throw new IOException("Expression config exceeds 1 MiB");
    AvatarLibrary.atomicWrite(
        AvatarLibrary.expressionPath(archive.avatar().id), json);
    if (active != null && active.archive.avatar().id.equals(archive.avatar().id)) {
      active.config = config.copy();
      selectionChanged.run();
    }
    AvatarLibrary.reloadAfterCurrent();
  }
}

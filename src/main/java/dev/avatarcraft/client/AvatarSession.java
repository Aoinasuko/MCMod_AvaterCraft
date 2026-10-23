package dev.avatarcraft.client;

import dev.avatarcraft.avatar.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class AvatarSession {
  public static AvatarSession active;
  public final AvatarArchive archive;
  public final AvatarPose pose;
  public final AvatarPose previewPose;
  public final FirstPersonArm leftFirstPerson, rightFirstPerson;
  public final MotionFacing facing = new MotionFacing();
  public final ExpressionPlayer expressions = new ExpressionPlayer(new Random());
  public ExpressionConfig config;
  public String preview;
  public final AvatarTextures textures;

  public AvatarSession(AvatarArchive a) throws IOException {
    archive = a;
    pose = new AvatarPose(a.avatar());
    previewPose = new AvatarPose(a.avatar());
    leftFirstPerson = new FirstPersonArm(a.avatar(), true);
    rightFirstPerson = new FirstPersonArm(a.avatar(), false);
    config = a.expressions().copy();
    var path = AvatarLibrary.expressionPath(a.avatar().id);
    if (Files.exists(path)) {
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
    if (old != null) old.textures.close();
  }

  public void saveExpressions() throws IOException {
    config.validate(archive.avatar());
    AvatarLibrary.atomicWrite(
        AvatarLibrary.expressionPath(archive.avatar().id), AvatarArchive.GSON.toJson(config));
  }
}

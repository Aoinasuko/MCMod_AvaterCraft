package dev.avatarcraft.player.multiplayer;

import dev.avatarcraft.avatar.AvatarArchive;
import java.util.*;

public record ShareManifest(String avatar, String sha256, String nonce, int bytes) {
  public ShareManifest {
    if (avatar == null
        || !avatar.matches("[a-z0-9_-]{1,64}")
        || sha256 == null
        || !sha256.matches("[a-f0-9]{64}")
        || nonce == null
        || bytes < 32
        || bytes > ShareMessage.MAX_BYTES)
      throw new IllegalArgumentException("Invalid avatar manifest");
    if (Base64.getDecoder().decode(nonce).length != 12)
      throw new IllegalArgumentException("Invalid nonce");
  }

  public byte[] nonceBytes() {
    return Base64.getDecoder().decode(nonce);
  }

  public String json() {
    return AvatarArchive.GSON.toJson(this);
  }

  public static ShareManifest parse(String json) {
    if (json.length() > 1024) throw new IllegalArgumentException("Oversized avatar manifest");
    var m = AvatarArchive.GSON.fromJson(json, ShareManifest.class);
    if (m == null) throw new IllegalArgumentException("Missing manifest");
    return m;
  }
}

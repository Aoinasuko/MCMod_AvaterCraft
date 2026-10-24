package dev.avatarcraft.multiplayer;

import dev.avatarcraft.avatar.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** The plaintext representation exists only in memory on the uploader and rendering clients. */
public final class ShareBundle {
  public static byte[] encode(byte[] archive, ExpressionConfig expressions) throws IOException {
    byte[] json = AvatarArchive.GSON.toJson(expressions).getBytes(StandardCharsets.UTF_8);
    if (archive.length > ShareMessage.MAX_BYTES - (1 << 20) - 32 || json.length > 1 << 20)
      throw new IOException("Avatar exceeds multiplayer size limit");
    var result = new ByteArrayOutputStream();
    var out = new DataOutputStream(result);
    out.writeInt(0x41565031);
    out.writeInt(archive.length);
    out.write(archive);
    out.writeInt(json.length);
    out.write(json);
    return result.toByteArray();
  }

  public static AvatarArchive decode(byte[] plain, String expectedAvatar) throws IOException {
    if (plain.length > ShareMessage.MAX_BYTES) throw new IOException("Oversized private avatar");
    var in = new DataInputStream(new ByteArrayInputStream(plain));
    if (in.readInt() != 0x41565031) throw new IOException("Invalid private avatar bundle");
    int n = in.readInt();
    if (n < 1 || n > plain.length - 12) throw new IOException("Invalid avatar length");
    byte[] avc = in.readNBytes(n);
    int j = in.readInt();
    if (j < 1 || j > 1 << 20 || j != in.available())
      throw new IOException("Invalid expression length");
    try {
      var a = AvatarArchive.read(avc, 128L << 20);
      if (!a.avatar().id.equals(expectedAvatar)) throw new IOException("Avatar identity mismatch");
      var displayImages = new java.util.HashMap<>(a.images());
      displayImages.remove("preview.png");
      a = new AvatarArchive(a.avatar(), java.util.Map.copyOf(displayImages), a.expressions());
      a = RemoteAvatarLimits.prepare(a);
      var expressions =
          AvatarArchive.GSON.fromJson(
              new String(in.readNBytes(j), StandardCharsets.UTF_8), ExpressionConfig.class);
      expressions.validate(a.avatar());
      return new AvatarArchive(a.avatar(), a.images(), expressions);
    } finally {
      java.util.Arrays.fill(avc, (byte) 0);
    }
  }

  private ShareBundle() {}
}

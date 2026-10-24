package dev.avatarcraft.multiplayer;

import java.util.UUID;

/** Untrusted wire data. Ownership always comes from the authenticated connection, never peer. */
public record ShareMessage(
    int op, UUID peer, UUID transfer, int sequence, int length, String text, byte[] data) {
  public static final int HELLO = 0,
      READY = 1,
      BEGIN = 2,
      CHUNK = 3,
      COMMIT = 4,
      OFFER = 5,
      SELECT = 6,
      CLEAR = 7,
      REQUEST = 8,
      KEY_REQUEST = 9,
      KEY = 10,
      FINISH = 11,
      ACK = 12,
      ERROR = 13;
  public static final int CHUNK_BYTES = 16384, MAX_BYTES = 64 << 20;
  public static final UUID NONE = new UUID(0, 0);

  public ShareMessage {
    if (op < 0
        || op > ERROR
        || peer == null
        || transfer == null
        || text == null
        || text.length() > 2048
        || data == null
        || data.length > CHUNK_BYTES) throw new IllegalArgumentException("Invalid avatar packet");
  }

  public static ShareMessage control(int op, UUID peer, UUID transfer) {
    return new ShareMessage(op, peer, transfer, 0, 0, "", new byte[0]);
  }
}

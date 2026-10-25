package dev.avatarcraft.player.multiplayer;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Sequential bounded receiver. Its file contains ciphertext exclusively. */
public final class CipherTransfer implements AutoCloseable {
  public final Path path;
  private final int expected;
  private final String hash;
  private final OutputStream output;
  private final MessageDigest digest;
  private int received, sequence;
  private boolean finished;

  public CipherTransfer(Path path, int size, String hash) throws IOException {
    if (size < 32 || size > ShareMessage.MAX_BYTES || !hash.matches("[a-f0-9]{64}"))
      throw new IOException("Invalid transfer");
    this.path = path;
    expected = size;
    this.hash = hash;
    Files.createDirectories(path.getParent());
    output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW);
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (GeneralSecurityException e) {
      throw new AssertionError(e);
    }
  }

  public void append(int index, byte[] bytes) throws IOException {
    if (finished
        || index != sequence
        || bytes.length != Math.min(ShareMessage.CHUNK_BYTES, expected - received)
        || bytes.length == 0) throw new IOException("Invalid chunk sequence or size");
    output.write(bytes);
    digest.update(bytes);
    received += bytes.length;
    sequence++;
  }

  public void finish() throws IOException {
    if (finished || received != expected) throw new IOException("Incomplete transfer");
    output.close();
    if (!HexFormat.of().formatHex(digest.digest()).equals(hash))
      throw new IOException("Ciphertext hash mismatch");
    finished = true;
  }

  public boolean finished() {
    return finished;
  }

  public void close() throws IOException {
    output.close();
    Files.deleteIfExists(path);
  }
}

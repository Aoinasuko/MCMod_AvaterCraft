package dev.avatarcraft.multiplayer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Authenticated ciphertext and recipient-bound key envelopes; not an anti-extraction DRM. */
public final class ShareCrypto {
  private static final SecureRandom RANDOM = new SecureRandom();

  public static KeyPair identity() throws GeneralSecurityException {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  public static PublicKey publicKey(byte[] bytes) throws GeneralSecurityException {
    if (bytes.length > 512) throw new GeneralSecurityException("Invalid public key size");
    var key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
    if (!(((java.security.interfaces.RSAPublicKey) key).getModulus().bitLength() == 2048)
        || !((java.security.interfaces.RSAPublicKey) key)
            .getPublicExponent()
            .equals(java.math.BigInteger.valueOf(65537)))
      throw new GeneralSecurityException("Invalid RSA key");
    return key;
  }

  public static byte[] random(int size) {
    var result = new byte[size];
    RANDOM.nextBytes(result);
    return result;
  }

  public static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (GeneralSecurityException e) {
      throw new AssertionError(e);
    }
  }

  public static byte[] aad(UUID owner, UUID transfer, String avatar) {
    return ("avatarcraft-private-v1:" + owner + ":" + transfer + ":" + avatar)
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] label(UUID owner, UUID transfer, UUID recipient) {
    return aad(owner, transfer, recipient.toString());
  }

  public static byte[] encrypt(byte[] plain, byte[] key, byte[] nonce, byte[] aad)
      throws GeneralSecurityException {
    return aes(Cipher.ENCRYPT_MODE, plain, key, nonce, aad);
  }

  public static byte[] decrypt(byte[] cipher, byte[] key, byte[] nonce, byte[] aad)
      throws GeneralSecurityException {
    return aes(Cipher.DECRYPT_MODE, cipher, key, nonce, aad);
  }

  private static byte[] aes(int mode, byte[] input, byte[] key, byte[] nonce, byte[] aad)
      throws GeneralSecurityException {
    if (key.length != 32 || nonce.length != 12)
      throw new GeneralSecurityException("Invalid AES parameters");
    var cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
    cipher.updateAAD(aad);
    return cipher.doFinal(input);
  }

  public static byte[] wrap(
      byte[] key, PublicKey recipient, UUID owner, UUID transfer, UUID receiver)
      throws GeneralSecurityException {
    var cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
    cipher.init(Cipher.ENCRYPT_MODE, recipient, oaep(label(owner, transfer, receiver)));
    return cipher.doFinal(key);
  }

  public static byte[] unwrap(
      byte[] bytes, PrivateKey key, UUID owner, UUID transfer, UUID receiver)
      throws GeneralSecurityException {
    if (bytes.length != 256) throw new GeneralSecurityException("Invalid key envelope");
    var cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
    cipher.init(Cipher.DECRYPT_MODE, key, oaep(label(owner, transfer, receiver)));
    return cipher.doFinal(bytes);
  }

  private static OAEPParameterSpec oaep(byte[] label) {
    return new OAEPParameterSpec(
        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, new PSource.PSpecified(label));
  }

  private ShareCrypto() {}
}

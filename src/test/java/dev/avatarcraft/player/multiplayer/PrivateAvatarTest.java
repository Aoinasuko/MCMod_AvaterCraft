package dev.avatarcraft.player.multiplayer;

import static dev.avatarcraft.player.multiplayer.ShareMessage.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.avatarcraft.avatar.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrivateAvatarTest {
  @TempDir Path root;
  static final Path MODEL = Path.of("src/main/resources/assets/bep_3davater_loader/avatars/tortobu.avc");

  @Test
  void gcmRejectsModifiedDataNonceAndOwnerBinding() throws Exception {
    byte[] key = ShareCrypto.random(32),
        nonce = ShareCrypto.random(12),
        plain = "private avatar model".getBytes();
    UUID owner = UUID.randomUUID(), transfer = UUID.randomUUID();
    byte[] aad = ShareCrypto.aad(owner, transfer, "avatar");
    byte[] cipher = ShareCrypto.encrypt(plain, key, nonce, aad);
    assertArrayEquals(plain, ShareCrypto.decrypt(cipher, key, nonce, aad));
    byte[] changed = cipher.clone();
    changed[0] ^= 1;
    assertThrows(
        GeneralSecurityException.class, () -> ShareCrypto.decrypt(changed, key, nonce, aad));
    assertThrows(
        GeneralSecurityException.class,
        () -> ShareCrypto.decrypt(cipher, key, ShareCrypto.random(12), aad));
    assertThrows(
        GeneralSecurityException.class,
        () ->
            ShareCrypto.decrypt(
                cipher, key, nonce, ShareCrypto.aad(UUID.randomUUID(), transfer, "avatar")));
  }

  @Test
  void wrappedKeysAreRestrictedToTheRecipientAndTransfer() throws Exception {
    var identity = ShareCrypto.identity();
    var other = ShareCrypto.identity();
    byte[] key = ShareCrypto.random(32);
    UUID owner = UUID.randomUUID(), transfer = UUID.randomUUID(), recipient = UUID.randomUUID();
    byte[] wrapped =
        ShareCrypto.wrap(
            key,
            ShareCrypto.publicKey(identity.getPublic().getEncoded()),
            owner,
            transfer,
            recipient);
    assertArrayEquals(
        key, ShareCrypto.unwrap(wrapped, identity.getPrivate(), owner, transfer, recipient));
    assertThrows(
        GeneralSecurityException.class,
        () -> ShareCrypto.unwrap(wrapped, other.getPrivate(), owner, transfer, recipient));
    assertThrows(
        GeneralSecurityException.class,
        () ->
            ShareCrypto.unwrap(wrapped, identity.getPrivate(), owner, transfer, UUID.randomUUID()));
    assertThrows(
        GeneralSecurityException.class,
        () ->
            ShareCrypto.unwrap(
                wrapped, identity.getPrivate(), owner, UUID.randomUUID(), recipient));
    assertThrows(GeneralSecurityException.class, () -> ShareCrypto.publicKey(new byte[1024]));
  }

  @Test
  void ciphertextReceiverRejectsReorderingOverflowHashChangesAndCleansUp() throws Exception {
    byte[] bytes = ShareCrypto.random(CHUNK_BYTES + 7);
    Path path = root.resolve("cipher.enc");
    try (var receiver = new CipherTransfer(path, bytes.length, ShareCrypto.hash(bytes))) {
      assertThrows(IOException.class, () -> receiver.append(1, Arrays.copyOf(bytes, CHUNK_BYTES)));
      assertThrows(IOException.class, () -> receiver.append(0, new byte[1]));
      receiver.append(0, Arrays.copyOf(bytes, CHUNK_BYTES));
      assertThrows(IOException.class, receiver::finish);
      receiver.append(1, Arrays.copyOfRange(bytes, CHUNK_BYTES, bytes.length));
      receiver.finish();
      assertArrayEquals(bytes, Files.readAllBytes(path));
      assertThrows(IOException.class, () -> receiver.append(2, new byte[1]));
    }
    assertFalse(Files.exists(path));
    try (var receiver = new CipherTransfer(path, bytes.length, "0".repeat(64))) {
      receiver.append(0, Arrays.copyOf(bytes, CHUNK_BYTES));
      receiver.append(1, Arrays.copyOfRange(bytes, CHUNK_BYTES, bytes.length));
      assertThrows(IOException.class, receiver::finish);
    }
  }

  @Test
  void memoryReaderRejectsUnsafePathsExpandedDataAndWrongAvatar() throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("../auth.json"));
      zip.write(new byte[32]);
      zip.closeEntry();
    }
    assertThrows(IOException.class, () -> AvatarArchive.read(bytes.toByteArray(), 1024));
    bytes.reset();
    try (var zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("huge.bin"));
      zip.write(new byte[4096]);
      zip.closeEntry();
    }
    assertThrows(IOException.class, () -> AvatarArchive.read(bytes.toByteArray(), 512));
    var avatar = AvatarArchive.read(MODEL);
    byte[] bundle = ShareBundle.encode(Files.readAllBytes(MODEL), avatar.expressions());
    assertThrows(IOException.class, () -> ShareBundle.decode(bundle, "wrong-avatar"));
    assertEquals(avatar.avatar().id, ShareBundle.decode(bundle, avatar.avatar().id).avatar().id);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ShareManifest(
                "../bad", "0".repeat(64), Base64.getEncoder().encodeToString(new byte[12]), 32));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ShareMessage(CHUNK, NONE, NONE, 0, 0, "", new byte[CHUNK_BYTES + 1]));
  }

  private final class Loop implements AutoCloseable {
    final ArrayDeque<Runnable> wire = new ArrayDeque<>();
    final Map<UUID, PrivateAvatarPeer> clients = new LinkedHashMap<>();
    final Map<UUID, UUID> connections = new HashMap<>();
    final Map<UUID, Map<UUID, AvatarArchive>> visible = new HashMap<>();
    final ArrayList<PrivateAvatarHub.Delivery> sent = new ArrayList<>();
    final PrivateAvatarHub hub =
        new PrivateAvatarHub(
            root.resolve("server"),
            d -> {
              sent.add(d);
              wire.add(
                  () -> {
                    var client = clients.get(d.recipient());
                    if (client != null) client.receive(d.message());
                  });
            });

    Loop() throws Exception {}

    UUID add() throws Exception {
      UUID id = UUID.randomUUID(), connection = UUID.randomUUID();
      connections.put(id, connection);
      visible.put(id, new HashMap<>());
      var peer =
          new PrivateAvatarPeer(
              root.resolve(id.toString()),
              m -> wire.add(() -> hub.receive(id, connection, m)),
              (owner, archive) -> {
                if (archive == null) visible.get(id).remove(owner);
                else visible.get(id).put(owner, archive);
              },
              text -> {});
      clients.put(id, peer);
      hub.join(id, connection, true);
      drain();
      return id;
    }

    void drain() {
      for (int i = 0; !wire.isEmpty(); i++) {
        if (i > 10000) throw new AssertionError("Unbounded packet loop");
        wire.removeFirst().run();
      }
    }

    void step() {
      for (var c : clients.values()) c.tick();
      drain();
      hub.tick();
      drain();
    }

    void until(BooleanSupplier done) {
      for (int i = 0; i < 600 && !done.getAsBoolean(); i++) step();
      assertTrue(done.getAsBoolean(), "Transfer did not finish");
    }

    UUID transfer(UUID owner) {
      return sent.stream()
          .map(PrivateAvatarHub.Delivery::message)
          .filter(m -> m.op() == OFFER && m.peer().equals(owner))
          .reduce((a, b) -> b)
          .orElseThrow()
          .transfer();
    }

    public void close() throws Exception {
      for (var c : clients.values()) c.close();
      hub.close();
    }
  }

  @Test
  void twoClientsSharePrivatelyAndPendingOrClearedModelsStayUnavailable() throws Exception {
    try (var loop = new Loop()) {
      UUID owner = loop.add(), viewer = loop.add();
      var avatar = AvatarArchive.read(MODEL);
      var config = avatar.expressions().copy();
      config.blinkMinSeconds = 3;
      config.blinkMaxSeconds = 4;
      loop.clients.get(viewer).wanted(List.of(owner));
      loop.clients.get(owner).publish(Files.readAllBytes(MODEL), config, avatar.avatar().id);
      loop.step();
      assertTrue(loop.visible.get(viewer).isEmpty(), "Downloading must preserve vanilla fallback");
      loop.until(() -> loop.visible.get(viewer).containsKey(owner));
      assertEquals(3, loop.visible.get(viewer).get(owner).expressions().blinkMinSeconds);
      assertTrue(
          loop.visible.get(owner).isEmpty(),
          "Received data never becomes the owner's selected local model");
      try (var files = Files.walk(root)) {
        assertTrue(
            files.filter(Files::isRegularFile).allMatch(p -> p.toString().endsWith(".enc")),
            "Only ciphertext cache files are created");
      }
      try (var files = Files.list(loop.hub.cacheRoot())) {
        for (var file : files.toList())
          assertFalse(Arrays.equals(Files.readAllBytes(file), Files.readAllBytes(MODEL)));
      }
      loop.clients.get(owner).publish(null, null, "");
      loop.drain();
      assertTrue(loop.visible.get(viewer).isEmpty());
      assertEquals(0, loop.hub.reservedBytes());
      try (var files = Files.walk(root)) {
        assertEquals(0, files.filter(Files::isRegularFile).count());
      }
    }
  }

  @Test
  void serverRejectsStolenSelectionAndSpoofedOwnersAndStaleConnections() throws Exception {
    try (var loop = new Loop()) {
      UUID owner = loop.add(), attacker = loop.add();
      var avatar = AvatarArchive.read(MODEL);
      loop.clients
          .get(owner)
          .publish(Files.readAllBytes(MODEL), avatar.expressions(), avatar.avatar().id);
      loop.until(
          () ->
              loop.hub.reservedBytes() > 0
                  && loop.sent.stream().anyMatch(d -> d.message().op() == OFFER));
      UUID transfer = loop.transfer(owner);
      loop.sent.clear();
      loop.hub.receive(attacker, loop.connections.get(attacker), control(SELECT, owner, transfer));
      loop.drain();
      assertTrue(loop.sent.stream().noneMatch(d -> d.message().op() == SELECT));
      assertTrue(loop.sent.stream().anyMatch(d -> d.message().op() == ERROR));
      loop.sent.clear();
      loop.hub.receive(owner, UUID.randomUUID(), control(CLEAR, owner, NONE));
      assertTrue(loop.hub.reservedBytes() > 0);
      assertTrue(loop.sent.isEmpty());
      byte[] cipher = ShareCrypto.random(32);
      UUID forged = UUID.randomUUID();
      var manifest =
          new ShareManifest(
              "avatar",
              ShareCrypto.hash(cipher),
              Base64.getEncoder().encodeToString(new byte[12]),
              cipher.length);
      loop.hub.receive(
          attacker,
          loop.connections.get(attacker),
          new ShareMessage(BEGIN, owner, forged, 0, 32, manifest.json(), new byte[0]));
      loop.hub.receive(
          attacker,
          loop.connections.get(attacker),
          new ShareMessage(CHUNK, owner, forged, 0, 0, "", cipher));
      loop.hub.receive(attacker, loop.connections.get(attacker), control(COMMIT, owner, forged));
      loop.drain();
      assertTrue(
          loop.sent.stream()
              .filter(d -> d.message().op() == OFFER && d.message().transfer().equals(forged))
              .allMatch(d -> d.message().peer().equals(attacker)));
    }
  }

  @Test
  void lateJoinersDownloadAndLogoutInvalidatesViewsAndKeys() throws Exception {
    try (var loop = new Loop()) {
      UUID owner = loop.add();
      var avatar = AvatarArchive.read(MODEL);
      loop.clients
          .get(owner)
          .publish(Files.readAllBytes(MODEL), avatar.expressions(), avatar.avatar().id);
      loop.until(() -> loop.sent.stream().anyMatch(d -> d.message().op() == OFFER));
      UUID viewer = loop.add();
      loop.clients.get(viewer).wanted(List.of(owner));
      loop.until(() -> loop.visible.get(viewer).containsKey(owner));
      loop.hub.leave(owner);
      loop.drain();
      assertTrue(loop.visible.get(viewer).isEmpty());
      assertEquals(0, loop.hub.reservedBytes());
    }
  }

  @Test
  void corruptedDownloadsNeverPublishAnArchive() throws Exception {
    var out = new ArrayList<ShareMessage>();
    var rendered = new ArrayList<AvatarArchive>();
    try (var peer =
        new PrivateAvatarPeer(
            root,
            out::add,
            (id, a) -> {
              if (a != null) rendered.add(a);
            },
            text -> {})) {
      UUID me = UUID.randomUUID(), owner = UUID.randomUUID(), id = UUID.randomUUID();
      peer.receive(control(HELLO, me, UUID.randomUUID()));
      byte[] cipher = ShareCrypto.random(32);
      var manifest =
          new ShareManifest(
              "avatar",
              ShareCrypto.hash(cipher),
              Base64.getEncoder().encodeToString(new byte[12]),
              32);
      peer.receive(new ShareMessage(OFFER, owner, id, 0, 32, manifest.json(), new byte[0]));
      peer.receive(control(SELECT, owner, id));
      peer.wanted(List.of(owner));
      peer.tick();
      byte[] changed = cipher.clone();
      changed[0] ^= 1;
      peer.receive(new ShareMessage(CHUNK, owner, id, 0, 0, "", changed));
      peer.receive(control(FINISH, owner, id));
      assertTrue(rendered.isEmpty());
      try (var files = Files.list(peer.cacheRoot())) {
        assertEquals(0, files.count());
      }
    }
  }

  @Test
  void offlineIdentityIsNotAcceptedForPrivateSharing() throws Exception {
    var sent = new ArrayList<PrivateAvatarHub.Delivery>();
    UUID player = UUID.randomUUID(), connection = UUID.randomUUID();
    try (var hub = new PrivateAvatarHub(root, sent::add)) {
      hub.join(player, connection, false);
      assertTrue(sent.stream().anyMatch(d -> d.message().op() == ERROR));
      assertTrue(sent.stream().noneMatch(d -> d.message().op() == HELLO));
      hub.receive(
          player,
          connection,
          new ShareMessage(
              READY, player, NONE, 0, 0, "", ShareCrypto.identity().getPublic().getEncoded()));
      assertEquals(0, hub.reservedBytes());
    }
  }

  @Test
  void remoteImagesAndGeometryHaveBudgetsBeyondCompressedFileSize() throws Exception {
    var archive = AvatarArchive.read(MODEL);
    RemoteAvatarLimits.validate(archive);
    byte[] fakePng = new byte[24];
    java.nio.ByteBuffer.wrap(fakePng).putInt(16, 8192).putInt(20, 8192);
    var oversized =
        new AvatarArchive(
            archive.avatar(), Map.of("oversized.png", fakePng), archive.expressions());
    assertThrows(IOException.class, () -> RemoteAvatarLimits.validate(oversized));
    var original = archive.avatar().materials;
    try {
      archive.avatar().materials = new dev.avatarcraft.avatar.Avatar.Material[33];
      assertThrows(IOException.class, () -> RemoteAvatarLimits.validate(archive));
    } finally {
      archive.avatar().materials = original;
    }
  }

  @Test
  void aFailedDownloadDoesNotDestroyTheViewersOwnPublishedModelKey() throws Exception {
    try (var loop = new Loop()) {
      UUID owner = loop.add(), viewer = loop.add();
      var avatar = AvatarArchive.read(MODEL);
      byte[] model = Files.readAllBytes(MODEL);
      loop.clients.get(owner).publish(model, avatar.expressions(), avatar.avatar().id);
      loop.clients.get(viewer).publish(model, avatar.expressions(), avatar.avatar().id);
      loop.clients.get(viewer).wanted(List.of(owner));
      loop.until(() -> loop.visible.get(viewer).containsKey(owner));
      loop.clients
          .get(viewer)
          .receive(
              new ShareMessage(
                  ERROR, viewer, loop.transfer(owner), REQUEST, 0, "cancelled", new byte[0]));
      UUID late = loop.add();
      loop.clients.get(late).wanted(List.of(viewer));
      loop.until(() -> loop.visible.get(late).containsKey(viewer));
    }
  }

  @Test
  void reservationsAreBoundedAndAbandonedUploadsExpire() throws Exception {
    byte[] publicKey = ShareCrypto.identity().getPublic().getEncoded();
    try (var hub = new PrivateAvatarHub(root, d -> {})) {
      var manifest =
          new ShareManifest(
              "avatar",
              "0".repeat(64),
              Base64.getEncoder().encodeToString(new byte[12]),
              MAX_BYTES);
      for (int i = 0; i < 9; i++) {
        UUID player = UUID.randomUUID(), connection = UUID.randomUUID();
        hub.join(player, connection, true);
        hub.receive(player, connection, new ShareMessage(READY, player, NONE, 0, 0, "", publicKey));
        hub.receive(
            player,
            connection,
            new ShareMessage(
                BEGIN, player, UUID.randomUUID(), 0, MAX_BYTES, manifest.json(), new byte[0]));
      }
      assertEquals(512L << 20, hub.reservedBytes());
      try (var files = Files.list(hub.cacheRoot())) {
        assertEquals(8, files.count());
      }
      for (int i = 0; i < 12001; i++) hub.tick();
      assertEquals(0, hub.reservedBytes());
      try (var files = Files.list(hub.cacheRoot())) {
        assertEquals(0, files.count());
      }
    }
  }

  @Test
  void highResolutionImagesAreAdaptedForDisplayWithoutChangingSourceData() throws Exception {
    var original = AvatarArchive.read(MODEL);
    var images = new HashMap<>(original.images());
    String texture = original.avatar().materials[0].texture("_MainTex");
    byte[] source = images.get(texture);
    var properties = original.avatar().materials[0].properties.deepCopy();
    try {
      for (int i = 0; i < 4; i++) {
        String name = "textures/extra" + i + ".png";
        images.put(name, source);
        var prop = new com.google.gson.JsonObject();
        prop.addProperty("texture", name);
        original.avatar().materials[0].properties.add("_Extra" + i, prop);
      }
      var archive = new AvatarArchive(original.avatar(), images, original.expressions());
      assertThrows(IOException.class, () -> RemoteAvatarLimits.validate(archive));
      var display = RemoteAvatarLimits.prepare(archive);
      RemoteAvatarLimits.validate(display);
      assertEquals(2048, java.nio.ByteBuffer.wrap(source).getInt(16));
      assertTrue(java.nio.ByteBuffer.wrap(display.images().get(texture)).getInt(16) < 2048);
      assertArrayEquals(source, images.get(texture));
    } finally {
      original.avatar().materials[0].properties = properties;
    }
  }

  @Test
  void configuredExternalAvatarCanBeRestoredForPrivateDisplay() throws Exception {
    String path = System.getenv("AVATARCRAFT_TEST_AVATAR");
    org.junit.jupiter.api.Assumptions.assumeTrue(path != null && !path.isBlank());
    var source = AvatarArchive.read(Path.of(path));
    byte[] bytes = Files.readAllBytes(Path.of(path));
    var display =
        ShareBundle.decode(ShareBundle.encode(bytes, source.expressions()), source.avatar().id);
    RemoteAvatarLimits.validate(display);
    assertEquals(source.avatar().id, display.avatar().id);
    assertEquals(source.avatar().meshes.length, display.avatar().meshes.length);
  }

  @Test
  void staleKeyRepliesCannotOverrideAReplacementDownload() throws Exception {
    var sent = new ArrayList<PrivateAvatarHub.Delivery>();
    try (var hub = new PrivateAvatarHub(root, sent::add)) {
      UUID owner = UUID.randomUUID(),
          viewer = UUID.randomUUID(),
          a = UUID.randomUUID(),
          b = UUID.randomUUID();
      byte[] pub = ShareCrypto.identity().getPublic().getEncoded();
      hub.join(owner, a, true);
      hub.join(viewer, b, true);
      hub.receive(owner, a, new ShareMessage(READY, owner, NONE, 0, 0, "", pub));
      hub.receive(viewer, b, new ShareMessage(READY, viewer, NONE, 0, 0, "", pub));
      UUID id = UUID.randomUUID();
      byte[] cipher = ShareCrypto.random(32);
      var manifest =
          new ShareManifest(
              "avatar",
              ShareCrypto.hash(cipher),
              Base64.getEncoder().encodeToString(new byte[12]),
              32);
      hub.receive(
          owner, a, new ShareMessage(BEGIN, owner, id, 0, 32, manifest.json(), new byte[0]));
      hub.receive(owner, a, new ShareMessage(CHUNK, owner, id, 0, 0, "", cipher));
      hub.receive(owner, a, control(COMMIT, owner, id));
      hub.receive(viewer, b, control(REQUEST, owner, id));
      int challenge =
          sent.stream()
              .map(PrivateAvatarHub.Delivery::message)
              .filter(m -> m.op() == KEY_REQUEST)
              .reduce((x, y) -> y)
              .orElseThrow()
              .sequence();
      sent.clear();
      hub.receive(owner, a, new ShareMessage(KEY, viewer, id, challenge ^ 1, 0, "", new byte[256]));
      assertTrue(sent.stream().noneMatch(d -> d.message().op() == KEY));
      hub.receive(owner, a, new ShareMessage(KEY, viewer, id, challenge, 0, "", new byte[256]));
      assertEquals(1, sent.stream().filter(d -> d.message().op() == KEY).count());
    }
  }
}

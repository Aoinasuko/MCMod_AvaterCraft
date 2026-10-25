package dev.avatarcraft.player.multiplayer;

import static dev.avatarcraft.player.multiplayer.ShareMessage.*;

import dev.avatarcraft.avatar.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.*;

/** Single-worker client protocol. No local-library path ever points at a received avatar. */
public final class PrivateAvatarPeer implements AutoCloseable {
  private record Offer(UUID owner, UUID id, ShareManifest manifest) {}

  private static final class Publication {
    final UUID id;
    final byte[] key;
    final ShareManifest manifest;
    byte[] cipher;
    int offset, sequence;

    Publication(UUID id, byte[] key, byte[] cipher, ShareManifest manifest) {
      this.id = id;
      this.key = key;
      this.cipher = cipher;
      this.manifest = manifest;
    }

    void destroy() {
      Arrays.fill(key, (byte) 0);
      if (cipher != null) Arrays.fill(cipher, (byte) 0);
      cipher = null;
    }
  }

  private static final class Incoming {
    final Offer offer;
    final CipherTransfer receiver;
    byte[] key;

    Incoming(Offer offer, CipherTransfer receiver) {
      this.offer = offer;
      this.receiver = receiver;
    }

    void destroyKey() {
      if (key != null) Arrays.fill(key, (byte) 0);
      key = null;
    }
  }

  private final Path root;
  private final Consumer<ShareMessage> send;
  private final BiConsumer<UUID, AvatarArchive> render;
  private final Consumer<String> status;
  private final KeyPair identity;
  private UUID self;
  private Publication publication;
  private Incoming incoming;
  private long tick, incomingTick;
  private final Map<UUID, Offer> offers = new HashMap<>();
  private final Map<UUID, UUID> selected = new HashMap<>(), restored = new HashMap<>();
  private final Map<UUID, CipherTransfer> caches = new HashMap<>();
  private final Set<UUID> failed = new HashSet<>();
  private List<UUID> wanted = List.of();

  public PrivateAvatarPeer(
      Path parent,
      Consumer<ShareMessage> send,
      BiConsumer<UUID, AvatarArchive> render,
      Consumer<String> status)
      throws IOException, GeneralSecurityException {
    Files.createDirectories(parent);
    root = Files.createTempDirectory(parent, "connection-");
    this.send = send;
    this.render = render;
    this.status = status;
    identity = ShareCrypto.identity();
  }

  public void receive(ShareMessage m) {
    try {
      if (m.op() == HELLO) {
        if (self != null) return;
        self = m.peer();
        send.accept(
            new ShareMessage(READY, self, NONE, 0, 0, "", identity.getPublic().getEncoded()));
        status.accept("非公開アバター同期に接続しました");
        return;
      }
      if (self == null) return;
      switch (m.op()) {
        case OFFER -> {
          var manifest = ShareManifest.parse(m.text());
          if (m.length() != manifest.bytes()
              || (offers.size() >= 128 && !offers.containsKey(m.transfer())))
            throw new IOException("Invalid offer");
          offers.put(m.transfer(), new Offer(m.peer(), m.transfer(), manifest));
        }
        case SELECT -> {
          if (!m.transfer().equals(selected.get(m.peer()))) clearRemote(m.peer());
          selected.put(m.peer(), m.transfer());
        }
        case CLEAR -> {
          clearRemote(m.peer());
          selected.remove(m.peer());
          offers.entrySet().removeIf(e -> e.getValue().owner.equals(m.peer()));
        }
        case KEY_REQUEST -> {
          if (publication == null || !publication.id.equals(m.transfer())) return;
          byte[] wrapped =
              ShareCrypto.wrap(
                  publication.key, ShareCrypto.publicKey(m.data()), self, publication.id, m.peer());
          send.accept(
              new ShareMessage(KEY, m.peer(), publication.id, m.sequence(), 0, "", wrapped));
        }
        case KEY -> {
          if (!matches(m)) return;
          incoming.key =
              ShareCrypto.unwrap(m.data(), identity.getPrivate(), m.peer(), m.transfer(), self);
          restore();
        }
        case CHUNK -> {
          if (matches(m)) {
            incoming.receiver.append(m.sequence(), m.data());
            incomingTick = tick;
          }
        }
        case FINISH -> {
          if (matches(m)) {
            incoming.receiver.finish();
            restore();
          }
        }
        case ERROR -> {
          if (publication != null
              && publication.id.equals(m.transfer())
              && (m.sequence() == BEGIN || m.sequence() == CHUNK || m.sequence() == COMMIT)) {
            publication.destroy();
            publication = null;
          }
          if (incoming != null && incoming.offer.id.equals(m.transfer())) failIncoming();
          status.accept("アバター共有の要求が拒否されました。対象モデルは通常表示になります");
        }
        default -> throw new IOException("Invalid packet direction");
      }
    } catch (Exception e) {
      failIncoming();
      status.accept("アバターを検証できません。通常モデルを表示します");
    }
  }

  private boolean matches(ShareMessage m) {
    return incoming != null
        && incoming.offer.owner.equals(m.peer())
        && incoming.offer.id.equals(m.transfer());
  }

  public void publish(byte[] avc, ExpressionConfig config, String avatar)
      throws IOException, GeneralSecurityException {
    if (self == null) return;
    if (publication != null) publication.destroy();
    publication = null;
    if (avc == null) {
      send.accept(control(CLEAR, self, NONE));
      status.accept("通常モデルを同期しました");
      return;
    }
    UUID id = UUID.randomUUID();
    byte[] key = ShareCrypto.random(32), nonce = ShareCrypto.random(12);
    byte[] plain = ShareBundle.encode(avc, config), cipher;
    try {
      cipher = ShareCrypto.encrypt(plain, key, nonce, ShareCrypto.aad(self, id, avatar));
    } finally {
      Arrays.fill(plain, (byte) 0);
    }
    var manifest =
        new ShareManifest(
            avatar,
            ShareCrypto.hash(cipher),
            Base64.getEncoder().encodeToString(nonce),
            cipher.length);
    publication = new Publication(id, key, cipher, manifest);
    send.accept(new ShareMessage(BEGIN, self, id, 0, cipher.length, manifest.json(), new byte[0]));
    status.accept("暗号化したアバターを送信しています");
  }

  public void wanted(List<UUID> players) {
    wanted = players.stream().filter(id -> !id.equals(self)).distinct().limit(4).toList();
    for (var id : new ArrayList<>(restored.keySet())) if (!wanted.contains(id)) clearRemote(id);
    if (incoming != null && !wanted.contains(incoming.offer.owner))
      clearRemote(incoming.offer.owner);
  }

  public void tick() {
    tick++;
    if (publication != null && publication.cipher != null) {
      for (int j = 0; j < 2; j++) {
        var p = publication;
        if (p.offset == p.cipher.length) {
          send.accept(control(COMMIT, self, p.id));
          Arrays.fill(p.cipher, (byte) 0);
          p.cipher = null;
          status.accept("本人専用アバターの送信が完了しました");
          break;
        }
        int count = Math.min(CHUNK_BYTES, p.cipher.length - p.offset);
        send.accept(
            new ShareMessage(
                CHUNK,
                self,
                p.id,
                p.sequence++,
                0,
                "",
                Arrays.copyOfRange(p.cipher, p.offset, p.offset + count)));
        p.offset += count;
      }
    }
    if (incoming != null && tick - incomingTick > 12000) failIncoming();
    if (self == null || incoming != null) return;
    for (var owner : wanted) {
      UUID id = selected.get(owner);
      var offer = offers.get(id);
      if (offer == null
          || !offer.owner.equals(owner)
          || id.equals(restored.get(owner))
          || failed.contains(id)) continue;
      try {
        var receiver =
            new CipherTransfer(
                root.resolve(id + ".enc"), offer.manifest.bytes(), offer.manifest.sha256());
        incoming = new Incoming(offer, receiver);
        incomingTick = tick;
        send.accept(control(REQUEST, owner, id));
        break;
      } catch (IOException e) {
        failed.add(id);
      }
    }
  }

  private void restore() throws IOException, GeneralSecurityException {
    if (incoming == null || !incoming.receiver.finished() || incoming.key == null) return;
    var in = incoming;
    byte[] cipher = Files.readAllBytes(in.receiver.path);
    byte[] plain =
        ShareCrypto.decrypt(
            cipher,
            in.key,
            in.offer.manifest.nonceBytes(),
            ShareCrypto.aad(in.offer.owner, in.offer.id, in.offer.manifest.avatar()));
    AvatarArchive archive;
    try {
      archive = ShareBundle.decode(plain, in.offer.manifest.avatar());
    } finally {
      Arrays.fill(plain, (byte) 0);
      in.destroyKey();
    }
    caches.put(in.offer.owner, in.receiver);
    restored.put(in.offer.owner, in.offer.id);
    incoming = null;
    send.accept(control(ACK, in.offer.owner, in.offer.id));
    render.accept(in.offer.owner, archive);
  }

  private void failIncoming() {
    if (incoming == null) return;
    failed.add(incoming.offer.id);
    clearRemote(incoming.offer.owner);
  }

  private void clearRemote(UUID owner) {
    if (incoming != null && incoming.offer.owner.equals(owner)) {
      var in = incoming;
      incoming = null;
      in.destroyKey();
      try {
        in.receiver.close();
      } catch (IOException ignored) {
      }
      if (self != null) send.accept(control(ACK, owner, in.offer.id));
    }
    var cache = caches.remove(owner);
    if (cache != null)
      try {
        cache.close();
      } catch (IOException ignored) {
      }
    if (restored.remove(owner) != null) render.accept(owner, null);
  }

  public Path cacheRoot() {
    return root;
  }

  public UUID selectedTransfer(UUID owner) {
    return selected.get(owner);
  }

  public void close() throws IOException {
    if (publication != null) publication.destroy();
    publication = null;
    if (incoming != null) clearRemote(incoming.offer.owner);
    for (var id : new ArrayList<>(caches.keySet())) clearRemote(id);
    offers.clear();
    selected.clear();
    wanted = List.of();
    failed.clear();
    self = null;
    Files.deleteIfExists(root);
  }
}

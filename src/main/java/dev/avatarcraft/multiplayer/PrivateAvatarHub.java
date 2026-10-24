package dev.avatarcraft.multiplayer;

import static dev.avatarcraft.multiplayer.ShareMessage.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Server relay, used on one IO worker. It never receives a plaintext model key. */
public final class PrivateAvatarHub implements AutoCloseable {
  public record Delivery(UUID recipient, UUID connection, ShareMessage message) {}

  private static final class Peer {
    final UUID connection;
    byte[] publicKey;
    int control = 64, bytes = 1 << 20;

    Peer(UUID connection) {
      this.connection = connection;
    }
  }

  private static final class Asset {
    final UUID owner, id;
    final ShareManifest manifest;
    final CipherTransfer receiver;
    long touched;

    Asset(UUID owner, UUID id, ShareManifest manifest, CipherTransfer receiver, long now) {
      this.owner = owner;
      this.id = id;
      this.manifest = manifest;
      this.receiver = receiver;
      touched = now;
    }
  }

  private static final class Download implements AutoCloseable {
    final UUID recipient;
    final Asset asset;
    final InputStream input;
    final int keyRequest = java.nio.ByteBuffer.wrap(ShareCrypto.random(4)).getInt();
    int sequence;
    boolean sent;
    long touched;

    Download(UUID recipient, Asset asset, long now) throws IOException {
      this.recipient = recipient;
      this.asset = asset;
      input = Files.newInputStream(asset.receiver.path);
      touched = now;
    }

    public void close() throws IOException {
      input.close();
    }
  }

  private final UUID session = UUID.randomUUID();
  private final Map<UUID, Peer> peers = new LinkedHashMap<>();
  private final Map<UUID, Asset> assets = new HashMap<>();
  private final Map<UUID, Download> downloads = new LinkedHashMap<>();
  private final Consumer<Delivery> transport;
  private final Path root;
  private long tick, reserved;
  private int cursor;

  public PrivateAvatarHub(Path parent, Consumer<Delivery> transport) throws IOException {
    Files.createDirectories(parent);
    root = Files.createTempDirectory(parent, "session-");
    this.transport = transport;
  }

  public void join(UUID player, UUID connection, boolean authenticated) {
    leave(player);
    peers.put(player, new Peer(connection));
    if (!authenticated) {
      error(player, "非公開アバター共有にはonline-modeの本人認証が必要です");
      peers.remove(player);
      return;
    }
    send(player, new ShareMessage(HELLO, player, session, 0, MAX_BYTES, "", new byte[0]));
  }

  private void send(UUID to, ShareMessage m) {
    var p = peers.get(to);
    if (p != null) transport.accept(new Delivery(to, p.connection, m));
  }

  private void broadcast(ShareMessage m) {
    for (var e : peers.entrySet()) if (e.getValue().publicKey != null) send(e.getKey(), m);
  }

  private void error(UUID p, String text) {
    send(p, new ShareMessage(ERROR, p, NONE, 0, 0, text, new byte[0]));
  }

  private ShareMessage offer(Asset a) {
    return new ShareMessage(
        OFFER, a.owner, a.id, 0, a.manifest.bytes(), a.manifest.json(), new byte[0]);
  }

  public void receive(UUID sender, UUID connection, ShareMessage m) {
    var peer = peers.get(sender);
    if (peer == null || !peer.connection.equals(connection)) return;
    try {
      if (m.op() == CHUNK) {
        if (peer.bytes < m.data().length) throw new IOException("Transfer rate exceeded");
        peer.bytes -= m.data().length;
      } else if (--peer.control < 0) throw new IOException("Control rate exceeded");
      if (m.op() == READY) {
        if (peer.publicKey != null) throw new IOException("Already initialized");
        ShareCrypto.publicKey(m.data());
        peer.publicKey = m.data().clone();
        for (var a : assets.values())
          if (a.receiver.finished()) {
            send(sender, offer(a));
            send(sender, control(SELECT, a.owner, a.id));
          }
        return;
      }
      if (peer.publicKey == null) throw new IOException("Not initialized");
      switch (m.op()) {
        case BEGIN -> {
          var manifest = ShareManifest.parse(m.text());
          if (m.transfer().equals(NONE)
              || assets.containsKey(m.transfer())
              || manifest.bytes() != m.length()) throw new IOException("Invalid upload");
          removeAsset(sender);
          if (reserved + manifest.bytes() > 512L << 20)
            throw new IOException("Server cache quota exceeded");
          var receiver =
              new CipherTransfer(
                  root.resolve(m.transfer() + ".enc"), manifest.bytes(), manifest.sha256());
          assets.put(m.transfer(), new Asset(sender, m.transfer(), manifest, receiver, tick));
          reserved += manifest.bytes();
        }
        case CHUNK -> {
          var asset = owned(sender, m.transfer());
          asset.receiver.append(m.sequence(), m.data());
          asset.touched = tick;
        }
        case COMMIT -> {
          var asset = owned(sender, m.transfer());
          asset.receiver.finish();
          asset.touched = tick;
          broadcast(offer(asset));
          broadcast(control(SELECT, sender, asset.id));
        }
        case SELECT -> {
          var a = owned(sender, m.transfer());
          if (!a.receiver.finished()) throw new IOException("Not ready");
          broadcast(control(SELECT, sender, a.id));
        }
        case CLEAR -> removeAsset(sender);
        case REQUEST -> {
          var a = assets.get(m.transfer());
          if (a == null
              || !a.receiver.finished()
              || a.owner.equals(sender)
              || !peers.containsKey(a.owner)) throw new IOException("Avatar unavailable");
          cancelDownload(sender);
          var download = new Download(sender, a, tick);
          downloads.put(sender, download);
          send(sender, offer(a));
          send(
              a.owner,
              new ShareMessage(
                  KEY_REQUEST, sender, a.id, download.keyRequest, 0, "", peer.publicKey.clone()));
        }
        case KEY -> {
          var a = owned(sender, m.transfer());
          var d = downloads.get(m.peer());
          if (d == null || d.asset != a || d.keyRequest != m.sequence())
            return; // Cancelled or reconnected recipient.
          if (m.data().length != 256) throw new IOException("Invalid key recipient");
          send(m.peer(), new ShareMessage(KEY, sender, a.id, 0, 0, "", m.data()));
        }
        case ACK -> {
          var d = downloads.get(sender);
          if (d != null && d.asset.id.equals(m.transfer())) cancelDownload(sender);
        }
        default -> throw new IOException("Invalid packet direction");
      }
    } catch (Exception e) {
      // Neither keys, avatar plaintext nor exception payloads are logged or returned.
      send(
          sender,
          new ShareMessage(
              ERROR,
              sender,
              m.transfer(),
              m.op(),
              0,
              "アバター共有を完了できません。サイズ・接続・転送順序を確認してください",
              new byte[0]));
      if (m.op() == BEGIN || m.op() == CHUNK || m.op() == COMMIT) removeAsset(sender);
      if (m.op() == REQUEST) cancelDownload(sender);
    }
  }

  private Asset owned(UUID player, UUID transfer) throws IOException {
    var a = assets.get(transfer);
    if (a == null || !a.owner.equals(player)) throw new IOException("Not the owner");
    return a;
  }

  public void tick() {
    tick++;
    for (var p : peers.values()) {
      p.control = Math.min(64, p.control + 2);
      p.bytes = Math.min(1 << 20, p.bytes + CHUNK_BYTES * 2);
    }
    for (var a : new ArrayList<>(assets.values()))
      if (!a.receiver.finished() && tick - a.touched > 12000) removeAsset(a.owner);
    var list = new ArrayList<>(downloads.values());
    int budget = 8;
    if (!list.isEmpty())
      for (int i = 0; i < list.size() && budget > 0; i++) {
        var d = list.get(Math.floorMod(cursor + i, list.size()));
        if (tick - d.touched > 12000) {
          cancelDownload(d.recipient);
          continue;
        }
        if (d.sent) continue;
        try {
          for (int j = 0; j < 2 && budget > 0; j++) {
            byte[] bytes = d.input.readNBytes(CHUNK_BYTES);
            if (bytes.length == 0) {
              d.sent = true;
              d.close();
              send(d.recipient, control(FINISH, d.asset.owner, d.asset.id));
              break;
            }
            send(
                d.recipient,
                new ShareMessage(CHUNK, d.asset.owner, d.asset.id, d.sequence++, 0, "", bytes));
            budget--;
          }
        } catch (IOException e) {
          cancelDownload(d.recipient);
          error(d.recipient, "暗号化キャッシュを読み出せません");
        }
      }
    cursor++;
  }

  private void cancelDownload(UUID peer) {
    var d = downloads.remove(peer);
    if (d != null)
      try {
        d.close();
      } catch (IOException ignored) {
      }
  }

  private void removeAsset(UUID owner) {
    for (var a : new ArrayList<>(assets.values()))
      if (a.owner.equals(owner)) {
        for (var d : new ArrayList<>(downloads.values()))
          if (d.asset == a) cancelDownload(d.recipient);
        assets.remove(a.id);
        reserved -= a.manifest.bytes();
        try {
          a.receiver.close();
        } catch (IOException ignored) {
        }
      }
    broadcast(control(CLEAR, owner, NONE));
  }

  public void leave(UUID player) {
    removeAsset(player);
    cancelDownload(player);
    peers.remove(player);
  }

  public long reservedBytes() {
    return reserved;
  }

  public Path cacheRoot() {
    return root;
  }

  public void close() throws IOException {
    for (var id : new ArrayList<>(peers.keySet())) leave(id);
    Files.deleteIfExists(root);
  }
}

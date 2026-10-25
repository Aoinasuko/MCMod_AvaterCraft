package dev.avatarcraft.player.client;

import dev.avatarcraft.client.*;

import dev.avatarcraft.avatar.*;
import dev.avatarcraft.player.multiplayer.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** Display-only remote sessions, isolated from AvatarLibrary and the local avatar selection. */
public final class ShareClient {
  private static final Map<UUID, AvatarSession> REMOTE = new HashMap<>();
  private static final Set<UUID> ANNOUNCED = new HashSet<>();
  private static final Map<UUID, UUID> ASSIGNED = new HashMap<>();
  private static final AtomicLong REVISION = new AtomicLong();
  private static ShareMessage hello;
  private static volatile State state;
  private static volatile String status = "オンライン同期は接続待ちです";

  private static final class State {
    final ThreadPoolExecutor worker =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256),
            Thread.ofPlatform().daemon(true).name("avatarcraft-private-client").factory());
    final ArrayBlockingQueue<ShareMessage> outgoing = new ArrayBlockingQueue<>(256);
    final ConcurrentLinkedQueue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
    final AtomicBoolean ticking = new AtomicBoolean();
    PrivateAvatarPeer peer;
    AvatarSession lastActive;
    long lastRevision = -1;
    int ticks;

    boolean submit(Runnable action) {
      try {
        worker.execute(
            () -> {
              try {
                action.run();
              } catch (Exception ignored) {
                status = "非公開アバター同期に失敗しました";
              }
            });
        return true;
      } catch (RejectedExecutionException ignored) {
        status = "アバター同期の処理待ちが上限に達しました";
        return false;
      }
    }

    void initialize(ShareMessage m) {
      submit(
          () -> {
            try {
              peer =
                  new PrivateAvatarPeer(
                      AvatarLibrary.ROOT.resolve("remote-cache"),
                      outgoing::offer,
                      (owner, archive) -> {
                        UUID transfer = peer == null ? null : peer.selectedTransfer(owner);
                        callbacks.add(
                            () -> {
                              if (state != this) return;
                              if (archive != null && !Objects.equals(transfer, ASSIGNED.get(owner)))
                                return;
                              var old = REMOTE.remove(owner);
                              if (old != null) old.textures.close();
                              if (archive != null)
                                try {
                                  REMOTE.put(owner, new AvatarSession(archive, true));
                                } catch (Exception ignored) {
                                }
                            });
                      },
                      text -> {
                        if (state == this) status = text;
                      });
              peer.receive(m);
            } catch (Exception ignored) {
              status = "非公開アバター同期を初期化できません";
            }
          });
    }

    void tick() {
      var mc = Minecraft.getInstance();
      for (int i = 0; i < 32; i++) {
        var task = callbacks.poll();
        if (task == null) break;
        task.run();
      }
      var active = AvatarSession.active;
      long revision = REVISION.get();
      if (lastActive != active || lastRevision != revision) {
        lastActive = active;
        lastRevision = revision;
        var entry =
            active == null
                ? null
                : AvatarLibrary.entries().stream()
                    .filter(e -> e.archive() == active.archive)
                    .findFirst()
                    .orElse(null);
        var config = active == null ? null : active.config.copy();
        submit(
            () -> {
              if (peer == null || state != this) return;
              byte[] bytes = null;
              try {
                if (active != null) {
                  if (entry == null
                      || Files.isSymbolicLink(entry.path())
                      || Files.size(entry.path()) > ShareMessage.MAX_BYTES - (1 << 20) - 32)
                    throw new IllegalArgumentException();
                  bytes = Files.readAllBytes(entry.path());
                }
                peer.publish(bytes, config, active == null ? "" : active.archive.avatar().id);
              } catch (Exception ignored) {
                try {
                  peer.publish(null, null, "");
                } catch (Exception ignoredAgain) {
                }
                status = "アバターを共有できません。元ファイルと転送上限を確認してください";
              } finally {
                if (bytes != null) Arrays.fill(bytes, (byte) 0);
              }
            });
      }
      if (++ticks % 20 == 0 && mc.level != null && mc.player != null) {
        var wanted =
            mc.level.players().stream()
                .filter(
                    p ->
                        p != mc.player
                            && ANNOUNCED.contains(p.getUUID())
                            && p.distanceToSqr(mc.player) <= 96 * 96)
                .sorted(Comparator.comparingDouble(p -> p.distanceToSqr(mc.player)))
                .limit(4)
                .map(p -> p.getUUID())
                .toList();
        submit(
            () -> {
              if (peer != null) peer.wanted(wanted);
            });
      }
      if (ticking.compareAndSet(false, true)
          && !submit(
              () -> {
                try {
                  if (peer != null) peer.tick();
                } finally {
                  ticking.set(false);
                }
              })) ticking.set(false);
      var connection = mc.getConnection();
      if (connection != null && connection.hasChannel(ShareNetwork.Up.TYPE))
        for (int i = 0; i < 8; i++) {
          var message = outgoing.poll();
          if (message == null) break;
          ClientPacketDistributor.sendToServer(new ShareNetwork.Up(message));
        }
    }

    void close() {
      worker.getQueue().clear();
      submit(
          () -> {
            if (peer != null)
              try {
                peer.close();
              } catch (Exception ignored) {
              }
            peer = null;
          });
      worker.shutdown();
      outgoing.clear();
      callbacks.clear();
    }
  }

  public static void receive(ShareMessage message) {
    if (message.op() == ShareMessage.HELLO) {
      hello = message;
      return;
    }
    if (message.op() == ShareMessage.SELECT) {
      if (ANNOUNCED.size() >= 128 && !ANNOUNCED.contains(message.peer())) return;
      ANNOUNCED.add(message.peer());
      if (!message.transfer().equals(ASSIGNED.put(message.peer(), message.transfer())))
        failed(message.peer());
    }
    if (message.op() == ShareMessage.CLEAR) {
      ANNOUNCED.remove(message.peer());
      ASSIGNED.remove(message.peer());
      failed(message.peer());
    }
    if (message.op() == ShareMessage.ERROR && state == null) status = "このサーバーでは非公開アバター同期を利用できません";
    if (state != null) {
      var current = state;
      current.submit(
          () -> {
            if (current.peer != null) current.peer.receive(message);
          });
    }
  }

  public static void tick() {
    if (hello != null && Minecraft.getInstance().player != null) {
      var next = hello;
      disconnect();
      hello = null;
      if (!next.peer().equals(Minecraft.getInstance().player.getUUID())) return;
      state = new State();
      state.initialize(next);
    }
    if (state != null) state.tick();
  }

  public static void selectionChanged() {
    REVISION.incrementAndGet();
  }

  public static AvatarSession session(UUID player) {
    return REMOTE.get(player);
  }

  public static void failed(UUID player) {
    var old = REMOTE.remove(player);
    if (old != null) old.textures.close();
  }

  public static String status() {
    return status;
  }

  public static void disconnect() {
    var old = state;
    state = null;
    hello = null;
    if (old != null) old.close();
    for (var session : REMOTE.values()) session.textures.close();
    REMOTE.clear();
    ANNOUNCED.clear();
    ASSIGNED.clear();
    status = "オンライン同期は接続待ちです";
  }

  private ShareClient() {}
}

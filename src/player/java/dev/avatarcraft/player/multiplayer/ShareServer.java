package dev.avatarcraft.player.multiplayer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Side-safe Minecraft adapter: file IO/crypto relay work never blocks the server tick. */
public final class ShareServer {
  private static final Map<MinecraftServer, Service> SERVICES = new IdentityHashMap<>();

  private static final class Service implements AutoCloseable {
    final MinecraftServer server;
    final Map<UUID, UUID> connections = new HashMap<>();
    final Map<UUID, long[]> limits = new HashMap<>();
    final ConcurrentMap<UUID, UUID> assetOwners = new ConcurrentHashMap<>();
    final ArrayBlockingQueue<PrivateAvatarHub.Delivery> outgoing = new ArrayBlockingQueue<>(1024);
    final ThreadPoolExecutor worker =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256),
            Thread.ofPlatform().daemon(true).name("avatarcraft-private-server").factory());
    final AtomicBoolean ticking = new AtomicBoolean();
    PrivateAvatarHub hub;

    Service(MinecraftServer server) {
      this.server = server;
    }

    boolean submit(Consumer<PrivateAvatarHub> action) {
      try {
        worker.execute(
            () -> {
              try {
                if (hub == null)
                  hub =
                      new PrivateAvatarHub(
                          server
                              .getWorldPath(LevelResource.ROOT)
                              .resolve("avatarcraft-private-cache"),
                          delivery -> {
                            var message = delivery.message();
                            if (message.op() == ShareMessage.OFFER)
                              assetOwners.put(message.transfer(), message.peer());
                            if (message.op() == ShareMessage.CLEAR)
                              assetOwners
                                  .entrySet()
                                  .removeIf(e -> e.getValue().equals(message.peer()));
                            outgoing.offer(delivery);
                          });
                action.accept(hub);
              } catch (Exception ignored) {
                System.getLogger("bep_3davater_loader")
                    .log(System.Logger.Level.WARNING, "Private avatar relay operation failed");
              }
            });
        return true;
      } catch (RejectedExecutionException ignored) {
        return false;
      }
    }

    void tick() {
      if (ticking.compareAndSet(false, true))
        if (!submit(
            h -> {
              try {
                h.tick();
              } finally {
                ticking.set(false);
              }
            })) ticking.set(false);
      for (int i = 0; i < 128; i++) {
        var d = outgoing.poll();
        if (d == null) break;
        var p = server.getPlayerList().getPlayer(d.recipient());
        if (p != null
            && d.connection().equals(connections.get(d.recipient()))
            && p.connection.hasChannel(ShareNetwork.Down.TYPE))
          PacketDistributor.sendToPlayer(p, new ShareNetwork.Down(d.message()));
      }
    }

    public void close() {
      connections.clear();
      limits.clear();
      worker.getQueue().clear();
      submit(
          h -> {
            try {
              h.close();
            } catch (Exception ignored) {
            }
          });
      worker.shutdown();
      try {
        if (!worker.awaitTermination(5, TimeUnit.SECONDS)) worker.shutdownNow();
      } catch (InterruptedException e) {
        worker.shutdownNow();
        Thread.currentThread().interrupt();
      }
      outgoing.clear();
      assetOwners.clear();
    }
  }

  public static void install() {
    NeoForge.EVENT_BUS.addListener(
        PlayerEvent.PlayerLoggedInEvent.class,
        e -> {
          if (!(e.getEntity() instanceof ServerPlayer player)
              || !player.connection.hasChannel(ShareNetwork.Down.TYPE)) return;
          var server = player.level().getServer();
          var service = SERVICES.computeIfAbsent(server, Service::new);
          UUID playerId = player.getUUID(), connection = UUID.randomUUID();
          service.connections.put(playerId, connection);
          boolean authenticated =
              server.usesAuthentication() || player.connection.getConnection().isMemoryConnection();
          service.submit(h -> h.join(playerId, connection, authenticated));
        });
    NeoForge.EVENT_BUS.addListener(
        PlayerEvent.PlayerLoggedOutEvent.class,
        e -> {
          if (!(e.getEntity() instanceof ServerPlayer player)) return;
          var service = SERVICES.get(player.level().getServer());
          if (service == null) return;
          UUID id = player.getUUID();
          service.connections.remove(id);
          service.limits.remove(id);
          service.submit(h -> h.leave(id));
        });
    NeoForge.EVENT_BUS.addListener(
        ServerTickEvent.Post.class,
        e -> {
          var service = SERVICES.get(e.getServer());
          if (service != null) service.tick();
        });
    NeoForge.EVENT_BUS.addListener(
        ServerStoppingEvent.class,
        e -> {
          var service = SERVICES.remove(e.getServer());
          if (service != null) service.close();
        });
  }

  static void receive(ServerPlayer player, ShareMessage message) {
    var service = SERVICES.get(player.level().getServer());
    if (service == null) return;
    UUID id = player.getUUID(), connection = service.connections.get(id);
    if (connection == null) return;
    long tick = player.level().getServer().getTickCount();
    var window = service.limits.computeIfAbsent(id, k -> new long[] {tick, 0});
    if (tick - window[0] >= 20 || tick < window[0]) {
      window[0] = tick;
      window[1] = 0;
    }
    if (++window[1] > 128) return;
    if (message.op() == ShareMessage.REQUEST) {
      UUID ownerId = service.assetOwners.get(message.transfer());
      var owner =
          ownerId == null ? null : player.level().getServer().getPlayerList().getPlayer(ownerId);
      if (owner == null
          || owner.level() != player.level()
          || owner.distanceToSqr(player) > 128 * 128) {
        PacketDistributor.sendToPlayer(
            player,
            new ShareNetwork.Down(
                new ShareMessage(
                    ShareMessage.ERROR,
                    id,
                    message.transfer(),
                    ShareMessage.REQUEST,
                    0,
                    "表示するプレイヤーが現在の描画範囲にいません",
                    new byte[0])));
        return;
      }
    }
    service.submit(h -> h.receive(id, connection, message));
  }

  private ShareServer() {}
}

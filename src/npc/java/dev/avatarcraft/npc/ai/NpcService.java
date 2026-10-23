package dev.avatarcraft.npc.ai;

import com.google.gson.JsonObject;
import dev.avatarcraft.npc.CompanionEntity;
import dev.avatarcraft.npc.memory.NpcJournal;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;

/**
 * World-scoped OAuth process, journal IO and rate limits. Never puts credentials in entity data.
 */
public final class NpcService implements AutoCloseable {
  private static final Map<MinecraftServer, NpcService> SERVICES = new WeakHashMap<>();

  public static synchronized NpcService get(MinecraftServer server) {
    return SERVICES.computeIfAbsent(server, NpcService::new);
  }

  public static synchronized void stop(MinecraftServer server) {
    var service = SERVICES.remove(server);
    if (service != null) service.close();
  }

  public final CodexConfig config;
  public final NpcJournal journal;
  private final MinecraftServer server;
  private final java.nio.file.Path workspace;
  private final ExecutorService io =
      Executors.newSingleThreadExecutor(
          Thread.ofVirtual().name("avatarcraft-journal-", 0).factory());
  private final ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor();
  private CompletableFuture<NpcBrain> bridge;
  private volatile NpcBrain connecting;
  private long retryAfter, nextRequest;
  private volatile boolean closed;
  private String error = "";

  private NpcService(MinecraftServer server) {
    this.server = server;
    var configRoot = FMLPaths.CONFIGDIR.get().resolve("avatarcraftnpc");
    CodexConfig loaded;
    try {
      loaded = NpcAiSettings.snapshot();
    } catch (Exception e) {
      loaded = new CodexConfig();
      loaded.enabled = false;
      error = "AI設定を読み込めません";
    }
    config = loaded;
    workspace = configRoot.resolve("codex-workspace").toAbsolutePath();
    journal =
        new NpcJournal(server.getWorldPath(LevelResource.ROOT).resolve("avatarcraftnpc/journal"));
  }

  public synchronized CompletableFuture<NpcBrain> brain() {
    if (closed || !config.enabled)
      return CompletableFuture.failedFuture(new IOException("AIが無効です"));
    if (bridge != null
        && bridge.isDone()
        && !bridge.isCompletedExceptionally()
        && !bridge.getNow(null).connected()) {
      bridge.getNow(null).close();
      bridge = null;
    }
    if (bridge != null && bridge.isCompletedExceptionally()) {
      if (System.currentTimeMillis() < retryAfter) return bridge;
      bridge = null;
    }
    if (bridge == null) {
      bridge =
          CompletableFuture.supplyAsync(
              () -> {
                NpcBrain created = null;
                try {
                  created =
                      config.provider == CodexConfig.Provider.CODEX
                          ? new CodexBridge(config, workspace)
                          : new HttpBrain(config);
                  synchronized (this) {
                    if (closed) {
                      created.close();
                      throw new IOException("Server stopped");
                    }
                    connecting = created;
                  }
                  created.initialize().join();
                  if (closed) throw new IOException("Server stopped");
                  synchronized (this) {
                    error = "";
                  }
                  return created;
                } catch (Exception e) {
                  if (created != null) created.close();
                  synchronized (this) {
                    error = "AIに接続できません。プロバイダー・モデル・認証設定を確認してください";
                    retryAfter = System.currentTimeMillis() + 60000;
                  }
                  throw new CompletionException(e);
                } finally {
                  synchronized (this) {
                    if (connecting == created) connecting = null;
                  }
                }
              },
              connections);
    }
    return bridge;
  }

  public synchronized String status() {
    if (!error.isEmpty()) return error;
    if (!config.enabled) return "AIが無効です";
    return bridge != null && bridge.isDone() && !bridge.isCompletedExceptionally()
        ? bridge.getNow(null).status()
        : "AI接続待ち";
  }

  public boolean requestSlot(long tick) {
    if (tick < nextRequest) return false;
    nextRequest = tick + config.minimumRequestIntervalSeconds * 20L;
    return true;
  }

  public void record(
      CompanionEntity npc, String kind, String text, UUID listener, JsonObject details) {
    if (!server.isSameThread()) {
      server.execute(() -> record(npc, kind, text, listener, details));
      return;
    }
    var owner = npc.ownerId();
    if (owner == null || closed) return;
    var actor = npc.getUUID();
    var name = npc.getName().getString();
    io.execute(
        () -> {
          try {
            journal.append(owner, actor, name, listener, kind, text, details);
          } catch (Exception e) {
            System.getLogger("AvatarCraft NPC")
                .log(System.Logger.Level.ERROR, "Cannot save companion journal", e);
          }
        });
  }

  public void say(CompanionEntity npc, String message, int targetId) {
    String text = message.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").strip();
    if (text.isEmpty()) return;
    if (text.length() > 512) text = text.substring(0, 512);
    var level = (ServerLevel) npc.level();
    var target = targetId >= 0 ? level.getEntity(targetId) : null;
    if (target != null && npc.distanceToSqr(target) > 16 * 16) target = null;
    var peer = target == null ? null : target.getUUID();
    var event = UUID.randomUUID();
    var component = Component.literal("<" + npc.getName().getString() + "> " + text);
    for (var player : level.getPlayers(p -> p.distanceToSqr(npc) <= 32 * 32))
      player.sendSystemMessage(component);
    record(npc, "speech", text, peer, new JsonObject());
    for (var actor :
        level.getEntitiesOfClass(
            CompanionEntity.class, npc.getBoundingBox().inflate(12), n -> n != npc && n.isAlive()))
      if (npc.hasLineOfSight(actor))
        actor.mind().hear(event, npc.getId(), npc.getName().getString(), text);
  }

  public <T> CompletableFuture<T> readJournal(Callable<T> read) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return read.call();
          } catch (Exception e) {
            throw new CompletionException(e);
          }
        },
        io);
  }

  public void close() {
    closed = true;
    var startup = connecting;
    if (startup != null) startup.close();
    if (bridge != null)
      bridge.whenComplete(
          (value, failure) -> {
            if (value != null) value.close();
          });
    io.shutdown();
    connections.shutdownNow();
    try {
      io.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}

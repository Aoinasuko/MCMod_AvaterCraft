package dev.avatarcraft.npc;

import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.neoforged.neoforge.common.world.chunk.TicketController;

/** One server-authorized recall per player; temporary tickets also cover unloaded NPCs. */
public final class NpcRecall {
  public static final TicketController CONTROLLER =
      new TicketController(
          bep_3davater_companion.id("recall"),
          (level, tickets) -> {
            // Requests are transient; a crash/restart must not leave their temporary chunks forced.
            tickets.getEntityTickets().keySet().forEach(tickets::removeAllTickets);
            tickets.getBlockTickets().keySet().forEach(tickets::removeAllTickets);
          });

  private record Pending(
      UUID player, UUID actor, UUID ticket, ServerLevel level, int x, int z, long deadline) {}

  private static final Map<MinecraftServer, Map<UUID, Pending>> PENDING = new WeakHashMap<>();

  public static String request(ServerPlayer player, UUID actor) {
    var server = player.level().getServer();
    var entry = NpcRoster.get(server).owned(player.getUUID(), actor);
    if (entry == null) return "自分のNPCだけを呼び戻せます";
    if (!NpcNetwork.holdingRecallMap(player)) return "呼び戻しの地図を手に持ってください";
    var jobs = PENDING.computeIfAbsent(server, s -> new HashMap<>());
    if (jobs.containsKey(player.getUUID())) return "呼び戻し処理中です";
    var level =
        server.getLevel(
            ResourceKey.create(Registries.DIMENSION, Identifier.parse(entry.dimension())));
    if (level == null) return "NPCのディメンジョンが利用できません";
    UUID ticket = UUID.randomUUID();
    int x = entry.x() >> 4, z = entry.z() >> 4;
    CONTROLLER.forceChunk(level, ticket, x, z, true, true);
    jobs.put(
        player.getUUID(),
        new Pending(
            player.getUUID(), actor, ticket, level, x, z, server.overworld().getGameTime() + 200));
    return "NPCを読み込み、呼び戻しています…";
  }

  public static void tick(MinecraftServer server) {
    var jobs = PENDING.get(server);
    if (jobs == null) return;
    var iterator = jobs.values().iterator();
    while (iterator.hasNext()) {
      var p = iterator.next();
      var player = server.getPlayerList().getPlayer(p.player());
      String result = null;
      boolean success = false;
      var npc = NpcRoster.find(server, p.actor());
      if (player == null || player.hasDisconnected() || !NpcNetwork.holdingRecallMap(player))
        result = "呼び戻しを取り消しました";
      else if (NpcRoster.get(server).owned(player.getUUID(), p.actor()) == null
          || npc != null && !npc.ownedBy(player)) result = "このNPCを呼び戻す権限がありません";
      else if (npc != null && npc.isAlive()) {
        var safe =
            NpcTravel.safePosition(
                player.level(),
                net.minecraft.world.phys.Vec3.atBottomCenterOf(
                    player.blockPosition().relative(player.getDirection(), 2)));
        if (safe == null) result = "近くに安全な呼び戻し先がありません";
        else {
          npc.standBy();
          var moved = NpcTravel.move(npc, player.level(), safe, player.getYRot());
          success = moved != null;
          result = success ? "NPCを呼び戻し、作業と追従を解除して待機させました" : "NPCを移動できませんでした";
          if (success)
            dev.avatarcraft.npc.ai.NpcService.get(server)
                .record(
                    moved, "recall", result, player.getUUID(), new com.google.gson.JsonObject());
        }
      } else if (server.overworld().getGameTime() >= p.deadline())
        result = "NPCを読み込めませんでした。復帰中の場合は少し待って再試行してください";
      if (result != null) {
        release(p);
        iterator.remove();
        if (player != null && !player.hasDisconnected()) {
          NpcNetwork.reply(player, -1, "recall_result", result);
          if (success) NpcNetwork.openRecall(player, 0);
        }
      }
    }
  }

  private static void release(Pending p) {
    CONTROLLER.forceChunk(p.level(), p.ticket(), p.x(), p.z(), false, true);
  }

  public static void clear(MinecraftServer server) {
    var jobs = PENDING.remove(server);
    if (jobs != null) jobs.values().forEach(NpcRecall::release);
  }

  private NpcRecall() {}
}

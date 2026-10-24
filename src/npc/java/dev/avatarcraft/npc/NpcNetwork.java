package dev.avatarcraft.npc;

import com.google.gson.*;
import dev.avatarcraft.avatar.AvatarArchive;
import dev.avatarcraft.npc.ai.NpcService;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** All edits require the owner's currently open, valid menu; journal reads require its item. */
public final class NpcNetwork {
  public record Request(int entity, String action, String data) implements CustomPacketPayload {
    public static final Type<Request> TYPE = new Type<>(NpcMod.id("request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC =
        StreamCodec.of(
            (b, p) -> {
              b.writeVarInt(p.entity);
              b.writeUtf(p.action, 32);
              b.writeUtf(p.data, 32768);
            },
            b -> new Request(b.readVarInt(), b.readUtf(32), b.readUtf(32768)));

    public Type<Request> type() {
      return TYPE;
    }
  }

  public record Response(int entity, String action, String data) implements CustomPacketPayload {
    public static final Type<Response> TYPE = new Type<>(NpcMod.id("response"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Response> CODEC =
        StreamCodec.of(
            (b, p) -> {
              b.writeVarInt(p.entity);
              b.writeUtf(p.action, 32);
              b.writeUtf(p.data, 262144);
            },
            b -> new Response(b.readVarInt(), b.readUtf(32), b.readUtf(262144)));

    public Type<Response> type() {
      return TYPE;
    }
  }

  private static final Map<UUID, Window> LIMITS = new HashMap<>();

  private record Window(long tick, int count) {}

  public static void register(RegisterPayloadHandlersEvent event) {
    var registrar = event.registrar("1");
    registrar.playToServer(Request.TYPE, Request.CODEC, NpcNetwork::handle);
    registrar.playToClient(Response.TYPE, Response.CODEC);
  }

  public static void clear() {
    LIMITS.clear();
  }

  private static void handle(Request request, IPayloadContext context) {
    if (!(context.player() instanceof ServerPlayer player)) return;
    long tick = player.level().getGameTime();
    var old = LIMITS.get(player.getUUID());
    int count = old != null && tick - old.tick < 20 && tick >= old.tick ? old.count + 1 : 1;
    LIMITS.put(player.getUUID(), new Window(count == 1 ? tick : old.tick, count));
    if (count > 8) return;
    try {
      if (request.action.equals("recall_list") && holdingRecallMap(player)) {
        openRecall(player, Integer.parseInt(request.data));
        return;
      }
      if (request.action.equals("recall") && holdingRecallMap(player)) {
        reply(
            player, -1, "recall_result", NpcRecall.request(player, UUID.fromString(request.data)));
        return;
      }
      CompanionEntity npc =
          player.containerMenu instanceof CompanionMenu menu
                  && menu.npc.getId() == request.entity
                  && menu.stillValid(player)
              ? menu.npc
              : null;
      if (request.action.equals("personality") && npc != null) {
        npc.setPersonality(Personality.parse(request.data));
        reply(player, request.entity, "saved", "性格とアバターを保存しました");
      } else if (request.action.equals("instruction") && npc != null) {
        npc.setInstruction(request.data);
        reply(player, request.entity, "saved", request.data.isBlank() ? "作業を停止しました" : "指示を渡しました");
      } else if (request.action.equals("follow_owner") && npc != null) {
        if (!request.data.equals("true") && !request.data.equals("false"))
          throw new IllegalArgumentException();
        npc.setFollowsOwner(Boolean.parseBoolean(request.data));
        reply(
            player,
            request.entity,
            "saved",
            npc.followsOwner() ? "待機中は所有者についていきます" : "待機中の追従を停止しました");
      } else if (request.action.equals("personalities") && npc != null) {
        NpcService.get(player.level().getServer())
            .readJournal(
                () -> {
                  var values = new JsonArray();
                  for (var profile :
                      PersonalityCatalog.load(
                          net.neoforged.fml.loading.FMLPaths.CONFIGDIR
                              .get()
                              .resolve("avatarcraftnpc/personalities")))
                    values.add(JsonParser.parseString(profile.json()));
                  return values.toString();
                })
            .whenComplete(
                (data, error) ->
                    player
                        .level()
                        .getServer()
                        .execute(
                            () -> {
                              if (!player.hasDisconnected()
                                  && player.containerMenu instanceof CompanionMenu menu
                                  && menu.npc.getId() == request.entity
                                  && menu.stillValid(player))
                                reply(
                                    player,
                                    request.entity,
                                    error == null ? "personalities" : "error",
                                    error == null ? data : "性格一覧を読み込めません");
                            }));
      } else if (request.action.equals("journal") && (npc != null || holdingJournal(player))) {
        var query = JsonParser.parseString(request.data).getAsJsonObject();
        UUID actor =
            npc == null ? UUID.fromString(query.get("actor").getAsString()) : npc.getUUID();
        String cursor = query.has("cursor") ? query.get("cursor").getAsString() : "";
        readJournal(player, request.entity, actor, cursor);
      } else if (request.action.equals("actors") && holdingJournal(player)) openJournal(player);
      else reply(player, request.entity, "error", "操作する権限がないか、NPCから離れています");
    } catch (RuntimeException e) {
      reply(player, request.entity, "error", "入力内容を確認してください。JSON・文字数・アバターIDが不正です");
    }
  }

  private static boolean holdingJournal(ServerPlayer player) {
    for (var hand : InteractionHand.values())
      if (player.getItemInHand(hand).is(NpcMod.MEMORY_JOURNAL.get())) return true;
    return false;
  }

  public static boolean holdingRecallMap(ServerPlayer player) {
    for (var hand : InteractionHand.values())
      if (player.getItemInHand(hand).is(NpcMod.RECALL_MAP.get())) return true;
    return false;
  }

  public static void openRecall(ServerPlayer player, int page) {
    if (!holdingRecallMap(player)) return;
    var owned = NpcRoster.get(player.level().getServer()).owned(player.getUUID());
    int pages = Math.max(1, (owned.size() + 4) / 5);
    page = Math.clamp(page, 0, pages - 1);
    var result = new JsonObject();
    result.addProperty("page", page);
    result.addProperty("pages", pages);
    result.addProperty("total", owned.size());
    var rows = new JsonArray();
    for (var entry : owned.subList(page * 5, Math.min(owned.size(), (page + 1) * 5))) {
      var row = new JsonObject();
      row.addProperty("id", entry.id().toString());
      row.addProperty("name", entry.name());
      row.addProperty("dimension", entry.dimension());
      row.addProperty("x", entry.x());
      row.addProperty("y", entry.y());
      row.addProperty("z", entry.z());
      rows.add(row);
    }
    result.add("actors", rows);
    reply(player, -1, "recall_list", result.toString());
  }

  public static void openJournal(ServerPlayer player) {
    var service = NpcService.get(player.level().getServer());
    var owner = player.getUUID();
    service
        .readJournal(
            () -> {
              var actors = new JsonArray();
              for (var actor : service.journal.actors(owner)) {
                var page = service.journal.read(owner, actor, "", 1);
                var row = new JsonObject();
                row.addProperty("id", actor.toString());
                row.addProperty(
                    "name",
                    page.entries().isEmpty()
                        ? actor.toString().substring(0, 8)
                        : page.entries().getFirst().actorName());
                actors.add(row);
              }
              return actors.toString();
            })
        .whenComplete(
            (data, error) ->
                player
                    .level()
                    .getServer()
                    .execute(
                        () -> {
                          if (!player.hasDisconnected() && holdingJournal(player))
                            reply(
                                player,
                                -1,
                                error == null ? "actors" : "error",
                                error == null ? data : "記録を読み込めません");
                        }));
  }

  private static void readJournal(ServerPlayer player, int entity, UUID actor, String cursor) {
    var service = NpcService.get(player.level().getServer());
    var owner = player.getUUID();
    service
        .readJournal(
            () -> {
              var page =
                  AvatarArchive.GSON
                      .toJsonTree(service.journal.read(owner, actor, cursor, 12))
                      .getAsJsonObject();
              page.addProperty("actor", actor.toString());
              return page.toString();
            })
        .whenComplete(
            (data, error) ->
                player
                    .level()
                    .getServer()
                    .execute(
                        () -> {
                          if (player.hasDisconnected()) return;
                          boolean menu =
                              player.containerMenu instanceof CompanionMenu m
                                  && m.npc.getId() == entity
                                  && m.npc.getUUID().equals(actor)
                                  && m.stillValid(player);
                          if (menu || holdingJournal(player))
                            reply(
                                player,
                                entity,
                                error == null ? "journal" : "error",
                                error == null ? data : "記録を読み込めません");
                        }));
  }

  public static void reply(ServerPlayer player, int entity, String action, String data) {
    if (player.connection.hasChannel(Response.TYPE))
      PacketDistributor.sendToPlayer(player, new Response(entity, action, data));
  }

  private NpcNetwork() {}
}

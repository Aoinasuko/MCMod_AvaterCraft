package dev.avatarcraft.npc;

import com.google.gson.*;
import dev.avatarcraft.npc.ai.NpcService;
import dev.avatarcraft.npc.memory.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/** Host-authenticated, owner-scoped journal queries and bounded, ordered export streaming. */
final class JournalRequests {
  private static final Map<UUID, UUID> EXPORTS = new HashMap<>();
  private static final Map<UUID, Long> NEXT_EXPORT = new HashMap<>();
  private static final Gson GSON = new Gson();

  static boolean handle(ServerPlayer player, NpcNetwork.Request request) {
    if (!Set.of("journal_recent", "journal_stats", "journal_export").contains(request.action())) return false;
    CompanionEntity npc = player.containerMenu instanceof CompanionMenu menu
        && menu.npc.getId() == request.entity() && menu.stillValid(player) ? menu.npc : null;
    if (npc == null && !NpcNetwork.holdingJournal(player)) throw new IllegalArgumentException();
    var query = JsonParser.parseString(request.data()).getAsJsonObject();
    UUID actor = npc == null ? UUID.fromString(query.get("actor").getAsString()) : npc.getUUID();
    UUID owner = player.getUUID();
    var service = NpcService.get(player.level().getServer());
    if (request.action().equals("journal_export")) {
      UUID token = UUID.fromString(query.get("token").getAsString());
      long now = System.currentTimeMillis();
      if (EXPORTS.containsKey(owner) || now < NEXT_EXPORT.getOrDefault(owner, 0L)) {
        exportReply(player, "error", token, "少し待ってから再度エクスポートしてください"); return true;
      }
      EXPORTS.put(owner, token); NEXT_EXPORT.put(owner, now + 30_000);
      Path temporary = player.level().getServer().getWorldPath(LevelResource.ROOT)
          .resolve("bep_3davater_companion/export-temporary");
      service.readJournal(() -> JournalArchive.create(service.journal, owner, actor, temporary))
          .whenComplete((file, error) -> player.level().getServer().execute(() -> {
            if (error != null) {
              EXPORTS.remove(owner, token);
              if (!player.hasDisconnected()) exportReply(player, "error", token, "出力できません。保存領域・60分の記録量を確認してください（最大64MiB）");
            } else if (!active(player, token)) clean(service, owner, token, file);
            else {
              var start = new JsonObject(); start.addProperty("token", token.toString());
              start.addProperty("bytes", file.bytes()); start.addProperty("sha256", file.sha256());
              NpcNetwork.reply(player, -1, "journal_export_start", start.toString());
              pump(player, token, file, 0, 0, service);
            }
          }));
      return true;
    }
    boolean conversation = query.has("conversation") && query.get("conversation").getAsBoolean();
    long sequence = query.get("sequence").getAsLong();
    service.readJournal(() -> {
      var result = new JsonObject(); result.addProperty("actor", actor.toString());
      result.addProperty("sequence", sequence);
      if (request.action().equals("journal_stats"))
        result.add("report", GSON.toJsonTree(JournalStatistics.read(service.journal, owner, actor, service.journal.now())));
      else {
        result.addProperty("conversation", conversation);
        var rows = new JsonArray();
        for (var entry : service.journal.recent(owner, actor, conversation)) {
          var row = new JsonObject(); row.addProperty("id", entry.id()); row.addProperty("timestamp", entry.timestamp());
          row.addProperty("actorName", entry.actorName()); row.addProperty("kind", entry.kind());
          row.addProperty("text", compact(entry.text(), 600));
          row.addProperty("details", compact(entry.details().toString(), 900)); rows.add(row);
        }
        result.add("entries", rows);
      }
      return result.toString();
    }).whenComplete((data, error) -> player.level().getServer().execute(() -> {
      if (player.hasDisconnected()) return;
      boolean menu = player.containerMenu instanceof CompanionMenu m && m.npc.getId() == request.entity()
          && m.npc.getUUID().equals(actor) && m.stillValid(player);
      if (menu || NpcNetwork.holdingJournal(player)) NpcNetwork.reply(player, request.entity(),
          error == null ? request.action() : "error", error == null ? data : "記録を読み込めません");
    }));
    return true;
  }
  private static String compact(String value, int limit) {
    return value.length() <= limit ? value : value.substring(0, limit) + "…";
  }
  private static boolean active(ServerPlayer player, UUID token) {
    return !player.hasDisconnected() && token.equals(EXPORTS.get(player.getUUID())) && NpcNetwork.holdingJournal(player);
  }
  private static void pump(ServerPlayer player, UUID token, JournalArchive.File file, long offset, int index, NpcService service) {
    service.readJournal(() -> {
      try (var input = new java.io.RandomAccessFile(file.path().toFile(), "r")) {
        input.seek(offset); byte[] bytes = new byte[(int) Math.min(32768, file.bytes() - offset)];
        input.readFully(bytes); return bytes;
      }
    }).whenComplete((bytes, error) -> player.level().getServer().execute(() -> {
      if (!active(player, token)) { clean(service, player.getUUID(), token, file); return; }
      if (error != null) {
        exportReply(player, "error", token, "行動ログを転送できません");
        clean(service, player.getUUID(), token, file); return;
      }
      var part = new JsonObject(); part.addProperty("token", token.toString()); part.addProperty("index", index);
      part.addProperty("data", Base64.getEncoder().encodeToString(bytes));
      NpcNetwork.reply(player, -1, "journal_export_part", part.toString());
      if (offset + bytes.length < file.bytes()) pump(player, token, file, offset + bytes.length, index + 1, service);
      else {
        exportReply(player, "done", token, "");
        clean(service, player.getUUID(), token, file);
      }
    }));
  }
  private static void clean(NpcService service, UUID owner, UUID token, JournalArchive.File file) {
    EXPORTS.remove(owner, token);
    service.readJournal(() -> { file.close(); return null; });
  }
  private static void exportReply(ServerPlayer player, String action, UUID token, String message) {
    var data = new JsonObject(); data.addProperty("token", token.toString()); data.addProperty("message", message);
    NpcNetwork.reply(player, -1, "journal_export_" + action, data.toString());
  }
  static void clear() { EXPORTS.clear(); NEXT_EXPORT.clear(); }
  private JournalRequests() {}
}

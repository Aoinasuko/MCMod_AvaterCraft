package dev.avatarcraft.npc;

import com.google.gson.*;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;

/** Series settings use authenticated ownership, without granting inventory or task access. */
public final class CompanionManagement {
  public static JsonObject list(ServerPlayer player, int page) {
    var server = player.level().getServer();
    var owned = NpcRoster.get(server).owned(player.getUUID());
    int pages = Math.max(1, (owned.size() + 11) / 12);
    page = Math.clamp(page, 0, pages - 1);
    var result = new JsonObject();
    result.addProperty("page", page); result.addProperty("pages", pages); result.addProperty("total", owned.size());
    var actors = new JsonArray();
    for (var entry : owned.subList(page * 12, Math.min(owned.size(), (page + 1) * 12))) {
      var row = new JsonObject();
      var npc = NpcRoster.find(server, entry.id());
      row.addProperty("id", entry.id().toString()); row.addProperty("name", npc == null ? entry.name() : npc.getName().getString());
      row.addProperty("dimension", entry.dimension()); row.addProperty("loaded", npc != null && npc.ownedBy(player));
      actors.add(row);
    }
    result.add("actors", actors); return result;
  }
  private static CompanionEntity owned(ServerPlayer player, UUID id) {
    var server = player.level().getServer();
    if (NpcRoster.get(server).owned(player.getUUID(), id) == null) throw new IllegalArgumentException("Not owned");
    var npc = NpcRoster.find(server, id);
    if (npc == null || !npc.ownedBy(player) || npc.isRemoved()) throw new IllegalArgumentException("NPC not loaded");
    return npc;
  }
  public static JsonObject profile(ServerPlayer player, UUID id) {
    var npc = owned(player, id);
    var result = new JsonObject(); result.addProperty("id", id.toString());
    result.add("personality", JsonParser.parseString(npc.personalityJson())); return result;
  }
  public static JsonObject save(ServerPlayer player, UUID id, String json) {
    var npc = owned(player, id);
    var profile = Personality.parse(json);
    npc.setPersonality(profile); NpcRoster.get(player.level().getServer()).update(npc);
    return profile(player, id);
  }
}

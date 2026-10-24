package dev.avatarcraft.npc;

import com.google.gson.*;
import com.mojang.serialization.Codec;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.*;

/** Persistent UUID index, including unloaded/dead companions. Never trusts a client owner ID. */
public final class NpcRoster extends SavedData {
  public record Entry(UUID id, UUID owner, String name, String dimension, int x, int y, int z) {}

  private static final Gson GSON = new Gson();
  private final Map<UUID, Entry> entries = new LinkedHashMap<>();
  public static final SavedDataType<NpcRoster> TYPE =
      new SavedDataType<>(
          NpcMod.id("roster"),
          NpcRoster::new,
          Codec.STRING.xmap(NpcRoster::parse, NpcRoster::json));

  private static NpcRoster parse(String json) {
    var result = new NpcRoster();
    for (var e : GSON.fromJson(json, Entry[].class)) {
      if (e == null
          || e.id() == null
          || e.owner() == null
          || e.name() == null
          || e.name().length() > 512
          || e.dimension() == null
          || e.dimension().length() > 256) throw new IllegalArgumentException("Invalid NPC roster");
      result.entries.put(e.id(), e);
    }
    return result;
  }

  private String json() {
    return GSON.toJson(entries.values());
  }

  public static NpcRoster get(MinecraftServer server) {
    return server.overworld().getDataStorage().computeIfAbsent(TYPE);
  }

  public void update(CompanionEntity npc) {
    if (npc.ownerId() == null) return;
    var p = npc.blockPosition();
    var e =
        new Entry(
            npc.getUUID(),
            npc.ownerId(),
            npc.getName().getString(),
            npc.level().dimension().identifier().toString(),
            p.getX(),
            p.getY(),
            p.getZ());
    if (!e.equals(entries.put(e.id(), e))) setDirty();
  }

  public void remove(UUID id) {
    if (entries.remove(id) != null) setDirty();
  }

  public List<Entry> owned(UUID owner) {
    return entries.values().stream().filter(e -> e.owner().equals(owner)).toList();
  }

  public Entry owned(UUID owner, UUID actor) {
    var e = entries.get(actor);
    return e != null && e.owner().equals(owner) ? e : null;
  }

  public boolean canSummon(UUID owner, int limit) {
    return limit == -1 || owned(owner).size() < limit;
  }

  public static CompanionEntity find(MinecraftServer server, UUID id) {
    for (ServerLevel level : server.getAllLevels())
      if (level.getEntity(id) instanceof CompanionEntity npc) return npc;
    return null;
  }
}

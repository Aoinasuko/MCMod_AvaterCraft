package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.*;
import com.mojang.serialization.Codec;
import dev.avatarcraft.npc.*;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.*;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * World-owned construction plans. The client receives geometry only, never authority or AI
 * settings.
 */
public final class NpcBlueprints extends SavedData {
  public static final class Plan {
    public String id, owner, npc, dimension, design, status = "planned";
    public int x, y, z;

    public BlueprintSpec spec() {
      return BlueprintSpec.parse(design);
    }
  }

  private static final Gson GSON = new Gson();
  public final Map<String, Plan> plans = new LinkedHashMap<>();
  private static final Map<String, String> SENT = new HashMap<>();
  public static final SavedDataType<NpcBlueprints> TYPE =
      new SavedDataType<>(
          NpcMod.id("blueprints"),
          NpcBlueprints::new,
          Codec.STRING.xmap(NpcBlueprints::parse, d -> GSON.toJson(d.plans)));

  private static NpcBlueprints parse(String json) {
    if (json.length() > 1048576) throw new IllegalArgumentException("Blueprint storage too large");
    var o = JsonParser.parseString(json).getAsJsonObject();
    if (o.size() > 64) throw new IllegalArgumentException("Too many active blueprints");
    var d = new NpcBlueprints();
    for (var e : o.entrySet()) {
      var p = GSON.fromJson(e.getValue(), Plan.class);
      UUID.fromString(p.id);
      UUID.fromString(p.owner);
      UUID.fromString(p.npc);
      if (!p.npc.equals(e.getKey())
          || p.dimension == null
          || !p.dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
          || p.status == null
          || !Set.of("planned", "gathering", "building", "complete", "blocked", "cancelled")
              .contains(p.status)) throw new IllegalArgumentException("Invalid saved blueprint");
      p.spec();
      d.plans.put(e.getKey(), p);
    }
    return d;
  }

  public static NpcBlueprints get(MinecraftServer server) {
    return server.overworld().getDataStorage().computeIfAbsent(TYPE);
  }

  public Plan current(CompanionEntity npc, String id) {
    var p = plans.get(npc.getUUID().toString());
    if (p == null
        || !p.owner.equals(npc.ownerId().toString())
        || !p.dimension.equals(npc.level().dimension().identifier().toString())
        || !id.equals("current") && !id.equals(p.id))
      throw new IllegalArgumentException("Blueprint unavailable for this NPC/owner/dimension");
    return p;
  }

  public void put(Plan p) {
    if (!plans.containsKey(p.npc) && plans.size() >= 64)
      plans.values().removeIf(old -> Set.of("complete", "cancelled").contains(old.status));
    if (!plans.containsKey(p.npc) && plans.size() >= 64)
      throw new IllegalArgumentException("Blueprint storage limit reached");
    plans.put(p.npc, p);
    setDirty();
  }

  public static void clearClientCache() {
    SENT.clear();
  }

  public static void tick(MinecraftServer server) {
    if (server.getTickCount() % 40 != 0) return;
    var data = get(server);
    for (var p : data.plans.values())
      for (var player : server.getPlayerList().getPlayers()) {
        if (!net.neoforged.neoforge.network.registration.NetworkRegistry.hasChannel(
            player.connection, NpcNetwork.Response.TYPE.id())) continue;
        if (!player.level().dimension().identifier().toString().equals(p.dimension)) continue;
        if (!player.getUUID().toString().equals(p.owner)
            && player.distanceToSqr(p.x, p.y, p.z) > 128 * 128) continue;
        String key =
            player.getUUID() + ":" + p.npc + ":" + System.identityHashCode(player.connection);
        String revision = p.id + ":" + p.status;
        if (!revision.equals(SENT.get(key))) {
          PacketDistributor.sendToPlayer(
              player, new NpcNetwork.Response(-1, "blueprint", geometry(p).toString()));
          SENT.put(key, revision);
        }
      }
    if (SENT.size() > 4096) SENT.clear();
  }

  public static JsonObject geometry(Plan p) {
    var spec = p.spec();
    var palette = new ArrayList<String>();
    var cells = new JsonArray();
    for (var e : spec.blocks().entrySet()) {
      int index = palette.indexOf(e.getValue());
      if (index < 0) {
        index = palette.size();
        palette.add(e.getValue());
      }
      var c = e.getKey();
      cells.add(GSON.toJsonTree(List.of(c.x(), c.y(), c.z(), index)));
    }
    return object(
        "id",
        p.id,
        "npc",
        p.npc,
        "dimension",
        p.dimension,
        "status",
        p.status,
        "name",
        spec.name(),
        "origin",
        GSON.toJsonTree(List.of(p.x, p.y, p.z)),
        "palette",
        GSON.toJsonTree(palette),
        "cells",
        cells);
  }
}

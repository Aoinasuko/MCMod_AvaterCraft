package dev.avatarcraft.npc;

import com.google.gson.*;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.*;

/** The overworld owns the index, so it is shared across players and dimensions. */
public final class NpcAnchors extends SavedData {
  public final AnchorIndex index;

  private NpcAnchors(AnchorIndex index) {
    this.index = index;
  }

  public static final SavedDataType<NpcAnchors> TYPE =
      new SavedDataType<>(
          NpcMod.id("anchors"),
          () -> new NpcAnchors(new AnchorIndex()),
          Codec.STRING.xmap(s -> new NpcAnchors(AnchorIndex.parse(s)), d -> d.index.json()));

  public static NpcAnchors get(MinecraftServer server) {
    return server.overworld().getDataStorage().computeIfAbsent(TYPE);
  }

  public static void remove(ServerLevel level, BlockPos pos) {
    var d = get(level.getServer());
    if (d.index.removeAt(
        level.dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ()))
      d.setDirty();
  }

  public AnchorIndex.Entry resolve(MinecraftServer server, String id) {
    var e = index.get(id);
    if (e == null) return null;
    var level =
        server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(e.dimension())));
    var pos = new BlockPos(e.x(), e.y(), e.z());
    // Unloaded anchors remain known. Never load a chunk merely to include it in a prompt.
    if (level != null
        && level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null
        && (!(level.getBlockEntity(pos) instanceof AvatarAnchorBlockEntity be)
            || !be.anchorId().equals(e.name()))) {
      index.removeAt(e.dimension(), e.x(), e.y(), e.z());
      setDirty();
      return null;
    }
    return e;
  }

  public JsonArray search(MinecraftServer server, String query) {
    var result = new JsonArray();
    if (query.strip().length() <= 32) {
      var exact = resolve(server, query);
      if (exact != null) {
        result.add(exact.observation());
        return result;
      }
    }
    var q = query.toLowerCase(java.util.Locale.ROOT);
    for (var entry : index.entries()) {
      if (result.size() >= 32) break;
      if (!q.isBlank() && !entry.name().toLowerCase(java.util.Locale.ROOT).contains(q)) continue;
      var valid = resolve(server, entry.name());
      if (valid != null) result.add(valid.observation());
    }
    return result;
  }

  public JsonArray relevant(MinecraftServer server, String instruction) {
    var result = new JsonArray();
    for (var e : index.entries())
      if (instruction.contains(e.name()) && resolve(server, e.name()) != null && result.size() < 16)
        result.add(e.observation());
    // With no explicit match a small directory is useful; exact lookup remains available to all
    // NPCs.
    return result.isEmpty() ? search(server, "") : result;
  }
}

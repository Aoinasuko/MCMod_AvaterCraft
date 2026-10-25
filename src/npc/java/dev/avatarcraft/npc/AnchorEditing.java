package dev.avatarcraft.npc;

import com.google.gson.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * A save is valid only for the block the player actually opened, within normal interaction range.
 */
public final class AnchorEditing {
  private record Session(
      String token, String dimension, BlockPos pos, AvatarAnchorBlockEntity block, long expires) {}

  private static final Map<UUID, Session> SESSIONS = new HashMap<>();

  public static void clear() {
    SESSIONS.clear();
  }

  public static void expire(long tick) {
    SESSIONS.values().removeIf(s -> s.expires < tick);
  }

  private static boolean mayEdit(ServerPlayer p, BlockPos pos) {
    return p.mayBuild()
        && p.level().hasChunkAt(pos)
        && p.level().mayInteract(p, pos)
        && p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) <= 64;
  }

  public static JsonObject open(ServerPlayer player, BlockPos pos) {
    if (!mayEdit(player, pos)
        || !(player.level().getBlockEntity(pos) instanceof AvatarAnchorBlockEntity block))
      throw new IllegalArgumentException("anchor.unavailable");
    long tick = player.level().getServer().getTickCount();
    expire(tick);
    if (!SESSIONS.containsKey(player.getUUID()) && SESSIONS.size() >= 1024)
      throw new IllegalArgumentException("anchor.unavailable");
    var s =
        new Session(
            UUID.randomUUID().toString(),
            player.level().dimension().identifier().toString(),
            pos.immutable(),
            block,
            tick + 6000);
    SESSIONS.put(player.getUUID(), s);
    var o = new JsonObject();
    o.addProperty("token", s.token);
    o.addProperty("id", block.anchorId());
    o.addProperty("x", pos.getX());
    o.addProperty("y", pos.getY());
    o.addProperty("z", pos.getZ());
    return o;
  }

  public static JsonObject save(ServerPlayer player, JsonObject request) {
    var result = new JsonObject();
    String token =
        request.has("token") && request.get("token").isJsonPrimitive()
            ? request.get("token").getAsString()
            : "";
    result.addProperty("token", token);
    try {
      var s = SESSIONS.get(player.getUUID());
      if (!request.keySet().equals(Set.of("token", "id"))
          || s == null
          || !s.token.equals(token)
          || s.expires < player.level().getServer().getTickCount()
          || !s.dimension.equals(player.level().dimension().identifier().toString())
          || !mayEdit(player, s.pos)
          || player.level().getBlockEntity(s.pos) != s.block)
        throw new IllegalArgumentException("anchor.unavailable");
      if (!request.get("id").isJsonPrimitive() || !request.getAsJsonPrimitive("id").isString())
        throw new IllegalArgumentException("anchor.invalid_id");
      s.block.setAnchorId(request.get("id").getAsString());
      result.addProperty("ok", true);
      result.addProperty("id", s.block.anchorId());
      result.addProperty("message", "anchor.saved");
    } catch (IllegalArgumentException e) {
      result.addProperty("ok", false);
      result.addProperty(
          "message",
          Set.of("anchor.duplicate_id", "anchor.invalid_id", "anchor.limit")
                  .contains(e.getMessage())
              ? e.getMessage()
              : "anchor.unavailable");
    }
    return result;
  }
}

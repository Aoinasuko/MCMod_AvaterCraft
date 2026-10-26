package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;
import com.google.gson.*;
import dev.avatarcraft.npc.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.HitResult;

/** Server-observed command referents, frozen before an asynchronous planning request. */
public final class NpcCommandContext {
  public static final String DELIVERY = "依頼の収納先";

  public static JsonObject capture(CompanionEntity npc, ServerPlayer player) {
    if (player == null || !npc.ownedBy(player) || player.level() != npc.level()) return new JsonObject();
    var level = player.level();
    var result = object("owner", player.getUUID().toString(), "dimension",
        level.dimension().identifier().toString(), "capturedTick", level.getGameTime(),
        "ownerPosition", new Gson().toJsonTree(java.util.List.of(player.getX(), player.getY(), player.getZ())));
    result.add("speaker", actor(player, level.getMinY(), level.getMaxY()));
    result.add("addressee", actor(npc, level.getMinY(), level.getMaxY()));
    result.addProperty("referenceSemantics", "Frozen at command receipt. 私/僕/俺/I/me = speaker; 君/あなた/you = addressee. "
        + "ここ/here needs the noun and speaker intent: pointed block for this block/chest; actor position for standing/here; "
        + "chunk bounds for actor's chunk. Do not confuse a point with an entire region. Ask if multiple referents remain plausible.");
    var eye = player.getEyePosition();
    var hit = level.clip(new ClipContext(eye, eye.add(player.getViewVector(1).scale(8)),
        ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
    BlockPos destination = null;
    if (hit.getType() == HitResult.Type.BLOCK)
      result.add("pointedBlock", object("position", new Gson().toJsonTree(java.util.List.of(hit.getBlockPos().getX(),
          hit.getBlockPos().getY(), hit.getBlockPos().getZ())), "block",
          net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(hit.getBlockPos()).getBlock()).toString()));
    if (hit.getType() == HitResult.Type.BLOCK && accessible(player, hit.getBlockPos()))
      destination = hit.getBlockPos();
    // "Here" is also unambiguous when there is exactly one accessible chest within four blocks.
    if (destination == null) {
      for (var p : BlockPos.betweenClosed(player.blockPosition().offset(-4,-2,-4),
          player.blockPosition().offset(4,2,4))) {
        if (p.distToCenterSqr(player.position()) > 16 || !accessible(player, p)) continue;
        if (destination != null) { destination = null; result.addProperty("chestAmbiguous", true); break; }
        destination = p.immutable();
      }
    }
    if (destination != null)
      result.add("deliveryChest", new Gson().toJsonTree(new NpcLocationMemory.Place(DELIVERY,
          level.dimension().identifier().toString(), destination.getX(), destination.getY(), destination.getZ(), "chest")));
    return result;
  }

  private static JsonObject actor(net.minecraft.world.entity.Entity entity, int minY, int maxY) {
    var position=entity.position();String id=entity.getUUID().toString();
    var p=BlockPos.containing(position);int cx=Math.floorDiv(p.getX(),16),cz=Math.floorDiv(p.getZ(),16);
    double yaw=Math.toRadians(entity.getYRot());
    return object("uuid",id,"position",new Gson().toJsonTree(java.util.List.of(position.x,position.y,position.z)),
        "blockPosition",new Gson().toJsonTree(java.util.List.of(p.getX(),p.getY(),p.getZ())),
        "yawDegrees",entity.getYRot(),"pitchDegrees",entity.getXRot(),
        "forward",new Gson().toJsonTree(java.util.List.of(-Math.sin(yaw),0.,Math.cos(yaw))),
        "right",new Gson().toJsonTree(java.util.List.of(-Math.cos(yaw),0.,-Math.sin(yaw))),
        "chunk",object("x",cx,"z",cz,"minX",cx*16,"maxX",cx*16+15,"minZ",cz*16,"maxZ",cz*16+15,
            "minY",minY,"maxY",maxY-1));
  }

  public static NpcLocationMemory.Place reference(CompanionEntity npc, String name, String reference) {
    var context=npc.commandContext();
    if(npc.ownerId()==null||!context.has("owner")||!context.get("owner").getAsString().equals(npc.ownerId().toString())
        ||!context.get("dimension").getAsString().equals(npc.level().dimension().identifier().toString()))
      throw new IllegalArgumentException("No authenticated command reference in this dimension");
    if(reference.equals("delivery_chest")) {
      var p=delivery(npc);if(p==null)throw new IllegalArgumentException("Command chest is ambiguous or absent");
      return new NpcLocationMemory.Place(name,p.dimension(),p.x(),p.y(),p.z(),p.kind());
    }
    JsonArray p=switch(reference) {
      case "speaker_position" -> context.getAsJsonObject("speaker").getAsJsonArray("blockPosition");
      case "npc_position" -> context.getAsJsonObject("addressee").getAsJsonArray("blockPosition");
      case "pointed_block" -> context.getAsJsonObject("pointedBlock").getAsJsonArray("position");
      default -> throw new IllegalArgumentException("Unknown reference; use speaker_position, npc_position, pointed_block or delivery_chest");
    };
    return new NpcLocationMemory.Place(name,context.get("dimension").getAsString(),p.get(0).getAsInt(),p.get(1).getAsInt(),p.get(2).getAsInt(),"point");
  }

  private static boolean accessible(ServerPlayer player, BlockPos p) {
    return player.level().hasChunkAt(p) && player.level().mayInteract(player, p)
        && player.level().getBlockEntity(p) instanceof ChestBlockEntity chest && chest.canOpen(player);
  }

  public static NpcLocationMemory.Place delivery(CompanionEntity npc) {
    var context = npc.commandContext();
    try {
      if (npc.ownerId() == null || !context.get("owner").getAsString().equals(npc.ownerId().toString())) return null;
      var place = new Gson().fromJson(context.get("deliveryChest"), NpcLocationMemory.Place.class);
      return place != null && place.kind().equals("chest")
          && place.dimension().equals(npc.level().dimension().identifier().toString()) ? place : null;
    } catch (RuntimeException e) { return null; }
  }

}

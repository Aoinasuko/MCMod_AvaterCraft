package dev.avatarcraft.player.multiplayer;

import dev.avatarcraft.player.bep_3davater_player;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class ShareNetwork {
  public record Up(ShareMessage message) implements CustomPacketPayload {
    public static final Type<Up> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(bep_3davater_player.ID, "share_up"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Up> CODEC =
        StreamCodec.of((b, p) -> write(b, p.message), b -> new Up(read(b)));

    public Type<Up> type() {
      return TYPE;
    }
  }

  public record Down(ShareMessage message) implements CustomPacketPayload {
    public static final Type<Down> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(bep_3davater_player.ID, "share_down"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Down> CODEC =
        StreamCodec.of((b, p) -> write(b, p.message), b -> new Down(read(b)));

    public Type<Down> type() {
      return TYPE;
    }
  }

  private static void write(RegistryFriendlyByteBuf b, ShareMessage p) {
    b.writeVarInt(p.op());
    b.writeUUID(p.peer());
    b.writeUUID(p.transfer());
    b.writeVarInt(p.sequence());
    b.writeVarInt(p.length());
    b.writeUtf(p.text(), 2048);
    b.writeByteArray(p.data());
  }

  private static ShareMessage read(RegistryFriendlyByteBuf b) {
    return new ShareMessage(
        b.readVarInt(),
        b.readUUID(),
        b.readUUID(),
        b.readVarInt(),
        b.readVarInt(),
        b.readUtf(2048),
        b.readByteArray(ShareMessage.CHUNK_BYTES));
  }

  public static void register(RegisterPayloadHandlersEvent event) {
    var registrar = event.registrar("private-1").optional();
    registrar.playToServer(
        Up.TYPE,
        Up.CODEC,
        (packet, context) -> {
          if (context.player() instanceof net.minecraft.server.level.ServerPlayer player)
            ShareServer.receive(player, packet.message());
        });
    registrar.playToClient(Down.TYPE, Down.CODEC);
  }

  private ShareNetwork() {}
}

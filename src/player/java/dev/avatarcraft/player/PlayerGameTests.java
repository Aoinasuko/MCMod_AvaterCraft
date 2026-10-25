package dev.avatarcraft.player;

import java.util.UUID;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.*;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

public final class PlayerGameTests {
  private static Identifier id(String path) {
    return Identifier.fromNamespaceAndPath(bep_3davater_player.ID, path);
  }

  public static void register(RegisterGameTestsEvent event) {
    var environment = event.registerEnvironment(id("qa"), new TestEnvironmentDefinition.AllOf());
    event.registerTest(
        id("private_avatar_wire"),
        new FunctionGameTestInstance(
            BuiltinTestFunctions.ALWAYS_PASS,
            new TestData<>(environment, id("qa_empty"), 40, 1, true)) {
          public void run(GameTestHelper h) {
            var buffer =
                new net.minecraft.network.RegistryFriendlyByteBuf(
                    io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
            try {
              var message =
                  new dev.avatarcraft.player.multiplayer.ShareMessage(
                      dev.avatarcraft.player.multiplayer.ShareMessage.CHUNK,
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      7,
                      0,
                      "",
                      new byte[dev.avatarcraft.player.multiplayer.ShareMessage.CHUNK_BYTES]);
              var packet = new dev.avatarcraft.player.multiplayer.ShareNetwork.Up(message);
              dev.avatarcraft.player.multiplayer.ShareNetwork.Up.CODEC.encode(buffer, packet);
              h.assertTrue(
                  buffer.readableBytes() < 32767,
                  "Upload must fit Minecraft serverbound packet limit");
              var decoded =
                  dev.avatarcraft.player.multiplayer.ShareNetwork.Up.CODEC.decode(buffer).message();
              h.assertTrue(
                  decoded.peer().equals(message.peer())
                      && decoded.transfer().equals(message.transfer())
                      && decoded.sequence() == 7
                      && decoded.data().length == 16384,
                  "Wire packet round trip");
              buffer.clear();
              buffer.writeVarInt(dev.avatarcraft.player.multiplayer.ShareMessage.CHUNK);
              buffer.writeUUID(UUID.randomUUID());
              buffer.writeUUID(UUID.randomUUID());
              buffer.writeVarInt(0);
              buffer.writeVarInt(0);
              buffer.writeUtf("");
              buffer.writeByteArray(new byte[16385]);
              boolean rejected = false;
              try {
                dev.avatarcraft.player.multiplayer.ShareNetwork.Up.CODEC.decode(buffer);
              } catch (RuntimeException expected) {
                rejected = true;
              }
              h.assertTrue(rejected, "Decoder must reject oversized chunks before allocation");
              h.succeed();
            } finally {
              buffer.release();
            }
          }
        });
  }
}

package dev.avatarcraft.player.client;

import dev.avatarcraft.client.*;

import dev.avatarcraft.player.bep_3davater_player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = bep_3davater_player.ID, dist = Dist.CLIENT)
public final class PlayerModelClient {
  public PlayerModelClient(IEventBus bus) {
    SeriesTabs.register(bep_3davater_player.ID, net.minecraft.network.chat.Component.literal("Player Model"), PlayerModelScreen::new);
    bus.addListener(
        net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent.class,
        event -> event.register(dev.avatarcraft.player.multiplayer.ShareNetwork.Down.TYPE,
            (packet, context) -> ShareClient.receive(packet.message())));
    AvatarSession.selectionChanged = ShareClient::selectionChanged;
    AvatarSession.status = ShareClient::status;
    NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> ShareClient.disconnect());
    NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> ShareClient.tick());
    NeoForge.EVENT_BUS.addListener(ClientTickEvent.Pre.class, e -> AvatarQa.tick());
    NeoForge.EVENT_BUS.addListener(RenderPlayerEvent.Pre.class, AvatarRenderer::render);
    NeoForge.EVENT_BUS.addListener(RenderArmEvent.class, AvatarFirstPerson::render);
  }
}

package dev.avatarcraft;

import net.neoforged.fml.common.Mod;

@Mod(AvatarCraft.ID)
public final class AvatarCraft {
  public static final String ID = "avatarcraft";

  public AvatarCraft(net.neoforged.bus.api.IEventBus bus) {
    bus.addListener(
        net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent.class,
        dev.avatarcraft.multiplayer.ShareNetwork::register);
    dev.avatarcraft.multiplayer.ShareServer.install();
  }
}

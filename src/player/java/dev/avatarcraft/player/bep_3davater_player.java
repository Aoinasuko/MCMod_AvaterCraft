package dev.avatarcraft.player;

import net.neoforged.fml.common.Mod;

@Mod(bep_3davater_player.ID)
public final class bep_3davater_player {
  public static final String ID = "bep_3davater_player";

  public bep_3davater_player(net.neoforged.bus.api.IEventBus bus) {
    bus.addListener(
        net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent.class,
        dev.avatarcraft.player.multiplayer.ShareNetwork::register);
    dev.avatarcraft.player.multiplayer.ShareServer.install();
    if (Boolean.getBoolean("avatarcraft.playerGameTest")
        && !net.neoforged.fml.loading.FMLEnvironment.isProduction())
      bus.addListener(net.neoforged.neoforge.event.RegisterGameTestsEvent.class, PlayerGameTests::register);
  }
}

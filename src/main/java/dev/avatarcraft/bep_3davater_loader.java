package dev.avatarcraft;

import net.neoforged.fml.common.Mod;

/** Shared model loader. Game integrations live in their own optional mods. */
@Mod(bep_3davater_loader.ID)
public final class bep_3davater_loader {
  public static final String ID = "bep_3davater_loader";

  public bep_3davater_loader(net.neoforged.bus.api.IEventBus bus) {
    if (Boolean.getBoolean("avatarcraft.loaderGameTest")
        && !net.neoforged.fml.loading.FMLEnvironment.isProduction())
      bus.addListener(net.neoforged.neoforge.event.RegisterGameTestsEvent.class,
          dev.avatarcraft.LoaderGameTests::register);
  }
}

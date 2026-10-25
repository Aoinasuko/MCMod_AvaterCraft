package dev.avatarcraft.npc.client;

import dev.avatarcraft.api.client.AvatarActorRenderer;
import dev.avatarcraft.npc.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = NpcMod.ID, dist = Dist.CLIENT)
public final class NpcClient {
  private AvatarActorRenderer<CompanionEntity> renderer;

  public NpcClient(IEventBus bus, net.neoforged.fml.ModContainer container) {
    if (Boolean.getBoolean("avatarcraft.anchorUiQa"))
      NeoForge.EVENT_BUS.addListener(
          net.neoforged.neoforge.client.event.ClientTickEvent.Post.class,
          event ->
              net.minecraft.client.Minecraft.getInstance()
                  .getWindow()
                  .setTitle("AvatarCraft 0.7.0 Anchor QA"));
    java.util.concurrent.CompletableFuture.runAsync(
        () -> {
          try {
            BundledAvatar.install(dev.avatarcraft.client.AvatarLibrary.ROOT.resolve("avatars"));
            dev.avatarcraft.client.AvatarLibrary.reloadAfterCurrent();
          } catch (Exception e) {
            System.getLogger("AvatarCraft NPC")
                .log(System.Logger.Level.ERROR, "Cannot install bundled tortobu", e);
          }
        });
    container.registerExtensionPoint(
        net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
        net.neoforged.neoforge.client.gui.ConfigurationScreen::new);
    bus.addListener(
        net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent.class,
        event ->
            event.register(
                NpcNetwork.Response.TYPE,
                (response, context) -> {
                  var minecraft = net.minecraft.client.Minecraft.getInstance();
                  if (response.action().equals("anchor_open")) {
                    if (minecraft.gui.screen() != null
                        && !(minecraft.gui.screen() instanceof AnchorScreen)) return;
                    minecraft.gui.setScreen(
                        new AnchorScreen(
                            com.google.gson.JsonParser.parseString(response.data())
                                .getAsJsonObject()));
                    return;
                  }
                  if (minecraft.gui.screen() instanceof AnchorScreen anchor) {
                    anchor.receive(response);
                    return;
                  }
                  if (response.action().equals("recall_list")) {
                    if (minecraft.gui.screen() != null
                        && !(minecraft.gui.screen() instanceof RecallScreen)) return;
                    if (!(minecraft.gui.screen() instanceof RecallScreen))
                      minecraft.gui.setScreen(new RecallScreen());
                    ((RecallScreen) minecraft.gui.screen()).receive(response);
                  } else if (minecraft.gui.screen() instanceof RecallScreen screen)
                    screen.receive(response);
                  else if (response.action().equals("actors")) {
                    if (minecraft.gui.screen() != null
                        && !(minecraft.gui.screen() instanceof JournalScreen)) return;
                    var screen = new JournalScreen();
                    minecraft.gui.setScreen(screen);
                    screen.receive(response);
                  } else if (minecraft.gui.screen() instanceof CompanionScreen screen)
                    screen.receive(response);
                  else if (minecraft.gui.screen() instanceof JournalScreen screen)
                    screen.receive(response);
                }));
    bus.addListener(
        EntityRenderersEvent.RegisterRenderers.class,
        event ->
            event.registerEntityRenderer(
                NpcMod.COMPANION.get(),
                context -> {
                  if (renderer != null) renderer.close();
                  renderer = new AvatarActorRenderer<>(context);
                  return renderer;
                }));
    bus.addListener(
        RegisterMenuScreensEvent.class,
        event -> event.register(NpcMod.COMPANION_MENU.get(), CompanionScreen::new));
    NeoForge.EVENT_BUS.addListener(
        ClientPlayerNetworkEvent.LoggingOut.class,
        event -> {
          if (renderer != null) renderer.close();
        });
  }
}

package dev.avatarcraft.npc.client;

import dev.avatarcraft.api.client.AvatarActorRenderer;
import dev.avatarcraft.npc.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = bep_3davater_companion.ID, dist = Dist.CLIENT)
public final class NpcClient {
  private AvatarActorRenderer<CompanionEntity> renderer;

  public NpcClient(IEventBus bus, net.neoforged.fml.ModContainer container) {
    dev.avatarcraft.client.SeriesTabs.register(bep_3davater_companion.ID,
        net.minecraft.network.chat.Component.literal("Companion"), CompanionSettingsScreen::new);
    dev.avatarcraft.client.SeriesTabs.register(AiModelScreen.TAB_ID,
        net.minecraft.network.chat.Component.literal("AIモデル"), AiModelScreen::new);
    BlueprintPreview.install();
    NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> {
      JournalExportClient.tick();
      if (Boolean.getBoolean("avatarcraft.journalUiQa")) net.minecraft.client.Minecraft.getInstance().getWindow()
          .setTitle("B.E.P Companion Journal QA 26.3-0.10.0");
    });
    if (Boolean.getBoolean("avatarcraft.anchorUiQa"))
      NeoForge.EVENT_BUS.addListener(
          net.neoforged.neoforge.client.event.ClientTickEvent.Post.class,
          event ->
              net.minecraft.client.Minecraft.getInstance()
                  .getWindow()
                  .setTitle(
                      Boolean.getBoolean("avatarcraft.blueprintUiQa")
                          ? "bep_3davater_loader Blueprint QA"
                          : "bep_3davater_loader 0.7.0 Anchor QA"));
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
                  if (response.action().startsWith("journal_export_")) { JournalExportClient.receive(response); return; }
                  if (response.action().startsWith("series_")
                      && minecraft.gui.screen() instanceof CompanionSettingsScreen settings) {
                    settings.receive(response); return;
                  }
                  if (response.action().equals("blueprint")) {
                    BlueprintPreview.receive(response.data());
                    return;
                  }
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
                bep_3davater_companion.COMPANION.get(),
                context -> {
                  if (renderer != null) renderer.close();
                  renderer = new AvatarActorRenderer<>(context);
                  return renderer;
                }));
    bus.addListener(
        RegisterMenuScreensEvent.class,
        event -> event.register(bep_3davater_companion.COMPANION_MENU.get(), CompanionScreen::new));
    NeoForge.EVENT_BUS.addListener(
        ClientPlayerNetworkEvent.LoggingOut.class,
        event -> {
          JournalExportClient.reset();
          if (renderer != null) renderer.close();
        });
  }
}

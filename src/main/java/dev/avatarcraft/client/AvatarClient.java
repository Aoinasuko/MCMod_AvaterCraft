package dev.avatarcraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.avatarcraft.AvatarCraft;
import dev.avatarcraft.avatar.AvatarArchive;
import java.nio.file.*;
import net.minecraft.client.*;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = AvatarCraft.ID, dist = Dist.CLIENT)
public final class AvatarClient {
  private static final KeyMapping.Category CATEGORY =
      new KeyMapping.Category(Identifier.fromNamespaceAndPath("avatarcraft", "avatarcraft"));
  public static final KeyMapping OPEN =
      new KeyMapping("key.avatarcraft.open", InputConstants.KEY_LBRACKET, CATEGORY);
  private boolean restored;

  public AvatarClient(IEventBus bus) {
    bus.addListener(
        net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent.class,
        event ->
            event.register(
                dev.avatarcraft.multiplayer.ShareNetwork.Down.TYPE,
                (packet, context) -> ShareClient.receive(packet.message())));
    NeoForge.EVENT_BUS.addListener(
        ClientPlayerNetworkEvent.LoggingOut.class, e -> ShareClient.disconnect());
    bus.addListener(
        RegisterKeyMappingsEvent.class,
        e -> {
          e.registerCategory(CATEGORY);
          e.register(OPEN);
        });
    NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, this::tick);
    NeoForge.EVENT_BUS.addListener(ClientTickEvent.Pre.class, e -> AvatarQa.tick());
    NeoForge.EVENT_BUS.addListener(RenderPlayerEvent.Pre.class, AvatarRenderer::render);
    NeoForge.EVENT_BUS.addListener(RenderArmEvent.class, AvatarFirstPerson::render);
    AvatarLibrary.reload();
  }

  private void tick(ClientTickEvent.Post event) {
    var mc = Minecraft.getInstance();
    if (!restored && !AvatarLibrary.loading()) {
      restored = true;
      var file = AvatarLibrary.ROOT.resolve("selection.json");
      if (Files.exists(file))
        try {
          var id =
              AvatarArchive.GSON
                  .fromJson(Files.readString(file), com.google.gson.JsonObject.class)
                  .get("avatar")
                  .getAsString();
          for (var entry : AvatarLibrary.entries())
            if (entry.archive() != null && entry.archive().avatar().id.equals(id)) {
              AvatarSession.active = new AvatarSession(entry.archive());
              break;
            }
        } catch (Exception e) {
          System.getLogger("AvatarCraft")
              .log(System.Logger.Level.WARNING, "Cannot restore avatar", e);
        }
    }
    while (OPEN.consumeClick())
      if (mc.gui.screen() == null && mc.player != null) mc.gui.setScreen(new AvatarScreen());
    ShareClient.tick();
  }
}

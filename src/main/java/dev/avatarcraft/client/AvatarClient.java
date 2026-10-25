package dev.avatarcraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.avatarcraft.bep_3davater_loader;
import dev.avatarcraft.avatar.AvatarArchive;
import java.nio.file.*;
import net.minecraft.client.*;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = bep_3davater_loader.ID, dist = Dist.CLIENT)
public final class AvatarClient {
  private static final KeyMapping.Category CATEGORY =
      new KeyMapping.Category(Identifier.fromNamespaceAndPath("bep_3davater_loader", "bep_3davater_loader"));
  public static final KeyMapping OPEN =
      new KeyMapping("key.bep_3davater_loader.open", InputConstants.KEY_LBRACKET, CATEGORY);
  private static final KeyMapping[] EXPRESSIONS = dev.avatarcraft.avatar.ExpressionConfig.EVENTS.stream()
      .map(key -> new KeyMapping("key.bep_3davater_loader.expression." + key, InputConstants.UNKNOWN.getValue(), CATEGORY))
      .toArray(KeyMapping[]::new);
  private boolean restored;

  public AvatarClient(IEventBus bus) {
    SeriesTabs.register("bep_3davater_loader", net.minecraft.network.chat.Component.literal("Loader"), AvatarScreen::new);
    bus.addListener(
        RegisterKeyMappingsEvent.class,
        e -> {
          e.registerCategory(CATEGORY);
          e.register(OPEN);
          for (var key : EXPRESSIONS) e.register(key);
        });
    NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, this::tick);
    NeoForge.EVENT_BUS.addListener(RenderPlayerEvent.Pre.class, AvatarPreview::render);
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
          System.getLogger("bep_3davater_loader")
              .log(System.Logger.Level.WARNING, "Cannot restore avatar", e);
        }
    }
    while (OPEN.consumeClick())
      if (mc.gui.screen() == null && mc.player != null) mc.gui.setScreen(new AvatarScreen());
    for (int i = 0; i < EXPRESSIONS.length; i++)
      while (EXPRESSIONS[i].consumeClick()) {
        if (mc.gui.screen() != null || AvatarSession.active == null) continue;
        var selected = AvatarSession.active.expressionKeys;
        var key = dev.avatarcraft.avatar.ExpressionConfig.EVENTS.get(i);
        if (key.equals("idle") || selected.keys().equals(java.util.List.of(key))) selected.clear();
        else selected.set(key);
      }
  }
}

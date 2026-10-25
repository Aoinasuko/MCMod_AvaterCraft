package dev.avatarcraft.npc.client;

import com.google.gson.*;
import dev.avatarcraft.npc.NpcNetwork;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public final class AnchorScreen extends Screen {
  private final String token;
  private String value;
  private EditBox id;
  private Component message = Component.empty();
  private boolean success;

  public AnchorScreen(JsonObject data) {
    super(Component.translatable("block.bep_3davater_companion.avatar_anchor"));
    token = data.get("token").getAsString();
    value = data.get("id").getAsString();
  }

  private int panelWidth() {
    return Math.min(320, width - 16);
  }

  private int panelHeight() {
    return Math.min(128, height - 12);
  }

  private int left() {
    return (width - panelWidth()) / 2;
  }

  private int top() {
    return (height - panelHeight()) / 2;
  }

  @Override
  protected void init() {
    if (id != null) value = id.getValue();
    clearWidgets();
    int x = left() + 12, y = top();
    id = new EditBox(font, x, y + 39, panelWidth() - 24, 20, Component.translatable("anchor.id"));
    id.setMaxLength(32);
    id.setValue(value);
    addRenderableWidget(id);
    setInitialFocus(id);
    addRenderableWidget(
        Button.builder(
                Component.translatable("anchor.save"),
                b -> {
                  var data = new JsonObject();
                  data.addProperty("token", token);
                  data.addProperty("id", id.getValue());
                  ClientPacketDistributor.sendToServer(
                      new NpcNetwork.Request(-1, "anchor_save", data.toString()));
                })
            .bounds(x, y + 67, panelWidth() - 24, 20)
            .build());
  }

  public void receive(NpcNetwork.Response response) {
    if (!response.action().equals("anchor_result")) return;
    var data = JsonParser.parseString(response.data()).getAsJsonObject();
    if (!token.equals(data.get("token").getAsString())) return;
    success = data.get("ok").getAsBoolean();
    message = Component.translatable(data.get("message").getAsString());
    if (success) {
      value = data.get("id").getAsString();
      id.setValue(value);
    }
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
    int x = left(), y = top(), w = panelWidth();
    g.fill(x, y, x + w, y + panelHeight(), 0xffc6c6c6);
    g.fill(x + 2, y + 2, x + w - 2, y + 3, 0xffffffff);
    g.text(font, title, x + 12, y + 10, 0xff202020, false);
    g.text(font, Component.translatable("anchor.id"), x + 12, y + 26, 0xff404040, false);
    g.text(
        font,
        font.plainSubstrByWidth(message.getString(), w - 24),
        x + 12,
        y + 96,
        success ? 0xff205020 : 0xffa02020,
        false);
    super.extractRenderState(g, mx, my, dt);
  }
}

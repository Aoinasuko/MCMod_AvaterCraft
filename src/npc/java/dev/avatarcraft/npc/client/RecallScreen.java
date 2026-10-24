package dev.avatarcraft.npc.client;

import com.google.gson.*;
import dev.avatarcraft.npc.NpcNetwork;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** Server-owned, paged list. Selection sends only a UUID, never owner IDs or target coordinates. */
public final class RecallScreen extends Screen {
  private record Actor(String id, String name, String dimension, int x, int y, int z) {}

  private List<Actor> actors = List.of();
  private int page, pages = 1, total;
  private String message = "";

  public RecallScreen() {
    super(Component.translatable("item.avatarcraftnpc.recall_map"));
  }

  private int panelWidth() {
    return Math.min(400, width - 16);
  }

  private int left() {
    return (width - panelWidth()) / 2;
  }

  private void send(String action, String data) {
    ClientPacketDistributor.sendToServer(new NpcNetwork.Request(-1, action, data));
  }

  private void button(String text, int x, int y, int w, Runnable action) {
    addRenderableWidget(
        Button.builder(Component.literal(text), b -> action.run()).bounds(x, y, w, 20).build());
  }

  @Override
  protected void init() {
    clearWidgets();
    int x = left(), w = panelWidth();
    int rowHeight = Math.min(30, Math.max(20, (height - 100) / 5));
    for (int i = 0; i < actors.size(); i++) {
      var actor = actors.get(i);
      String label =
          font.plainSubstrByWidth(actor.name() + " / " + actor.dimension(), Math.max(30, w - 100));
      button(
          label + " →",
          x + 8,
          42 + i * rowHeight,
          w - 16,
          () -> {
            message = "呼び戻し中…";
            send("recall", actor.id());
          });
    }
    button(
        "<",
        x + 8,
        height - 52,
        26,
        () -> send("recall_list", Integer.toString(Math.max(0, page - 1))));
    button(
        ">",
        x + 38,
        height - 52,
        26,
        () -> send("recall_list", Integer.toString(Math.min(pages - 1, page + 1))));
    button("再読込", x + 72, height - 52, 64, () -> send("recall_list", Integer.toString(page)));
    button("閉じる", x + w - 72, height - 52, 64, this::onClose);
  }

  public void receive(NpcNetwork.Response response) {
    if (response.action().equals("recall_list")) {
      var data = JsonParser.parseString(response.data()).getAsJsonObject();
      page = data.get("page").getAsInt();
      pages = data.get("pages").getAsInt();
      total = data.get("total").getAsInt();
      var list = new ArrayList<Actor>();
      for (var element : data.getAsJsonArray("actors")) {
        var a = element.getAsJsonObject();
        list.add(
            new Actor(
                a.get("id").getAsString(),
                a.get("name").getAsString(),
                a.get("dimension").getAsString(),
                a.get("x").getAsInt(),
                a.get("y").getAsInt(),
                a.get("z").getAsInt()));
      }
      actors = List.copyOf(list);
      init();
    } else if (response.action().equals("recall_result") || response.action().equals("error"))
      message = response.data();
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float dt) {
    int x = left(), w = panelWidth();
    g.fill(x, 8, x + w, height - 8, 0xffc6c6c6);
    g.fill(x + 3, 11, x + w - 3, height - 11, 0xffd8d8d8);
    g.text(font, title, x + 8, 18, 0xff202020, false);
    g.text(
        font, "自分のNPC: " + total + "  " + (page + 1) + " / " + pages, x + 8, 30, 0xff404040, false);
    if (actors.isEmpty()) g.text(font, "召喚中のNPCはいません", x + 8, 60, 0xff404040, false);
    g.text(font, font.plainSubstrByWidth(message, w - 16), x + 8, height - 24, 0xff303060, false);
    super.extractRenderState(g, mouseX, mouseY, dt);
  }
}

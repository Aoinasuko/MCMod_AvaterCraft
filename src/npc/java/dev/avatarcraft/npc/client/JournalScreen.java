package dev.avatarcraft.npc.client;

import com.google.gson.*;
import dev.avatarcraft.npc.NpcNetwork;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public final class JournalScreen extends Screen {
  private record Actor(String id, String name) {}

  private final JournalView journal = new JournalView();
  private List<Actor> actors = List.of();
  private int selected;
  private String message = "";

  public JournalScreen() {
    super(Component.literal("仲間の記録"));
  }

  private void button(String label, int x, int y, int w, Runnable action) {
    addRenderableWidget(
        Button.builder(Component.literal(label), b -> action.run()).bounds(x, y, w, 20).build());
  }

  protected void init() {
    clearWidgets();
    button("<", 8, 28, 24, () -> change(-1));
    button(">", width - 32, 28, 24, () -> change(1));
    button("最新", 8, height - 48, 60, () -> request(""));
    var older =
        Button.builder(Component.literal("過去の記録"), b -> request(journal.cursor()))
            .bounds(76, height - 48, 90, 20)
            .build();
    older.active = !journal.cursor().isEmpty();
    addRenderableWidget(older);
    button("閉じる", width - 72, height - 48, 64, this::onClose);
    button("↑", width - 58, 54, 24, () -> journal.scroll(-5));
    button("↓", width - 30, 54, 24, () -> journal.scroll(5));
  }

  private void change(int direction) {
    if (actors.isEmpty()) return;
    selected = Math.floorMod(selected + direction, actors.size());
    request("");
  }

  private void request(String cursor) {
    if (actors.isEmpty()) return;
    var query = new JsonObject();
    query.addProperty("actor", actors.get(selected).id);
    query.addProperty("cursor", cursor);
    ClientPacketDistributor.sendToServer(new NpcNetwork.Request(-1, "journal", query.toString()));
    message = "読込中…";
  }

  public void receive(NpcNetwork.Response response) {
    if (response.action().equals("actors")) {
      var values = new ArrayList<Actor>();
      for (var element : JsonParser.parseString(response.data()).getAsJsonArray()) {
        var row = element.getAsJsonObject();
        values.add(new Actor(row.get("id").getAsString(), row.get("name").getAsString()));
      }
      actors = List.copyOf(values);
      selected = 0;
      message = actors.isEmpty() ? "記録はまだありません" : "";
      request("");
    } else if (response.action().equals("journal")) {
      var value = JsonParser.parseString(response.data()).getAsJsonObject();
      if (actors.isEmpty() || !value.get("actor").getAsString().equals(actors.get(selected).id))
        return;
      journal.load(response.data());
      message = "";
      init();
    } else message = response.data();
  }

  public boolean isPauseScreen() {
    return false;
  }

  public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float dt) {
    g.fill(0, 0, width, height, 0xffc8ccd4);
    g.text(font, title, 8, 10, 0xff182438, false);
    String name =
        actors.isEmpty()
            ? "仲間なし"
            : actors.get(selected).name + " (" + (selected + 1) + "/" + actors.size() + ")";
    g.text(font, font.plainSubstrByWidth(name, width - 84), 40, 34, 0xff182438, false);
    journal.draw(g, font, 8, 80, width - 16, Math.max(30, height - 138));
    g.text(font, font.plainSubstrByWidth(message, width - 16), 8, height - 18, 0xff182438, false);
    super.extractRenderState(g, x, y, dt);
  }
}

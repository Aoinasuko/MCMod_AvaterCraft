package dev.avatarcraft.npc.client;

import com.google.gson.*;
import net.minecraft.client.renderer.RenderPipelines;
import dev.avatarcraft.npc.NpcNetwork;
import dev.avatarcraft.npc.bep_3davater_companion;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public final class JournalScreen extends Screen {
  private record Actor(String id, String name) {}
  private static final String[] TABS = {"直近の会話", "直近のログ", "60分の統計"};
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm:ss").withZone(ZoneId.systemDefault());
  private List<Actor> actors = List.of();
  private JsonObject data;
  private JournalList list;
  private int selected, tab, left, top, panelWidth, panelHeight, ticks;
  private long sequence;
  private boolean waiting;
  private String message = "", summary = "";
  public JournalScreen() { super(Component.literal("アバター記録帳")); }
  private Button button(String label, int x, int y, int w, Runnable action) {
    return addRenderableWidget(Button.builder(Component.literal(label), b -> action.run()).bounds(x, y, w, 20).build());
  }
  @Override protected void init() {
    double scroll = list == null ? 0 : list.scrollAmount();
    clearWidgets(); panelWidth = Math.min(660, width - 16); panelHeight = height - 16;
    left = (width - panelWidth) / 2; top = 8;
    int tw = (panelWidth - 24) / 3;
    for (int i = 0; i < 3; i++) {
      final int page = i;
      var button = button(TABS[i], left + 8 + i * (tw + 4), top + 8, tw, () -> {
        tab = page; data = null; summary = ""; list = null; init(); request();
      });
      button.active = i != tab;
    }
    button("<", left + 8, top + 34, 24, () -> change(-1));
    button(">", left + panelWidth - 32, top + 34, 24, () -> change(1));
    list = addRenderableWidget(new JournalList(left + 8, top + 76, panelWidth - 16, Math.max(20, panelHeight - 122)));
    populate(); list.setScrollAmount(scroll);
    int y = top + panelHeight - 30;
    button("更新", left + 8, y, 45, this::request);
    if (tab == 2) button("行動ログをエクスポート", left + 58, y, Math.min(190, Math.max(110, panelWidth - 150)), () -> {
      if (!actors.isEmpty()) JournalExportClient.begin(UUID.fromString(actors.get(selected).id));
    });
    button("閉じる", left + panelWidth - 64, y, 56, this::onClose);
  }
  private void populate() {
    if (data == null) { list.line(actors.isEmpty() ? "記録はまだありません" : "記録を読込中…", ""); return; }
    if (tab == 2) {
      var report = data.getAsJsonObject("report"); var total = report.getAsJsonObject("total");
      summary = "60分合計  " + counts(total);
      var minutes = report.getAsJsonArray("minutes");
      for (int i = minutes.size() - 1; i >= 0; i--) {
        var minute = minutes.get(i).getAsJsonObject();
        list.line(time(minute.get("from").getAsLong()) + " ～ " + time(minute.get("to").getAsLong()), counts(minute.getAsJsonObject("counts")));
      }
      list.line("命令別・60分の合計", "");
      for (var command : report.getAsJsonObject("commands").entrySet()) {
        var c = command.getValue().getAsJsonObject();
        list.line(command.getKey(), "実行 " + c.get("executions").getAsLong() + " / 失敗 " + c.get("failures").getAsLong());
      }
    } else {
      var entries = data.getAsJsonArray("entries");
      summary = "最新 " + entries.size() + " / 100件";
      if (entries.isEmpty()) list.line("記録はまだありません", "");
      for (var e : entries) {
        var row = e.getAsJsonObject(); String kind = row.get("kind").getAsString();
        String text = row.get("text").getAsString();
        String body = tab == 0 ? text : text + (row.get("details").getAsString().equals("{}") ? "" : "\n" + row.get("details").getAsString());
        list.line(time(row.get("timestamp").getAsLong()) + " [" + kind + "] " + row.get("actorName").getAsString(), body);
      }
    }
  }
  private static String counts(JsonObject c) {
    return "計画 " + c.get("planning").getAsLong() + "  実行 " + c.get("executions").getAsLong()
        + "  失敗 " + c.get("failures").getAsLong() + "  リトライ " + c.get("retries").getAsLong()
        + "  上位AI " + c.get("handoffs").getAsLong();
  }
  private static String time(long millis) { return TIME.format(Instant.ofEpochMilli(millis)); }
  private void change(int direction) {
    if (actors.isEmpty()) return;
    selected = Math.floorMod(selected + direction, actors.size()); data = null; summary = ""; list = null; init(); request();
  }
  private void request() {
    if (actors.isEmpty()) return;
    var query = new JsonObject(); query.addProperty("actor", actors.get(selected).id);
    query.addProperty("sequence", ++sequence); query.addProperty("conversation", tab == 0);
    ClientPacketDistributor.sendToServer(new NpcNetwork.Request(-1, tab == 2 ? "journal_stats" : "journal_recent", query.toString()));
    waiting = true; ticks = 0; message = "読込中…";
  }
  @Override public void tick() {
    if (++ticks >= 100 && !waiting && !JournalExportClient.busy()) request();
    if (waiting && ticks >= 400) { waiting = false; message = "応答待ち"; }
  }
  public void receive(NpcNetwork.Response response) {
    if (response.action().equals("actors")) {
      var values = new ArrayList<Actor>();
      for (var e : JsonParser.parseString(response.data()).getAsJsonArray()) {
        var row = e.getAsJsonObject(); values.add(new Actor(row.get("id").getAsString(), row.get("name").getAsString()));
      }
      actors = List.copyOf(values); selected = 0;
      message = actors.isEmpty() ? "記録はまだありません" : "";
      if (actors.isEmpty()) { data = null; list = null; init(); } else request();
    } else if (response.action().equals("journal_recent") || response.action().equals("journal_stats")) {
      var value = JsonParser.parseString(response.data()).getAsJsonObject();
      if (actors.isEmpty() || !value.get("actor").getAsString().equals(actors.get(selected).id)
          || value.get("sequence").getAsLong() != sequence) return;
      data = value; waiting = false; message = ""; init();
    } else { waiting = false; message = response.data(); }
  }
  @Override public boolean isPauseScreen() { return false; }
  @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float dt) {
    g.fill(0, 0, width, height, 0x9920252d);
    g.pose().pushMatrix(); g.pose().translate(left, top); g.pose().scale(panelWidth / 256f, panelHeight / 256f);
    g.blit(RenderPipelines.GUI_TEXTURED, bep_3davater_companion.id("textures/gui/journal.png"), 0, 0, 0, 0, 256, 256, 256, 256);
    g.pose().popMatrix();
    String name = actors.isEmpty() ? "仲間の記録なし" : actors.get(selected).name + " (" + (selected + 1) + "/" + actors.size() + ")";
    g.text(font, font.plainSubstrByWidth(name, panelWidth - 90), left + 40, top + 40, 0xff182432, false);
    g.text(font, font.plainSubstrByWidth(summary, panelWidth - 22), left + 10, top + 62, 0xff263443, false);
    String status = JournalExportClient.status().isEmpty() ? message : JournalExportClient.status();
    g.text(font, font.plainSubstrByWidth(status, panelWidth - 22), left + 10, top + panelHeight - 43, 0xff263443, false);
    super.extractRenderState(g, x, y, dt);
  }
}

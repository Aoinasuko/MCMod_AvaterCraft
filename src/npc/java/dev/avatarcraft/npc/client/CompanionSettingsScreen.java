package dev.avatarcraft.npc.client;

import com.google.gson.*;
import dev.avatarcraft.client.*;
import dev.avatarcraft.npc.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** Owned companions across dimensions, with explicit model/personality saves. */
public final class CompanionSettingsScreen extends SeriesScreen {
  private JsonArray actors = new JsonArray();
  private List<Personality> profiles = List.of(Personality.preset("tortobu"));
  private int page, pages = 1, total;
  private UUID actor;
  private UUID pendingActor;
  private String draft = "", message = "読込中…", modelQuery = "";
  private boolean requested;
  private ModelList models;

  public CompanionSettingsScreen() { super(bep_3davater_companion.ID); }
  private void send(String action, String data) {
    ClientPacketDistributor.sendToServer(new NpcNetwork.Request(-1, action, data));
  }
  @Override protected void init() {
    clearWidgets(); initTabs(); if (models != null) { models.close(); models = null; }
    if (!requested) {
      requested = true; send("series_list", "0"); send("series_profiles", "");
    }
    if (actor == null) buildList(); else buildEditor();
  }
  private void buildList() {
    var list = addRenderableWidget(new ControlList(12, CONTENT_TOP + 14, width - 24, Math.max(35, height - CONTENT_TOP - 68)));
    for (var element : actors) {
      var row = element.getAsJsonObject();
      var id = UUID.fromString(row.get("id").getAsString());
      var button = Button.builder(Component.literal(row.get("name").getAsString() + " / " + row.get("dimension").getAsString()
          + (row.get("loaded").getAsBoolean() ? "" : "（未読込）")), b -> {
        pendingActor = id;
        var query = new JsonObject(); query.addProperty("id", id.toString()); send("series_open", query.toString());
        message = "NPCの設定を読込中…";
      }).bounds(0, 0, width - 50, 22).build();
      list.control(button);
    }
    button("<", 12, height - 27, 26, () -> send("series_list", Integer.toString(Math.max(0, page - 1))));
    button(">", 42, height - 27, 26, () -> send("series_list", Integer.toString(Math.min(pages - 1, page + 1))));
    button("再読込", 76, height - 27, 80, () -> send("series_list", Integer.toString(page)));
    button("閉じる", width - 92, height - 27, 80, this::onClose);
  }
  private void buildEditor() {
    int leftWidth = Math.max(130, (width - 36) / 2), right = leftWidth + 24, rightWidth = width - right - 12;
    String profileName;
    try { profileName = Personality.parse(draft).name; }
    catch (RuntimeException e) { profileName = "JSONを編集中"; }
    var modelSearch = new EditBox(font, 12, CONTENT_TOP + 12, leftWidth, 18, Component.literal("モデルを検索"));
    modelSearch.setValue(modelQuery); modelSearch.setHint(Component.literal("モデルを検索"));
    modelSearch.setResponder(value -> { modelQuery = value; populateModels(); }); addRenderableWidget(modelSearch);
    models = addRenderableWidget(new ModelList(8, CONTENT_TOP + 36, leftWidth + 8, Math.max(32, height - CONTENT_TOP - 92), entry -> {
      try { var value = Personality.parse(draft); value.avatar = entry.archive().avatar().id; draft = value.json();
        message = "モデルを選択しました。保存で反映します"; refreshEditorText(); }
      catch (RuntimeException e) { message = "性格JSONを修正してからモデルを変更してください"; }
    }));
    var preset = button("性格: " + profileName, right, CONTENT_TOP + 12, rightWidth, () -> {
      try {
        var current = Personality.parse(draft); int index = -1;
        for (int i = 0; i < profiles.size(); i++) if (profiles.get(i).id.equals(current.id)) index = i;
        var next = Personality.parse(profiles.get(Math.floorMod(index + 1, profiles.size())).json());
        next.avatar = current.avatar; draft = next.json(); init();
      } catch (RuntimeException e) { message = "性格JSONを修正してください"; }
    });
    preset.setTooltip(Tooltip.create(Component.literal("クリックして性格プリセットを切り替えます")));
    editor = MultiLineEditBox.builder().setX(right).setY(CONTENT_TOP + 38).setShowDecorations(false)
        .build(font, rightWidth, Math.max(32, height - CONTENT_TOP - 94), Component.literal("性格JSON"));
    editor.setCharacterLimit(16384); editor.setValue(formattedOrDraft(draft)); editor.setScrollAmount(0);
    editor.setValueListener(value -> draft = value); addRenderableWidget(editor);
    int footerWidth = (width - 40) / 3;
    button("一覧へ戻る", 12, height - 27, footerWidth, () -> { actor = null; pendingActor = null; draft = ""; message = ""; send("series_list", Integer.toString(page)); init(); });
    button("モデル・性格を保存", 20 + footerWidth, height - 27, footerWidth, () -> {
      try {
        var value = Personality.parse(draft); var data = new JsonObject(); data.addProperty("id", actor.toString());
        data.add("personality", JsonParser.parseString(value.json())); send("series_save", data.toString()); message = "保存中…";
      } catch (RuntimeException e) { message = "性格JSONを確認してください"; }
    });
    button("閉じる", 28 + footerWidth * 2, height - 27, footerWidth, this::onClose); populateModels();
  }
  private MultiLineEditBox editor;
  private static String pretty(String json) { return new GsonBuilder().setPrettyPrinting().create().toJson(JsonParser.parseString(json)); }
  private static String formattedOrDraft(String json) {
    try { return pretty(json); } catch (RuntimeException e) { return json; }
  }
  private void refreshEditorText() { if (editor != null) { editor.setValue(pretty(draft)); editor.setScrollAmount(0); } }
  private void populateModels() {
    if (models == null) return;
    String avatar = ""; try { avatar = Personality.parse(draft).avatar; } catch (RuntimeException ignored) {}
    models.entries(AvatarLibrary.entries().stream().filter(e -> e.name().toLowerCase(Locale.ROOT).contains(modelQuery.toLowerCase(Locale.ROOT))).toList(), avatar);
  }
  public void receive(NpcNetwork.Response response) {
    switch (response.action()) {
      case "series_list" -> {
        var data = JsonParser.parseString(response.data()).getAsJsonObject(); actors = data.getAsJsonArray("actors");
        page = data.get("page").getAsInt(); pages = data.get("pages").getAsInt(); total = data.get("total").getAsInt();
        if (actor == null) { message = total == 0 ? "召喚済みの自分のNPCはいません" : "NPCをクリックしてモデル・性格を変更します"; init(); }
      }
      case "series_profile", "series_saved" -> {
        var data = JsonParser.parseString(response.data()).getAsJsonObject();
        UUID id = UUID.fromString(data.get("id").getAsString());
        if (response.action().equals("series_profile") && !id.equals(pendingActor)) return;
        if (response.action().equals("series_saved") && !id.equals(actor)) return;
        actor = id; draft = data.get("personality").toString(); message = response.action().equals("series_saved") ? "保存しました" : ""; init();
      }
      case "series_profiles" -> {
        var values = new ArrayList<Personality>();
        for (var row : JsonParser.parseString(response.data()).getAsJsonArray()) values.add(Personality.parse(row.toString()));
        if (!values.isEmpty()) profiles = List.copyOf(values);
      }
      case "series_error", "error" -> message = response.data();
      default -> {}
    }
  }
  @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
    background(g);
    if (actor == null) g.text(font, "自分のNPC " + total + "体 / " + (page + 1) + "/" + pages, 14, CONTENT_TOP, 0xffc1cde0);
    g.text(font, font.plainSubstrByWidth(message, width - 24), 12, height - 46, 0xffffdc9b);
    super.extractRenderState(g, mx, my, dt);
  }
  @Override public void removed() { if (models != null) models.close(); }
}

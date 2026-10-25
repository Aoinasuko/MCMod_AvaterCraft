package dev.avatarcraft.client;

import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** Loader catalog and optional Player Model dressing room. Browsing never auto-equips. */
public class AvatarScreen extends SeriesScreen {
  private final boolean dressingRoom;
  private ModelList models;
  private String selectedId = "", query = "", message = "";
  private List<AvatarLibrary.Entry> snapshot = List.of();
  public AvatarScreen() { this("bep_3davater_loader", false); }
  protected AvatarScreen(String tabId, boolean dressingRoom) { super(tabId); this.dressingRoom = dressingRoom; }

  @Override protected void init() {
    clearWidgets(); initTabs();
    var search = new EditBox(font, 16, CONTENT_TOP, width - 32, 18, Component.literal("モデルを検索"));
    search.setValue(query); search.setHint(Component.literal("モデルを検索"));
    search.setResponder(value -> { query = value; populate(); }); addRenderableWidget(search);
    if (models != null) models.close();
    models = addRenderableWidget(new ModelList(8, CONTENT_TOP + 24, width - 16,
        Math.max(40, height - CONTENT_TOP - 80), entry -> { selectedId = entry.archive().avatar().id; updateActions(); }));
    int bw = (width - 40) / 4, y = height - 27;
    if (dressingRoom) {
      button("選択したモデルを着る", 8, y, bw, () -> {
        var entry = selected(); if (entry == null) return;
        try { AvatarSession.select(entry.archive()); message = "着用しました: " + entry.name(); }
        catch (Exception e) { message = e.getMessage(); }
      });
      button("着用を解除", 16 + bw, y, bw, () -> {
        try { AvatarSession.select(null); message = "着用を解除しました"; }
        catch (Exception e) { message = e.getMessage(); }
      });
    } else {
      button("表情・シェイプキー", 8, y, bw, () -> {
        var entry = selected(); if (entry != null) minecraft.gui.setScreen(new ExpressionScreen(this, entry.archive()));
      });
      button("表情キーJSON編集", 16 + bw, y, bw, () -> {
        var entry = selected(); if (entry != null) minecraft.gui.setScreen(new ExpressionJsonScreen(this, entry.archive()));
      });
    }
    button("再読込", 24 + bw * 2, y, bw, () -> { AvatarLibrary.reload(); message = "読込中…"; });
    button("閉じる", 32 + bw * 3, y, bw, this::onClose);
    populate();
  }
  private AvatarLibrary.Entry selected() { return AvatarLibrary.resolve(selectedId); }
  private void updateActions() {
    for (var child : children()) if (child instanceof net.minecraft.client.gui.components.Button b
        && (b.getMessage().getString().equals("選択したモデルを着る")
            || b.getMessage().getString().equals("表情・シェイプキー") || b.getMessage().getString().equals("表情キーJSON編集")))
      b.active = selected() != null;
  }
  private void populate() {
    if (models == null) return;
    snapshot = AvatarLibrary.entries();
    var shown = snapshot.stream().filter(e -> (e.name() + " " + (e.archive() == null ? "" : e.archive().avatar().id))
        .toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList();
    models.entries(shown, selectedId); updateActions();
  }
  @Override public void tick() { if (snapshot != AvatarLibrary.entries()) populate(); }
  @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
    background(g);
    String text = message.isEmpty() ? (dressingRoom ? "モデルを選んで「着る」で反映します" : "編集するモデルを選択してください") : message;
    g.text(font, font.plainSubstrByWidth(text, width - 24), 12, height - 46, 0xffffdc9b);
    super.extractRenderState(g, mx, my, dt);
  }
  @Override public void removed() { if (models != null) models.close(); }
}

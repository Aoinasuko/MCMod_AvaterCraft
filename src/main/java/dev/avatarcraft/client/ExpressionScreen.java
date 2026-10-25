package dev.avatarcraft.client;

import dev.avatarcraft.avatar.*;
import java.util.*;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Searchable grouped morph controls with a continuous draggable scrollbar. */
public final class ExpressionScreen extends Screen {
  private final Screen parent;
  private final AvatarSession session;
  private final List<ExpressionShapes.Shape> shapes;
  private String event = "blink", query = "", message = "", minText, maxText;
  private boolean headOnly;
  private ControlList controls;
  private int previewWidth, formLeft, formWidth, footerY;
  private int matchCount;

  public ExpressionScreen(Screen parent, AvatarArchive archive) {
    super(Component.literal("表情設定 / シェイプキー"));
    this.parent = parent;
    try { session = new AvatarSession(archive, true); }
    catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    shapes = ExpressionShapes.collect(archive.avatar());
    headOnly = shapes.stream().anyMatch(ExpressionShapes.Shape::head);
    minText = String.valueOf(session.config.blinkMinSeconds);
    maxText = String.valueOf(session.config.blinkMaxSeconds);
  }
  private Button button(String label, int x, int y, int w, Runnable action) {
    return addRenderableWidget(Button.builder(Component.literal(label), b -> action.run()).bounds(x, y, w, 20).build());
  }
  @Override protected void init() {
    clearWidgets();
    previewWidth = Math.max(82, Math.min(width / 3, width - 224));
    formLeft = previewWidth + 12; formWidth = width - 12 - formLeft; footerY = height - 26;
    var keys = new ArrayList<>(session.config.events.keySet());
    button("表情キー: " + event, formLeft, 34, formWidth / 2 - 3, () -> {
      event = keys.get(Math.floorMod(keys.indexOf(event) + 1, keys.size()));
      if (session.preview != null) session.preview = event;
      init();
    });
    button(headOnly ? "表示: 頭部のみ" : "表示: すべて", formLeft + formWidth / 2 + 3, 34, formWidth / 2 - 3,
        () -> { headOnly = !headOnly; init(); });
    var search = new EditBox(font, formLeft, 58, formWidth, 18, Component.literal("シェイプキーを検索"));
    search.setValue(query); search.setHint(Component.literal("シェイプキー・メッシュ名を検索"));
    search.setResponder(value -> { query = value; populate(); }); addRenderableWidget(search);
    controls = addRenderableWidget(new ControlList(formLeft, 82, formWidth, Math.max(28, footerY - 126)));
    populate();
    int timingY = footerY - 24;
    var min = new EditBox(font, formLeft, timingY, 48, 18, Component.literal("まばたき最小秒"));
    min.setValue(minText); min.setResponder(value -> minText = value); addRenderableWidget(min);
    var max = new EditBox(font, formLeft + 70, timingY, 48, 18, Component.literal("まばたき最大秒"));
    max.setValue(maxText); max.setResponder(value -> maxText = value); addRenderableWidget(max);
    button(session.preview == null ? "プレビュー" : "終了", 8, timingY, previewWidth - 16, () -> {
      session.preview = session.preview == null ? event : null; init();
    });
    button("保存", formLeft, footerY, (formWidth - 6) / 2, () -> {
      try {
        session.config.blinkMinSeconds = Double.parseDouble(minText);
        session.config.blinkMaxSeconds = Double.parseDouble(maxText);
        session.saveExpressions(); minecraft.gui.setScreen(parent);
      } catch (Exception e) { message = "保存できません: " + e.getMessage(); }
    });
    button("キャンセル", formLeft + (formWidth + 6) / 2, footerY, (formWidth - 6) / 2, this::onClose);
  }
  private void populate() {
    if (controls == null) return;
    controls.clearEntries(); controls.setScrollAmount(0);
    String group = "";
    matchCount = 0;
    for (var shape : shapes) {
      if (headOnly && !shape.head() || !shape.key().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) continue;
      String next = (shape.head() ? "頭部 / " : "その他 / ") + shape.mesh();
      if (!next.equals(group)) { controls.heading(next); group = next; }
      float weight = session.config.events.get(event).getOrDefault(shape.key(), 0f);
      controls.control(new AbstractSliderButton(0, 0, formWidth - 24, 22, Component.empty(), weight / 100d) {
        { updateMessage(); setTooltip(Tooltip.create(Component.literal(shape.key()))); }
        @Override protected void updateMessage() {
          setMessage(Component.literal(font.plainSubstrByWidth(shape.name(), Math.max(20, getWidth() - 42)) + " " + Math.round(value * 100)));
        }
        @Override protected void applyValue() { session.config.events.get(event).put(shape.key(), (float) (value * 100)); }
      });
      matchCount++;
    }
    if (matchCount == 0) controls.heading("該当するシェイプキーはありません");
  }
  @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
    g.fill(0, 0, width, height, 0xe018202b);
    g.text(font, title, 12, 14, 0xffeef4ff);
    if (minecraft.player != null) AvatarPreview.extractEntityInInventoryFollowsMouse(g, 8, 35,
        previewWidth - 4, height - 70, Math.max(25, Math.min((height - 115) / 2, (previewWidth - 16) / 2)),
        .0625f, mx, my, minecraft.player, session);
    g.text(font, "まばたき（秒） " + matchCount + "件", formLeft, footerY - 38, 0xffc1cde0);
    g.text(font, "〜", formLeft + 54, footerY - 19, 0xffc1cde0);
    if (!message.isEmpty()) g.text(font, font.plainSubstrByWidth(message, width - 16), 8, height - 39, 0xffffb0a0);
    super.extractRenderState(g, mx, my, dt);
  }
  @Override public void onClose() { minecraft.gui.setScreen(parent); }
  @Override public void removed() { session.close(); }
  @Override public boolean isPauseScreen() { return false; }
}

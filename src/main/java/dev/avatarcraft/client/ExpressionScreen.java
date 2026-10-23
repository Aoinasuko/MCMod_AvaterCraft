package dev.avatarcraft.client;

import dev.avatarcraft.avatar.*;
import java.util.*;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ExpressionScreen extends Screen {
  private final Screen parent;
  private final AvatarSession session;
  private final ExpressionConfig original;
  private String event = "blink", query = "", message = "";
  private int page;
  private boolean saved;
  private EditBox min, max, search;
  private String minText, maxText;
  private boolean restoreSearchFocus;
  private int navigationY, timingLabelY, timingInputY, footerY, formLeft, formWidth, previewWidth;
  private int pageCount;

  public ExpressionScreen(Screen parent) {
    super(Component.literal("表情設定 / シェイプキー"));
    this.parent = parent;
    session = AvatarSession.active;
    original = session.config.copy();
    minText = String.valueOf(session.config.blinkMinSeconds);
    maxText = String.valueOf(session.config.blinkMaxSeconds);
  }

  private void button(String text, int x, int y, int w, Runnable action) {
    addRenderableWidget(
        Button.builder(Component.literal(text), b -> action.run()).bounds(x, y, w, 20).build());
  }

  @Override
  protected void init() {
    clearWidgets();
    previewWidth = Math.max(82, Math.min(width / 3, width - 224));
    formLeft = previewWidth + 12;
    formWidth = width - 12 - formLeft;
    footerY = height - 26;
    timingInputY = footerY - 38;
    timingLabelY = timingInputY - 14;
    navigationY = timingLabelY - 26;
    int left = formLeft, right = width - 12, contentWidth = right - left;
    int bw = (contentWidth - 12) / 4;
    String[] labels = {"通常", "まばたき", "食事", "被ダメージ"};
    for (int i = 0; i < 4; i++) {
      String key = ExpressionConfig.EVENTS.get(i);
      button(
          (event.equals(key) ? "● " : "") + labels[i],
          left + i * (bw + 4),
          34,
          bw,
          () -> {
            event = key;
            if (session.preview != null) session.preview = event;
            init();
          });
    }
    search = new EditBox(font, left, 58, contentWidth, 18, Component.literal("シェイプキーを検索"));
    search.setValue(query);
    search.setHint(Component.literal("シェイプキーを検索"));
    search.setResponder(
        s -> {
          query = s;
          page = 0;
          restoreSearchFocus = true;
          rebuild();
        });
    addRenderableWidget(search);
    if (restoreSearchFocus) {
      setInitialFocus(search);
      restoreSearchFocus = false;
    }
    var keys =
        session.config.shapes(session.archive.avatar()).stream()
            .filter(k -> k.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))
            .toList();
    int rows = Math.max(1, (navigationY - 80) / 28);
    pageCount = Math.max(1, (keys.size() + rows - 1) / rows);
    page = Math.clamp(page, 0, Math.max(0, (keys.size() - 1) / rows));
    var visible = keys.stream().skip((long) page * rows).limit(rows).toList();
    for (int i = 0; i < visible.size(); i++) {
      String key = visible.get(i);
      int y = 80 + i * 28;
      float current = session.config.events.get(event).getOrDefault(key, 0f);
      addRenderableWidget(
          new AbstractSliderButton(left, y, contentWidth, 24, Component.empty(), current / 100d) {
            {
              updateMessage();
            }

            @Override
            protected void updateMessage() {
              String label = key.substring(key.lastIndexOf('/') + 1);
              setMessage(
                  Component.literal(
                      font.plainSubstrByWidth(label, Math.max(40, contentWidth - 48))
                          + " "
                          + Math.round(value * 100)));
            }

            @Override
            protected void applyValue() {
              session.config.events.get(event).put(key, (float) (value * 100));
            }
          });
    }
    button(
        "<",
        left,
        navigationY,
        22,
        () -> {
          page--;
          init();
        });
    button(
        ">",
        left + 26,
        navigationY,
        22,
        () -> {
          page++;
          init();
        });
    button(
        session.preview == null ? "プレビュー" : "プレビュー終了",
        left + Math.min(104, contentWidth / 2),
        navigationY,
        contentWidth - Math.min(104, contentWidth / 2),
        () -> {
          session.preview = session.preview == null ? event : null;
          init();
        });
    min = new EditBox(font, left, timingInputY, 54, 18, Component.literal("まばたき最小秒"));
    min.setValue(minText);
    min.setResponder(s -> minText = s);
    addRenderableWidget(min);
    max = new EditBox(font, left + 78, timingInputY, 54, 18, Component.literal("まばたき最大秒"));
    max.setValue(maxText);
    max.setResponder(s -> maxText = s);
    addRenderableWidget(max);
    button(
        "保存",
        left,
        footerY,
        (contentWidth - 6) / 2,
        () -> {
          try {
            session.config.blinkMinSeconds = Double.parseDouble(min.getValue());
            session.config.blinkMaxSeconds = Double.parseDouble(max.getValue());
            session.saveExpressions();
            saved = true;
            session.preview = null;
            minecraft.gui.setScreen(parent);
          } catch (Exception e) {
            message = "設定を保存できません: " + e.getMessage();
          }
        });
    button(
        "キャンセル",
        left + (contentWidth - 6) / 2 + 6,
        footerY,
        contentWidth - (contentWidth - 6) / 2 - 6,
        this::onClose);
  }

  private void rebuild() {
    minecraft.execute(
        () -> {
          if (minecraft.gui.screen() == this) init();
        });
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
    g.fill(0, 0, width, height, 0xf018202b);
    // Keep the heading in the left side of the toast-free title area.
    g.text(
        font,
        font.plainSubstrByWidth(title.getString(), Math.max(120, width - 184)),
        12,
        14,
        0xffeef4ff);
    int pw = previewWidth;
    if (minecraft.player != null)
      AvatarPreview.extractEntityInInventoryFollowsMouse(
          g,
          8,
          35,
          pw - 4,
          height - 58,
          Math.max(25, Math.min((height - 108) / 2, (pw - 16) / 2)),
          .0625f,
          mx,
          my,
          minecraft.player);
    int left = formLeft;
    g.text(font, "まばたき間隔（秒）", left, timingLabelY, 0xffc1cde0);
    g.text(font, "〜", left + 61, timingInputY + 5, 0xffc1cde0);
    if (formWidth >= 228) g.text(font, "最小 / 最大", left + 144, timingInputY + 5, 0xff90a1ba);
    String pageLabel = (page + 1) + "/" + pageCount;
    int pageLabelWidth = Math.min(46, Math.min(104, formWidth / 2) - 52);
    if (pageLabelWidth > 12)
      g.text(
          font,
          font.plainSubstrByWidth(pageLabel, pageLabelWidth),
          left + 54,
          navigationY + 6,
          0xffb8c6dc);
    if (!message.isEmpty())
      g.text(font, font.plainSubstrByWidth(message, pw - 16), 8, height - 40, 0xffffb0a0);
    super.extractRenderState(g, mx, my, dt);
  }

  @Override
  public void onClose() {
    minecraft.gui.setScreen(parent);
  }

  @Override
  public void removed() {
    session.preview = null;
    if (!saved) session.config = original;
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}

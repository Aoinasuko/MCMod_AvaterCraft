package dev.avatarcraft.client;

import java.util.*;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class AvatarScreen extends Screen {
  private int page;
  private List<AvatarLibrary.Entry> shown = List.of();
  private final Map<String, AvatarTextures> previews = new HashMap<>();
  private String message = "";
  private boolean waitingForReload;

  public AvatarScreen() {
    super(Component.translatable("screen.avatarcraft.library"));
  }

  private void button(String text, int x, int y, int w, Runnable action) {
    addRenderableWidget(
        Button.builder(Component.literal(text), b -> action.run()).bounds(x, y, w, 20).build());
  }

  @Override
  protected void init() {
    clearWidgets();
    int rows = Math.max(1, (height - 100) / 72);
    var entries = AvatarLibrary.entries();
    page = Math.clamp(page, 0, Math.max(0, (entries.size() - 1) / rows));
    shown = entries.stream().skip((long) page * rows).limit(rows).toList();
    for (int i = 0; i < shown.size(); i++) {
      var e = shown.get(i);
      int y = 40 + i * 72;
      var btn =
          Button.builder(
                  Component.literal(e.name()),
                  b -> {
                    try {
                      AvatarSession.select(e.archive());
                      message = "選択しました: " + e.name();
                    } catch (Exception ex) {
                      message = ex.getMessage();
                    }
                  })
              .bounds(86, y + 8, Math.max(80, width - 104), 24)
              .build();
      btn.active = e.archive() != null;
      addRenderableWidget(btn);
    }
    int bw = Math.max(40, (width - 36) / 4);
    int bottom = height - 28;
    button(
        "標準モデル",
        8,
        bottom,
        bw,
        () -> {
          try {
            AvatarSession.select(null);
            message = "標準モデルに戻しました";
          } catch (Exception e) {
            message = e.getMessage();
          }
        });
    button(
        "表情設定",
        16 + bw,
        bottom,
        bw,
        () -> {
          if (AvatarSession.active != null) minecraft.gui.setScreen(new ExpressionScreen(this));
          else message = "先にアバターを選択してください";
        });
    button(
        "再読込",
        24 + bw * 2,
        bottom,
        bw,
        () -> {
          AvatarLibrary.reload();
          waitingForReload = true;
          message = "読込中…";
        });
    button("閉じる", 32 + bw * 3, bottom, bw, this::onClose);
    button(
        "<",
        8,
        12,
        24,
        () -> {
          page--;
          init();
        });
    button(
        ">",
        width - 32,
        12,
        24,
        () -> {
          page++;
          init();
        });
  }

  @Override
  public void tick() {
    if (waitingForReload && !AvatarLibrary.loading()) {
      waitingForReload = false;
      previews.values().forEach(AvatarTextures::close);
      previews.clear();
      message = "再読込しました: " + AvatarLibrary.entries().size() + "件";
      init();
      return;
    }
    if (!AvatarLibrary.loading() && !AvatarLibrary.entries().containsAll(shown)
        || !AvatarLibrary.loading() && shown.isEmpty() && !AvatarLibrary.entries().isEmpty())
      init();
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
    g.fill(0, 0, width, height, 0xf018202b);
    g.text(
        font,
        font.plainSubstrByWidth(title.getString(), Math.max(100, width - 224)),
        40,
        15,
        0xffeef4ff);
    for (int i = 0; i < shown.size(); i++) {
      var e = shown.get(i);
      int y = 40 + i * 72;
      g.fill(8, y, width - 8, y + 66, 0xff293548);
      if (e.archive() != null) {
        var a = e.archive();
        if (a.images().containsKey("preview.png")) {
          var textures = previews.computeIfAbsent(a.avatar().id, k -> new AvatarTextures(a));
          g.blit(textures.get("preview.png"), 12, y + 3, 72, y + 63, 0, 1, 0, 1);
        }
        g.text(
            font,
            Component.literal(
                a.avatar().meshes.length
                    + " meshes · "
                    + a.avatar().humanoid.size()
                    + " humanoid bones"),
            88,
            y + 39,
            0xffb8c6dc);
      } else
        g.text(
            font,
            font.plainSubstrByWidth(String.valueOf(e.error()), Math.max(40, width - 104)),
            88,
            y + 39,
            0xffffa8a8);
    }
    if (shown.isEmpty())
      g.centeredText(
          font,
          Component.literal(
              AvatarLibrary.loading()
                  ? "アバターを読み込んでいます…"
                  : "config/avatarcraft/avatars に .avc を配置してください"),
          width / 2,
          70,
          0xffbbc9df);
    g.text(
        font,
        font.plainSubstrByWidth(
            message.isEmpty() ? ShareClient.status() : message + " / " + ShareClient.status(),
            width - 16),
        8,
        height - 43,
        0xffffdc9b);
    super.extractRenderState(g, mx, my, dt);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  @Override
  public void removed() {
    for (var p : previews.values()) p.close();
    previews.clear();
  }
}

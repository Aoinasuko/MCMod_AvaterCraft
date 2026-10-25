package dev.avatarcraft.client;

import com.google.gson.GsonBuilder;
import dev.avatarcraft.avatar.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Validated JSON editing for named expression keys, independent of the worn model. */
public final class ExpressionJsonScreen extends Screen {
  private final Screen parent;
  private final AvatarSession session;
  private String text, message = "";
  public ExpressionJsonScreen(Screen parent, AvatarArchive archive) {
    super(Component.literal("表情キーJSON / " + archive.avatar().name)); this.parent = parent;
    try { session = new AvatarSession(archive, true); }
    catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    text = new GsonBuilder().setPrettyPrinting().create().toJson(session.config);
  }
  @Override protected void init() {
    var editor = MultiLineEditBox.builder().setX(12).setY(35).setShowDecorations(false)
        .build(font, width - 24, height - 104, Component.literal("表情キーJSON"));
    editor.setCharacterLimit(1 << 20); editor.setValue(text); editor.setScrollAmount(0);
    editor.setValueListener(value -> { text = value; message = ""; }); addRenderableWidget(editor);
    addRenderableWidget(Button.builder(Component.literal("検証して保存"), b -> {
      try {
        var config = AvatarArchive.GSON.fromJson(text, ExpressionConfig.class);
        if (config == null) throw new IllegalArgumentException("JSONが空です");
        config.validate(session.archive.avatar()); session.config = config; session.saveExpressions();
        minecraft.gui.setScreen(parent);
      } catch (Exception e) { message = "保存できません: " + e.getMessage(); }
    }).bounds(width / 2 - 154, height - 27, 150, 20).build());
    addRenderableWidget(Button.builder(Component.literal("キャンセル"), b -> onClose()).bounds(width / 2 + 4, height - 27, 150, 20).build());
  }
  @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
    g.fill(0, 0, width, height, 0xe018202b); g.text(font, title, 12, 14, 0xffeef4ff);
    g.text(font, font.plainSubstrByWidth(message, width - 24), 12, height - 50, 0xffffb0a0);
    super.extractRenderState(g, mx, my, dt);
  }
  @Override public void onClose() { minecraft.gui.setScreen(parent); }
  @Override public void removed() { session.close(); }
  @Override public boolean isPauseScreen() { return false; }
}

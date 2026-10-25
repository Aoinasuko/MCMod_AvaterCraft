package dev.avatarcraft.npc.client;

import dev.avatarcraft.client.*;
import dev.avatarcraft.npc.ai.AiModelCatalog;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.Component;

/** Searchable provider catalog; picking a model edits the pending connection form only. */
public final class AiModelPickerScreen extends SeriesScreen {
  private final AiModelScreen parent;
  private final List<AiModelCatalog.Model> models;
  private final Consumer<String> selected;
  private final boolean complex;
  private String query = "";
  private ControlList list;
  private int count;
  public AiModelPickerScreen(AiModelScreen parent, List<AiModelCatalog.Model> models, boolean complex, Consumer<String> selected) {
    super(AiModelScreen.TAB_ID); this.parent = parent; this.models = List.copyOf(models); this.selected = selected; this.complex = complex;
  }
  @Override protected void init() {
    clearWidgets(); initTabs();
    var search = new EditBox(font, 14, CONTENT_TOP + 16, width - 28, 18, Component.literal("モデルを検索"));
    search.setValue(query); search.setHint(Component.literal("モデル名・IDを検索"));
    search.setResponder(value -> { query = value; populate(); }); addRenderableWidget(search);
    list = addRenderableWidget(new ControlList(12, CONTENT_TOP + 40, width - 24, Math.max(30, height - CONTENT_TOP - 98)));
    button("接続設定に戻る", width / 2 - 90, height - 27, 180, this::onClose); populate();
  }
  private void populate() {
    if (list == null) return;
    list.clearEntries(); list.setScrollAmount(0); count = 0;
    for (var model : models) {
      if (!(model.id() + " " + model.label()).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) continue;
      var text = model.label().equals(model.id()) ? model.id() : model.label() + " / " + model.id();
      var button = Button.builder(Component.literal(text), b -> { selected.accept(model.id()); minecraft.gui.setScreen(parent); })
          .bounds(0, 0, width - 48, 22).build();
      button.setTooltip(Tooltip.create(Component.literal(model.id()))); list.control(button); count++;
    }
    if (count == 0) list.heading("検索条件に合うモデルがありません");
  }
  @Override public void onClose() { minecraft.gui.setScreen(parent); }
  @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
    background(g); g.text(font, complex ? "複雑な計画用モデルを選択" : "通常モデルを選択", 14, CONTENT_TOP, 0xffc1cde0);
    g.text(font, count + "件 / クリックして選択（接続設定の保存は別途行います）", 14, height - 46, 0xffffdc9b);
    super.extractRenderState(g, mx, my, dt);
  }
}

package dev.avatarcraft.client;

import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Shared selectable catalog. Selecting a row never equips a model. */
public final class ModelList extends ObjectSelectionList<ModelList.Row> implements AutoCloseable {
  private final Map<String, AvatarTextures> previews = new HashMap<>();
  private final Consumer<AvatarLibrary.Entry> selected;
  public ModelList(int x, int y, int width, int height, Consumer<AvatarLibrary.Entry> selected) {
    super(Minecraft.getInstance(), width, height, y, 60);
    setX(x); this.selected = selected;
  }
  @Override public int getRowWidth() { return getWidth() - 18; }
  @Override protected int scrollBarX() { return getRight() - 6; }
  public void entries(List<AvatarLibrary.Entry> entries, String selectedId) {
    clearEntries();
    for (var entry : entries) {
      var row = new Row(entry); addEntry(row);
      if (entry.archive() != null && entry.archive().avatar().id.equals(selectedId)) super.setSelected(row);
    }
  }
  @Override public void setSelected(Row row) {
    super.setSelected(row);
    if (row != null && row.entry.archive() != null) selected.accept(row.entry);
  }
  @Override public void close() { previews.values().forEach(AvatarTextures::close); previews.clear(); }
  public final class Row extends ObjectSelectionList.Entry<Row> {
    private final AvatarLibrary.Entry entry;
    Row(AvatarLibrary.Entry entry) { this.entry = entry; }
    @Override public Component getNarration() { return Component.literal(entry.name()); }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
      if (event.button() != 1 || entry.archive() == null) return false;
      setSelected(this); return true;
    }
    @Override public void extractContent(GuiGraphicsExtractor g, int mx, int my, boolean hovered, float a) {
      int x = getContentX(), y = getContentY();
      if (entry.archive() != null && entry.archive().images().containsKey("preview.png")) {
        var textures = previews.computeIfAbsent(entry.archive().avatar().id, k -> new AvatarTextures(entry.archive()));
        g.blit(textures.get("preview.png"), x + 2, y + 1, x + 55, y + 55, 0, 1, 0, 1);
      }
      var font = Minecraft.getInstance().font;
      g.text(font, font.plainSubstrByWidth(entry.name(), Math.max(20, getWidth() - 72)), x + 62, y + 8, 0xffeeeeee);
      String status = entry.archive() == null ? entry.error() : entry.archive().avatar().id;
      if (AvatarSession.active != null && entry.archive() != null
          && AvatarSession.active.archive.avatar().id.equals(entry.archive().avatar().id)) status += " / 着用中";
      g.text(font, font.plainSubstrByWidth(String.valueOf(status), Math.max(20, getWidth() - 72)),
          x + 62, y + 28, entry.archive() == null ? 0xffffa8a8 : 0xffa9bacb);
    }
  }
}

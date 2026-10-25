package dev.avatarcraft.npc.client;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Wrapped variable-height rows with the vanilla draggable scrollbar and wheel scrolling. */
final class JournalList extends ContainerObjectSelectionList<JournalList.Row> {
  JournalList(int x, int y, int width, int height) {
    super(Minecraft.getInstance(), width, height, y, 26); setX(x);
  }
  @Override public int getRowWidth() { return Math.max(20, getWidth() - 18); }
  @Override protected int scrollBarX() { return getRight() - 6; }
  @Override protected void extractListBackground(GuiGraphicsExtractor g) {
    g.fill(getX(), getY(), getRight(), getBottom(), 0xffe1e1e1);
  }
  @Override protected void extractListSeparators(GuiGraphicsExtractor g) {
    g.fill(getX(), getY() - 1, getRight(), getY(), 0xff818181);
    g.fill(getX(), getBottom(), getRight(), getBottom() + 1, 0xff818181);
  }
  void line(String title, String body) {
    var font = Minecraft.getInstance().font;
    var heading = font.split(Component.literal(clean(title)), Math.max(20, getRowWidth() - 10));
    var detail = body.isBlank() ? List.<FormattedCharSequence>of()
        : font.split(Component.literal(clean(body)), Math.max(20, getRowWidth() - 10));
    addEntry(new Row(heading, detail), Math.max(25, (heading.size() + detail.size()) * 11 + 12));
  }
  private static String clean(String text) { return text.replaceAll("[\\p{Cntrl}&&[^\\n]]|§", " "); }
  final class Row extends ContainerObjectSelectionList.Entry<Row> {
    private final List<FormattedCharSequence> title, body;
    Row(List<FormattedCharSequence> title, List<FormattedCharSequence> body) { this.title = title; this.body = body; }
    @Override public List<? extends GuiEventListener> children() { return List.of(); }
    @Override public List<? extends NarratableEntry> narratables() { return List.of(); }
    @Override public void extractContent(GuiGraphicsExtractor g, int mx, int my, boolean hovered, float dt) {
      int y = getContentY() + 4;
      for (var line : title) { g.text(Minecraft.getInstance().font, line, getContentX() + 4, y, 0xff182432, false); y += 11; }
      for (var line : body) { g.text(Minecraft.getInstance().font, line, getContentX() + 4, y, 0xff485362, false); y += 11; }
      g.fill(getContentX() + 4, y + 4, getContentX() + getContentWidth() - 4, y + 5, 0x505d6874);
    }
  }
}

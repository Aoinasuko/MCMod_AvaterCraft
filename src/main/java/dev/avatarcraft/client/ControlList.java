package dev.avatarcraft.client;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;

/** Vanilla scrolling, draggable scrollbar and keyboard traversal for editable controls. */
public final class ControlList extends ContainerObjectSelectionList<ControlList.Row> {
  public ControlList(int x, int y, int width, int height) {
    super(Minecraft.getInstance(), width, height, y, 26);
    setX(x);
  }
  @Override public int getRowWidth() { return getWidth() - 18; }
  @Override protected int scrollBarX() { return getRight() - 6; }
  public void control(AbstractWidget widget) { addEntry(new Row(widget, null), 26); }
  public void field(String label, AbstractWidget widget) { addEntry(new Row(widget, label), 26); }
  public void heading(String label) { addEntry(new Row(null, label), 18); }

  public final class Row extends ContainerObjectSelectionList.Entry<Row> {
    private final AbstractWidget widget;
    private final String label;
    Row(AbstractWidget widget, String label) { this.widget = widget; this.label = label; }
    private int labelWidth() { return widget != null && label != null ? Math.min(142, getContentWidth() / 3) : 0; }
    private void position() {
      if (widget != null) { widget.setPosition(getContentX() + labelWidth(), getContentY()); widget.setWidth(Math.max(8, getContentWidth() - labelWidth())); }
    }
    @Override public void setX(int value) { super.setX(value); position(); }
    @Override public void setY(int value) { super.setY(value); position(); }
    @Override public void setWidth(int value) { super.setWidth(value); position(); }
    @Override public List<? extends GuiEventListener> children() { return widget == null ? List.of() : List.of(widget); }
    @Override public List<? extends NarratableEntry> narratables() { return widget == null ? List.of() : List.of(widget); }
    @Override public void extractContent(GuiGraphicsExtractor g, int mx, int my, boolean hovered, float a) {
      if (widget != null) {
        if (label != null) {
          var font = Minecraft.getInstance().font;
          g.text(font, font.plainSubstrByWidth(label, Math.max(8, labelWidth() - 4)), getContentX(), getContentY() + 6, 0xffd2dfeb);
        }
        widget.extractRenderState(g, mx, my, a);
      }
      else g.text(Minecraft.getInstance().font, label, getContentX(), getContentY() + 3, 0xffb5d8ee);
    }
  }
}

package dev.avatarcraft.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The shared series header, with tabs supplied by the installed mods. */
public abstract class SeriesScreen extends Screen {
  private final String tabId;
  protected static final int CONTENT_TOP = 64;

  protected SeriesScreen(String tabId) {
    super(Component.literal("B.E.P 3DAvater"));
    this.tabId = tabId;
  }

  protected void initTabs() {
    var tabs = SeriesTabs.tabs();
    int tabWidth = Math.min(164, (width - 16) / Math.max(1, tabs.size()));
    int x = (width - tabs.size() * tabWidth) / 2;
    for (var tab : tabs) {
      boolean selected = tab.id().equals(tabId);
      addRenderableWidget(new Button(x, 8, tabWidth, 26, tab.title(),
          b -> { if (!selected) minecraft.gui.setScreen(tab.screen().get()); }, narration -> narration.get()) {
        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float a) {
          int border = selected || isHoveredOrFocused() ? 0xffbcbcbc : 0xff686868;
          int y = getY() + (selected ? 0 : 3);
          g.fill(getX(), y, getRight(), getBottom(), selected ? 0x99303030 : 0x66404040);
          g.fill(getX(), y, getRight(), y + 1, border);
          g.fill(getX(), y, getX() + 1, getBottom(), border);
          g.fill(getRight() - 1, y, getRight(), getBottom(), border);
          var label = font.plainSubstrByWidth(getMessage().getString(), getWidth() - 12);
          g.centeredText(font, label, getX() + getWidth() / 2, getY() + 10, 0xffeeeeee);
          if (selected) {
            int w = Math.min(font.width(label), getWidth() - 16);
            g.fill(getX() + (getWidth() - w) / 2, getBottom() - 3,
                getX() + (getWidth() + w) / 2, getBottom() - 2, 0xffffffff);
          }
        }
      });
      x += tabWidth;
    }
  }

  protected Button button(String label, int x, int y, int w, Runnable action) {
    return addRenderableWidget(Button.builder(Component.literal(label), b -> action.run())
        .bounds(x, y, w, 20).build());
  }

  protected void background(GuiGraphicsExtractor g) {
    g.fill(0, 0, width, height, 0xb018202b);
    g.fill(0, 34, width, 35, 0xff777777);
    g.fill(0, height - 34, width, height - 33, 0xff777777);
    g.centeredText(font, title, width / 2, 43, 0xffeef4ff);
  }

  @Override public boolean isPauseScreen() { return false; }
}

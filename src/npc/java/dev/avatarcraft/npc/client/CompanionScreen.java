package dev.avatarcraft.npc.client;

import dev.avatarcraft.client.AvatarLibrary;
import dev.avatarcraft.client.AvatarPreview;
import dev.avatarcraft.npc.*;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** Inventory-style owner menu. All four sections share one authenticated container. */
public final class CompanionScreen extends AbstractContainerScreen<CompanionMenu> {
  private static final Identifier ICONS = NpcMod.id("textures/gui/icon.png"),
      INVENTORY = NpcMod.id("textures/gui/inv.png"),
      INFO = NpcMod.id("textures/gui/info.png");
  private static final int INK = 0xff353535;
  private int page, avatarIndex;
  private float uiScale = 1;
  private String personality, instruction, message = "", selectedAvatar = "";
  private MultiLineEditBox editor;
  private boolean profilesRequested;
  private java.util.List<Personality> profiles = java.util.List.of(Personality.preset("tortobu"));
  private final JournalView journal = new JournalView();

  public CompanionScreen(CompanionMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title, 209, 272);
    personality = menu.npc.personalityJson();
    instruction = menu.npc.instruction();
    selectedAvatar = menu.npc.avatarId();
  }

  private void button(String label, int x, int y, int w, Runnable action) {
    button(label, x, y, w, 20, action);
  }

  private void button(String label, int x, int y, int w, int h, Runnable action) {
    addRenderableWidget(
        Button.builder(Component.literal(label), b -> action.run())
            .bounds(leftPos + x, topPos + y, w, h)
            .build());
  }

  private static final String[] TAB_NAMES = {"インベントリ", "外見・性格", "作業指示", "記録"};
  private static final String[] TAB_HELP = {
    "NPCとプレイヤーの持ち物を受け渡し、主手・副手・防具を装備します。",
    "アバターを選び、性格や話し方を編集します。",
    "作業を依頼・停止し、待機中の追従を切り替えます。",
    "会話・指示・行動の履歴を読み返します。"
  };

  private final class IconTab extends Button {
    private final int index;

    IconTab(int index) {
      super(
          leftPos + 177,
          topPos + 4 + index * 34,
          31,
          32,
          Component.literal(TAB_NAMES[index] + "。" + TAB_HELP[index]),
          b -> switchPage(index),
          DEFAULT_NARRATION);
      this.index = index;
    }

    protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float a) {
      g.blit(RenderPipelines.GUI_TEXTURED, ICONS, getX(), getY(), 0, index * 32, 31, 32, 31, 128);
      if (page == index || isHoveredOrFocused()) {
        int color = page == index ? 0xff77cde4 : 0xffeeeeee;
        g.fill(getX(), getY(), getX() + 31, getY() + 1, color);
        g.fill(getX(), getY() + 31, getX() + 31, getY() + 32, color);
      }
    }
  }

  private void switchPage(int next) {
    if (!menu.getCarried().isEmpty()) {
      message = "先にカーソルのアイテムを戻してください";
      return;
    }
    page = next;
    message = "";
    init();
    if (page == 3) requestJournal("");
  }

  protected void init() {
    int actualW = minecraft.getWindow().getGuiScaledWidth(),
        actualH = minecraft.getWindow().getGuiScaledHeight();
    uiScale = Math.min(1f, Math.min((actualW - 4f) / imageWidth, (actualH - 4f) / imageHeight));
    width = (int) Math.ceil(actualW / uiScale);
    height = (int) Math.ceil(actualH / uiScale);
    super.init();
    clearWidgets();
    clearFocus();
    menu.slotsVisible = page == 0;
    for (int i = 0; i < 4; i++) addRenderableWidget(new IconTab(i));
    if (!profilesRequested) {
      profilesRequested = true;
      ClientPacketDistributor.sendToServer(
          new NpcNetwork.Request(menu.npc.getId(), "personalities", ""));
    }
    if (page == 1 || page == 2) {
      int y = page == 1 ? 88 : 40;
      editor =
          MultiLineEditBox.builder()
              .setX(leftPos + 8)
              .setY(topPos + y)
              .build(font, 159, 202 - y, Component.literal(page == 1 ? "性格JSON" : "作業指示"));
      editor.setCharacterLimit(page == 1 ? 16384 : 2048);
      editor.setValue(page == 1 ? personality : instruction);
      editor.setScrollAmount(0);
      editor.setValueListener(
          value -> {
            if (page == 1) personality = value;
            else instruction = value;
          });
      addRenderableWidget(editor);
      button(
          "保存・実行",
          8,
          220,
          76,
          () -> send(page == 1 ? "personality" : "instruction", editor.getValue()));
      if (page == 2) {
        button(
            "作業停止",
            91,
            220,
            76,
            () -> {
              instruction = "";
              editor.setValue("");
              send("instruction", "");
            });
        button(
            menu.npc.followsOwner() ? "追従: ON" : "追従: OFF",
            8,
            242,
            159,
            18,
            () -> send("follow_owner", Boolean.toString(!menu.npc.followsOwner())));
      } else {
        button("<", 8, 44, 20, () -> selectAvatar(-1));
        button(">", 31, 44, 20, () -> selectAvatar(1));
        button(
            "一覧再読込",
            56,
            44,
            111,
            () -> {
              AvatarLibrary.reload();
              message = "再読込しています";
            });
        String presetName;
        try {
          presetName = Personality.parse(personality).name;
        } catch (RuntimeException e) {
          presetName = "選択";
        }
        button(
            "性格: " + font.plainSubstrByWidth(presetName, 100), 8, 66, 159, this::selectPersonality);
      }
    }
    if (page == 3) {
      button("最新", 8, 220, 46, () -> requestJournal(""));
      var older =
          Button.builder(Component.literal("過去の記録"), b -> requestJournal(journal.cursor()))
              .bounds(leftPos + 58, topPos + 220, 109, 20)
              .build();
      older.active = !journal.cursor().isEmpty();
      addRenderableWidget(older);
      button("↑", 119, 244, 22, () -> journal.scroll(-5));
      button("↓", 145, 244, 22, () -> journal.scroll(5));
    }
  }

  private void selectAvatar(int direction) {
    var entries = AvatarLibrary.entries().stream().filter(e -> e.archive() != null).toList();
    if (entries.isEmpty()) {
      AvatarLibrary.reload();
      message = "読込中です。もう一度選択してください";
      return;
    }
    int current = entries.indexOf(AvatarLibrary.resolve(selectedAvatar));
    avatarIndex = Math.floorMod((current < 0 ? avatarIndex : current) + direction, entries.size());
    selectedAvatar = entries.get(avatarIndex).archive().avatar().id;
    try {
      var value = Personality.parse(editor.getValue());
      value.avatar = selectedAvatar;
      editor.setValue(value.json());
    } catch (RuntimeException e) {
      message = "JSONのエラーを修正してからモデルを反映してください";
    }
  }

  private void selectPersonality() {
    int current = -1;
    try {
      String id = Personality.parse(editor.getValue()).id;
      for (int i = 0; i < profiles.size(); i++) if (profiles.get(i).id.equals(id)) current = i;
    } catch (RuntimeException ignored) {
    }
    var profile =
        Personality.parse(profiles.get(Math.floorMod(current + 1, profiles.size())).json());
    profile.avatar = selectedAvatar;
    personality = profile.json();
    init();
    message = "性格を選択しました。保存・実行で反映します";
  }

  private void send(String action, String data) {
    ClientPacketDistributor.sendToServer(new NpcNetwork.Request(menu.npc.getId(), action, data));
    message = "送信中…";
  }

  private void requestJournal(String cursor) {
    var query = new com.google.gson.JsonObject();
    query.addProperty("cursor", cursor);
    send("journal", query.toString());
  }

  public void receive(NpcNetwork.Response response) {
    if (response.entity() != menu.npc.getId()) return;
    if (response.action().equals("personalities")) {
      var values = new java.util.ArrayList<Personality>();
      for (var row : com.google.gson.JsonParser.parseString(response.data()).getAsJsonArray())
        values.add(Personality.parse(row.toString()));
      if (!values.isEmpty()) profiles = java.util.List.copyOf(values);
    } else if (response.action().equals("journal")) {
      journal.load(response.data());
      message = "";
      if (page == 3) init();
    } else message = response.data();
  }

  @Override
  public void containerTick() {
    super.containerTick();
    if (page == 2)
      for (var child : children())
        if (child instanceof Button b && b.getMessage().getString().startsWith("追従:"))
          b.setMessage(Component.literal(menu.npc.followsOwner() ? "追従: ON" : "追従: OFF"));
  }

  @Override
  public boolean keyPressed(KeyEvent event) {
    if (page != 0 && !event.isEscape() && isInputCaptured()) {
      if (getFocused() != null) getFocused().keyPressed(event);
      return true;
    }
    return super.keyPressed(event);
  }

  private MouseButtonEvent unscale(MouseButtonEvent e) {
    return new MouseButtonEvent(e.x() / uiScale, e.y() / uiScale, e.buttonInfo());
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
    return super.mouseClicked(unscale(e), twice);
  }

  @Override
  public boolean mouseReleased(MouseButtonEvent e) {
    return super.mouseReleased(unscale(e));
  }

  @Override
  public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
    return super.mouseDragged(unscale(e), dx / uiScale, dy / uiScale);
  }

  @Override
  public void mouseMoved(double x, double y) {
    super.mouseMoved(x / uiScale, y / uiScale);
  }

  @Override
  public boolean mouseScrolled(double x, double y, double sx, double sy) {
    double vx = x / uiScale, vy = y / uiScale;
    if (page == 3
        && vx >= leftPos + 8
        && vx < leftPos + 167
        && vy >= topPos + 30
        && vy < topPos + 212) {
      journal.scroll(sy > 0 ? -3 : 3);
      return true;
    }
    return super.mouseScrolled(vx, vy, sx, sy);
  }

  @Override
  protected void extractTooltip(GuiGraphicsExtractor g, int x, int y) {
    super.extractTooltip(g, Math.round(x * uiScale), Math.round(y * uiScale));
  }

  public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float partialTick) {
    int vx = (int) (x / uiScale), vy = (int) (y / uiScale);
    g.pose().pushMatrix();
    g.pose().scale(uiScale, uiScale);
    g.blit(
        RenderPipelines.GUI_TEXTURED,
        page == 0 ? INVENTORY : INFO,
        leftPos,
        topPos,
        0,
        0,
        175,
        272,
        256,
        272);
    // Entity pictures use absolute GUI coordinates and do not inherit the 2D pose matrix.
    if (page == 0)
      AvatarPreview.extractEntityInInventoryFollowsMouse(
          g,
          Math.round((leftPos + 25) * uiScale),
          Math.round((topPos + 10) * uiScale),
          Math.round((leftPos + 75) * uiScale),
          Math.round((topPos + 81) * uiScale),
          Math.round(37 * uiScale),
          .03f,
          x,
          y,
          menu.npc);
    super.extractRenderState(g, vx, vy, partialTick);
    g.pose().popMatrix();
    for (int i = 0; i < 4; i++) {
      if (vx >= leftPos + 177
          && vx < leftPos + 208
          && vy >= topPos + 4 + i * 34
          && vy < topPos + 36 + i * 34) {
        g.setTooltipForNextFrame(
            font,
            font.split(
                Component.literal(TAB_NAMES[i])
                    .withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal("\n" + TAB_HELP[i]).withStyle(ChatFormatting.GRAY)),
                170),
            x,
            y);
      }
    }
    if (page == 0
        && vx >= leftPos + 7
        && vx < leftPos + 169
        && vy >= topPos + 174
        && vy < topPos + 188)
      g.setTooltipForNextFrame(
          font,
          font.split(
              Component.literal(
                      menu.npc.instruction().isBlank()
                          ? menu.npc.aiStatus()
                          : menu.npc.instruction())
                  .withStyle(ChatFormatting.GRAY),
              220),
          x,
          y);
  }

  private void sprite(GuiGraphicsExtractor g, String id, int x, int y) {
    g.blitSprite(RenderPipelines.GUI_TEXTURED, Identifier.withDefaultNamespace(id), x, y, 9, 9);
  }

  private void gauge(GuiGraphicsExtractor g, int value, int y, boolean food) {
    for (int i = 0; i < 10; i++) {
      int x = 80 + i * 8;
      sprite(g, food ? "hud/food_empty" : "hud/heart/container", x, y);
      if (value > i * 2)
        sprite(
            g,
            food
                ? (value > i * 2 + 1 ? "hud/food_full" : "hud/food_half")
                : (value > i * 2 + 1 ? "hud/heart/full" : "hud/heart/half"),
            x,
            y);
    }
  }

  protected void extractLabels(GuiGraphicsExtractor g, int x, int y) {
    if (page == 0) {
      var npc = menu.npc;
      g.text(font, font.plainSubstrByWidth(npc.getName().getString(), 86), 80, 9, INK, false);
      g.text(font, "体力", 80, 21, INK, false);
      gauge(g, (int) Math.ceil(npc.getHealth()), 30, false);
      g.text(font, "空腹", 80, 41, INK, false);
      gauge(g, npc.foodLevel(), 50, true);
      String task =
          npc.instruction().isBlank()
              ? (npc.followsOwner() ? "待機・所有者に追従" : "待機")
              : npc.instruction();
      String line = message.isEmpty() ? "作業: " + task.replace('\n', ' ') : message;
      g.text(font, font.plainSubstrByWidth(line, 159), 8, 178, INK, false);
    } else {
      String[] titles = {"", "外見・性格", "作業指示", "記録"};
      g.text(font, titles[page], 10, 12, INK, false);
      if (page == 1) {
        var entry = AvatarLibrary.resolve(selectedAvatar);
        String label = entry == null ? selectedAvatar : entry.name();
        g.text(font, font.plainSubstrByWidth("アバター: " + label, 159), 8, 30, INK, false);
      } else if (page == 2) g.text(font, "具体的な作業を指示してください", 8, 27, INK, false);
      else journal.draw(g, font, 8, 30, 159, 180);
      g.text(font, font.plainSubstrByWidth(message, page == 3 ? 105 : 159), 8, 261, INK, false);
    }
  }
}

package dev.avatarcraft.npc.client;

import com.google.gson.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import net.minecraft.client.gui.*;
import net.minecraft.network.chat.Component;

/** Wrapped entries stay inside a fixed viewport. */
final class JournalView {
  private List<String> entries = List.of();
  private String cursor = "";
  private int scroll;

  String cursor() {
    return cursor;
  }

  void scroll(int amount) {
    scroll = Math.max(0, scroll + amount);
  }

  void load(String json) {
    var value = JsonParser.parseString(json).getAsJsonObject();
    var lines = new ArrayList<String>();
    for (var element : value.getAsJsonArray("entries")) {
      var row = element.getAsJsonObject();
      String time =
          DateTimeFormatter.ofPattern("MM/dd HH:mm:ss")
              .withZone(ZoneId.systemDefault())
              .format(Instant.ofEpochMilli(row.get("timestamp").getAsLong()));
      lines.add(
          time
              + " "
              + row.get("actorName").getAsString()
              + " ["
              + row.get("kind").getAsString()
              + "] "
              + row.get("text").getAsString());
      if (row.has("details") && row.getAsJsonObject("details").size() > 0)
        lines.add(row.get("details").toString());
    }
    entries = List.copyOf(lines);
    cursor = value.get("nextCursor").getAsString();
    scroll = 0;
  }

  void draw(GuiGraphicsExtractor g, Font font, int x, int y, int width, int height) {
    var lines =
        entries.stream()
            .flatMap(e -> font.split(Component.literal(e), width - 8).stream())
            .toList();
    int visible = Math.max(1, (height - 4) / 11);
    scroll = Math.clamp(scroll, 0, Math.max(0, lines.size() - visible));
    g.fill(x, y, x + width, y + height, 0xffe5e9ef);
    if (lines.isEmpty()) g.text(font, "記録はまだありません", x + 3, y + 3, 0xff263e58, false);
    for (int i = 0; i < visible && scroll + i < lines.size(); i++)
      g.text(font, lines.get(scroll + i), x + 3, y + i * 11 + 2, 0xff263e58, false);
  }
}

package dev.avatarcraft.client;

import java.util.*;
import java.util.function.Supplier;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Client extension point. Optional mods register their tab without Loader linking to them. */
public final class SeriesTabs {
  public record Tab(String id, Component title, Supplier<? extends Screen> screen) {}
  private static final Map<String, Tab> TABS = new LinkedHashMap<>();

  public static synchronized void register(String id, Component title, Supplier<? extends Screen> screen) {
    if (TABS.putIfAbsent(id, new Tab(id, Objects.requireNonNull(title), Objects.requireNonNull(screen))) != null)
      throw new IllegalArgumentException("Duplicate settings tab: " + id);
  }

  public static synchronized List<Tab> tabs() {
    return TABS.values().stream().sorted(Comparator.comparingInt((Tab t) ->
        t.id().equals("bep_3davater_loader") ? 0 : t.id().equals("bep_3davater_player") ? 1 : 2)
        .thenComparing(Tab::id)).toList();
  }
}

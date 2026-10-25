package dev.avatarcraft.api;

import java.util.*;

/** Per-instance named expression selection, ordered from lowest to highest priority. */
public final class ExpressionKeys {
  private List<String> keys = List.of();

  public static boolean valid(String key) {
    return key != null && key.matches("[a-z0-9_.-]{1,64}");
  }

  public void set(String... values) {
    set(Arrays.asList(values));
  }

  public void set(Collection<String> values) {
    Objects.requireNonNull(values);
    if (values.stream().anyMatch(k -> !valid(k)))
      throw new IllegalArgumentException("Invalid expression key");
    keys = List.copyOf(new LinkedHashSet<>(values));
  }

  public List<String> keys() {
    return keys;
  }

  public void clear() {
    keys = List.of();
  }
}

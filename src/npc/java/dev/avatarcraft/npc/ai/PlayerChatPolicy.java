package dev.avatarcraft.npc.ai;

import java.util.*;

/** Pure addressing and authorization policy, independent of the language model. */
public final class PlayerChatPolicy {
  public record Candidate(
      int id, String name, boolean owned, double distanceSquared, boolean visible) {}

  public static OptionalInt recipient(String message, Collection<Candidate> candidates) {
    var targets = recipients(message, candidates);
    return targets.isEmpty() ? OptionalInt.empty() : OptionalInt.of(targets.getFirst());
  }

  public static List<Integer> recipients(String message, Collection<Candidate> candidates) {
    String text = message.toLowerCase(Locale.ROOT);
    var nearby = candidates.stream().filter(c -> c.visible && c.distanceSquared <= 144).toList();
    var named =
        nearby.stream()
            .filter(c -> c.name.length() >= 2 && text.contains(c.name.toLowerCase(Locale.ROOT)))
            .toList();
    boolean group =
        List.of("みんな", "皆さん", "全員", "二人とも", "2人とも", "三人とも", "everyone").stream()
            .anyMatch(text::contains);
    var pool =
        !named.isEmpty()
            ? named
            : group && nearby.stream().anyMatch(Candidate::owned)
                ? nearby.stream().filter(Candidate::owned).toList()
                : nearby;
    return pool.stream()
        .sorted(
            Comparator.comparingInt((Candidate c) -> c.owned ? 0 : 1)
                .thenComparingDouble(Candidate::distanceSquared)
                .thenComparingInt(Candidate::id))
        .limit(!named.isEmpty() || group ? 4 : 1)
        .map(Candidate::id)
        .toList();
  }

  public static boolean mayStartTask(
      boolean ownerWhenReceived, boolean stillOwner, boolean superseded, String instruction) {
    return ownerWhenReceived
        && stillOwner
        && !superseded
        && instruction != null
        && !instruction.isBlank()
        && instruction.length() <= 2048;
  }

  private PlayerChatPolicy() {}
}

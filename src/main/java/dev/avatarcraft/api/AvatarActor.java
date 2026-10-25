package dev.avatarcraft.api;

/**
 * Implement on an entity to expose a catalog reference without linking server code to rendering.
 */
public interface AvatarActor {
  String avatarId();

  /** Named expression keys, in increasing priority order. */
  default java.util.List<String> avatarExpressionKeys() {
    return java.util.List.of();
  }

  default boolean avatarMining() {
    return false;
  }
}

package dev.avatarcraft.api;

/**
 * Implement on an entity to expose a catalog reference without linking server code to rendering.
 */
public interface AvatarActor {
  String avatarId();

  default boolean avatarMining() {
    return false;
  }
}

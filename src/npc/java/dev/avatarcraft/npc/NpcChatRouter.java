package dev.avatarcraft.npc;

import dev.avatarcraft.npc.ai.PlayerChatPolicy;
import net.minecraft.server.level.ServerPlayer;

/** Route nearby human chat to one recipient, or at most four explicitly addressed companions. */
public final class NpcChatRouter {
  public static int receive(ServerPlayer player, String raw) {
    String text = raw == null ? "" : raw.replaceAll("[\\p{Cntrl}]", " ").strip();
    if (player.hasDisconnected() || text.isEmpty() || text.length() > 512 || player.isSpectator())
      return -1;
    var candidates =
        player
            .level()
            .getEntitiesOfClass(
                CompanionEntity.class, player.getBoundingBox().inflate(12), n -> n.isAlive());
    var selected =
        PlayerChatPolicy.recipients(
            text,
            candidates.stream()
                .map(
                    n ->
                        new PlayerChatPolicy.Candidate(
                            n.getId(),
                            n.getName().getString(),
                            n.ownedBy(player),
                            n.distanceToSqr(player),
                            n.hasLineOfSight(player)))
                .toList());
    if (selected.isEmpty()) return -1;
    for (var npc : candidates)
      if (selected.contains(npc.getId())) npc.mind().hearPlayer(player, text);
    return selected.getFirst();
  }

  private NpcChatRouter() {}
}

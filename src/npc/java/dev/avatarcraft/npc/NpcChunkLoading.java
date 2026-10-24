package dev.avatarcraft.npc;

import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.world.chunk.*;

/** Persistent entity-owned ticking tickets. Each NPC owns its own overlapping 3x3 square. */
public final class NpcChunkLoading {
  public static final TicketController CONTROLLER = new TicketController(NpcMod.id("companions"));
  private ServerLevel level;
  private Set<Long> chunks = Set.of();

  public static Set<Long> square(int x, int z) {
    var result = new LinkedHashSet<Long>();
    for (int dx = -1; dx <= 1; dx++)
      for (int dz = -1; dz <= 1; dz++) result.add(ChunkPos.pack(x + dx, z + dz));
    return Set.copyOf(result);
  }

  public void update(CompanionEntity npc) {
    if (!(npc.level() instanceof ServerLevel current) || !npc.isAlive()) return;
    var position = npc.chunkPosition();
    var wanted = square(position.x(), position.z());
    if (current == level && wanted.equals(chunks)) return;
    // Acquire first so crossing a boundary cannot unload the destination beneath the NPC.
    for (long p : wanted)
      if (current != level || !chunks.contains(p))
        CONTROLLER.forceChunk(
            current, npc.getUUID(), ChunkPos.getX(p), ChunkPos.getZ(p), true, true);
    release(npc.getUUID(), current == level ? wanted : Set.of());
    level = current;
    chunks = wanted;
  }

  private void release(UUID owner, Set<Long> retained) {
    if (level != null)
      for (long p : chunks)
        if (!retained.contains(p))
          CONTROLLER.forceChunk(level, owner, ChunkPos.getX(p), ChunkPos.getZ(p), false, true);
  }

  public void remove(CompanionEntity npc) {
    // A freshly loaded NPC may be killed before its first update.
    if (level == null && npc.level() instanceof ServerLevel current) {
      level = current;
      chunks = square(npc.chunkPosition().x(), npc.chunkPosition().z());
    }
    release(npc.getUUID(), Set.of());
    level = null;
    chunks = Set.of();
  }
}

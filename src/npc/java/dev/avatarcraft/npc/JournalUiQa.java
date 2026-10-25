package dev.avatarcraft.npc;

import dev.avatarcraft.npc.ai.NpcService;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;

/** Development-only isolated UI fixtures; opt-in runJournalClient, never a live AI connection. */
final class JournalUiQa {
  private static final Set<MinecraftServer> SEEDED = Collections.newSetFromMap(new WeakHashMap<>());
  static void tick(MinecraftServer server) {
    if (!Boolean.getBoolean("avatarcraft.journalUiQa") || net.neoforged.fml.loading.FMLEnvironment.isProduction()
        || SEEDED.contains(server) || server.getPlayerList().getPlayers().isEmpty()) return;
    SEEDED.add(server);
    var player = server.getPlayerList().getPlayers().getFirst();
    var npc = bep_3davater_companion.COMPANION.get().create(player.level(), EntitySpawnReason.COMMAND);
    npc.initialize(player, "tortobu"); npc.setFollowsOwner(false); npc.setPos(player.position().add(2, 0, 0));
    player.level().addFreshEntity(npc);
    var service = NpcService.get(server);
    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(bep_3davater_companion.MEMORY_JOURNAL.get()));
    service.readJournal(() -> {
      long now = service.journal.now();
      for (int i = 0; i < 300; i++) {
        String kind = switch (i % 10) {
          case 0 -> "planning_start"; case 1 -> "tool_start"; case 2 -> "tool_result";
          case 3 -> "local_retry"; case 4 -> "ai_handoff"; case 5 -> "speech";
          case 6 -> "player_chat"; case 7 -> "death"; default -> "local_policy";
        };
        var detail = new com.google.gson.JsonObject(); detail.addProperty("ok", i % 20 != 2);
        detail.addProperty("fixture", true);
        service.journal.appendAt(player.getUUID(), npc.getUUID(), "tortobu", null, kind,
            kind.equals("speech") ? "【画面確認用】ぼく、チェストに原木を入れておいたよ。" : "【画面確認用】行動 " + i,
            detail, now - (300 - i) * 11_000L);
      }
      return null;
    }).whenComplete((value, error) -> server.execute(() -> { if (error == null) NpcNetwork.openJournal(player); }));
  }
  private JournalUiQa() {}
}

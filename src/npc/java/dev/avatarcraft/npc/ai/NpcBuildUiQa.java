package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import com.google.gson.*;
import dev.avatarcraft.npc.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.*;

/** Explicit dev-run fixture: the real tools create a saved preview and optionally build it. */
public final class NpcBuildUiQa {
  private static final Map<UUID, Job> JOBS = new HashMap<>();

  private static final class Job {
    CompanionEntity npc;
    NpcSkills skills;
    CompletableFuture<JsonObject> pending;
    int stage;
    int reported = -1;
  }

  public static void tick(MinecraftServer server) {
    if (!Boolean.getBoolean("avatarcraft.blueprintUiQa")
        || net.neoforged.fml.loading.FMLEnvironment.isProduction()) return;
    for (var player : server.getPlayerList().getPlayers()) {
      if (!player.entityTags().contains("avatarcraft_blueprint_preview_qa")) continue;
      var job = JOBS.get(player.getUUID());
      if (job == null) {
        job = new Job();
        var n = bep_3davater_companion.COMPANION.get().create(player.level(), EntitySpawnReason.COMMAND);
        n.initialize(player, "tortobu");
        n.setFollowsOwner(false);
        n.setPos(player.position().add(3, 0, -2));
        n.inventory.setItem(0, new ItemStack(Items.OAK_PLANKS, 64));
        n.inventory.setItem(1, new ItemStack(Items.OAK_PLANKS, 16));
        n.inventory.setItem(2, new ItemStack(Items.DIRT));
        player.level().addFreshEntity(n);
        job.npc = n;
        job.skills = new NpcSkills(n, (t, id) -> {});
        var p = player.blockPosition().offset(-2, 0, -8);
        var selection =
            object(
                "from",
                new Gson().toJsonTree(List.of(p.getX(), p.getY(), p.getZ())),
                "to",
                new Gson().toJsonTree(List.of(p.getX() + 4, p.getY() + 3, p.getZ() + 4)));
        job.pending =
            job.skills
                .execute(
                    "create_blueprint",
                    object(
                        "design_json",
                        house(),
                        "site_json",
                        selection.toString()))
                .toCompletableFuture();
        JOBS.put(player.getUUID(), job);
      }
      job.skills.tick();
      if (!job.pending.isDone()) continue;
      var result = job.pending.join();
      if (job.reported != job.stage)
        try {
          job.reported = job.stage;
          java.nio.file.Files.writeString(
              java.nio.file.Path.of("../build/blueprint-ui-qa.json"),
              new GsonBuilder()
                  .setPrettyPrinting()
                  .create()
                  .toJson(
                      object(
                          "stage",
                          job.stage,
                          "result",
                          result,
                          "position",
                          job.npc.position().toString())));
        } catch (Exception error) {
          throw new RuntimeException(error);
        }
      if (!result.get("ok").getAsBoolean()) continue;
      if (job.stage == 0 && player.entityTags().contains("avatarcraft_blueprint_build_qa")) {
        job.stage = 1;
        job.pending =
            job.skills
                .execute("gather_blueprint", object("blueprint_id", "current"))
                .toCompletableFuture();
      } else if (job.stage == 1) {
        job.stage = 2;
        job.pending =
            job.skills
                .execute("build_blueprint", object("blueprint_id", "current"))
                .toCompletableFuture();
      }
    }
  }

  public static void clear() {
    JOBS.clear();
  }

  /** The small house both the UI check and the construction GameTests build. */
  static String house() {
    return "{\"name\":\"小さな家\",\"size\":[5,4,5],\"parts\":[{\"from\":[0,0,0],\"to\":[4,3,4],\"block\":\"minecraft:oak_planks\"},{\"from\":[1,1,1],\"to\":[3,2,3],\"block\":\"minecraft:air\"},{\"from\":[2,1,0],\"to\":[2,2,0],\"block\":\"minecraft:air\"}]}";
  }
}

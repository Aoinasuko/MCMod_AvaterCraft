package dev.avatarcraft.npc.ai;

import com.google.gson.*;
import com.mojang.serialization.Codec;
import dev.avatarcraft.npc.bep_3davater_companion;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.*;

/** World-owned leases and delegated jobs. Contains no provider settings or credentials. */
public final class NpcWorkData extends SavedData {
  public static final class Job {
    public UUID id, owner, leader, worker;
    public String parentInstruction, item, status = "running", problem = "";
    public int count;
    public int initialEntityId;
  }

  public static final class Group {
    public UUID owner, leader;
    public String instruction;
    public final Set<UUID> members = new LinkedHashSet<>();
  }

  private static final Gson GSON = new Gson();
  public final Map<String, UUID> furnaces = new LinkedHashMap<>();
  public final List<Job> jobs = new ArrayList<>();
  public final Map<String, JsonObject> findings = new LinkedHashMap<>();
  public final List<Group> groups = new ArrayList<>();

  private static NpcWorkData parse(String json) {
    if (json.length() > 1048576) throw new IllegalArgumentException("Work state too large");
    var data = GSON.fromJson(json, NpcWorkData.class);
    if (data == null
        || data.jobs == null
        || data.furnaces == null
        || data.groups == null
        || data.findings == null
        || data.jobs.size() > 256
        || data.furnaces.size() > 512
        || data.groups.size() > 64
        || data.findings.size() > 64) throw new IllegalArgumentException("Invalid work state");
    for (var job : data.jobs)
      if (job == null
          || job.id == null
          || job.owner == null
          || job.leader == null
          || job.worker == null
          || job.parentInstruction == null
          || job.parentInstruction.length() > 2048
          || job.item == null
          || job.item.length() > 128
          || job.problem == null
          || job.problem.length() > 8192
          || job.status == null
          || !Set.of("running", "complete", "blocked", "cancelled").contains(job.status)
          || job.count < 1
          || job.count > 64) throw new IllegalArgumentException("Invalid saved team job");
    for (var group : data.groups)
      if (group == null
          || group.owner == null
          || group.leader == null
          || group.members == null
          || group.members.size() > 64
          || group.members.contains(null)
          || group.instruction == null
          || group.instruction.length() > 2048)
        throw new IllegalArgumentException("Invalid saved team group");
    if (data.furnaces.entrySet().stream()
            .anyMatch(e -> e.getKey().length() > 256 || e.getValue() == null)
        || data.findings.entrySet().stream()
            .anyMatch(
                e ->
                    e.getKey().length() > 256
                        || e.getValue() == null
                        || e.getValue().toString().length() > 8192))
      throw new IllegalArgumentException("Invalid saved work location");
    return data;
  }

  static boolean roomForJob(NpcWorkData data) {
    if (data.jobs.size() < 256) return true;
    var iterator = data.jobs.iterator();
    while (iterator.hasNext() && data.jobs.size() >= 192)
      if (!iterator.next().status.equals("running")) {
        iterator.remove();
        data.setDirty();
      }
    return data.jobs.size() < 256;
  }

  public static final SavedDataType<NpcWorkData> TYPE =
      new SavedDataType<>(
          bep_3davater_companion.id("work"),
          NpcWorkData::new,
          Codec.STRING.xmap(NpcWorkData::parse, data -> GSON.toJson(data)));

  public static NpcWorkData get(ServerLevel level) {
    return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
  }
}

package dev.avatarcraft.npc.ai;

import static dev.avatarcraft.npc.ai.CodexBridge.object;

import dev.avatarcraft.npc.*;
import java.util.*;
import net.minecraft.server.level.ServerLevel;

/** Server-side, same-owner work leases; completion comes from actual child plan execution. */
public final class NpcTeams {
  private NpcTeams() {}

  public static NpcWorkData.Job job(CompanionEntity worker) {
    return NpcWorkData.get((ServerLevel) worker.level()).jobs.stream()
        .filter(j -> j.worker.equals(worker.getUUID()) && j.status.equals("running"))
        .findFirst()
        .orElse(null);
  }

  public static boolean coordinates(CompanionEntity actor) {
    if (!NpcPlanningTier.cooperative(actor.instruction()) || job(actor) != null) return true;
    var level = (ServerLevel) actor.level();
    var data = NpcWorkData.get(level);
    for (var group : data.groups)
      if (group.owner.equals(actor.ownerId())
          && group.instruction.equals(actor.instruction())
          && group.members.contains(actor.getUUID())) return group.leader.equals(actor.getUUID());
    var members =
        level.getEntitiesOfClass(
            CompanionEntity.class,
            actor.getBoundingBox().inflate(48),
            n ->
                n.isAlive()
                    && Objects.equals(n.ownerId(), actor.ownerId())
                    && n.instruction().equals(actor.instruction())
                    && job(n) == null);
    if (members.size() < 2) return true;
    members.sort(Comparator.comparingInt(CompanionEntity::getId));
    if (data.groups.size() >= 64) throw new IllegalStateException("Team group limit reached");
    var group = new NpcWorkData.Group();
    group.owner = actor.ownerId();
    group.leader = members.getFirst().getUUID();
    group.instruction = actor.instruction();
    for (var n : members) group.members.add(n.getUUID());
    data.groups.add(group);
    data.setDirty();
    return group.leader.equals(actor.getUUID());
  }

  public static com.google.gson.JsonObject delegate(
      CompanionEntity leader, int entityId, String item, int count) {
    var level = (ServerLevel) leader.level();
    var data = NpcWorkData.get(level);
    if (count < 1 || count > 64 || leader.instruction().isBlank())
      return object("ok", false, "error", "Invalid delegated quantity or inactive leader");
    if (job(leader) != null)
      return object("ok", false, "error", "Delegated workers cannot recursively delegate");
    var previous =
        data.jobs.stream()
            .filter(
                j ->
                    j.leader.equals(leader.getUUID())
                        && j.parentInstruction.equals(leader.instruction())
                        && j.item.equals(item)
                        && j.count == count
                        && j.status.equals("running"))
            .findFirst()
            .orElse(null);
    if (previous != null)
      return object(
          "ok", true, "job", previous.id.toString(), "worker", previous.worker.toString());
    if (!NpcWorkData.roomForJob(data))
      return object("ok", false, "error", "Saved team job limit reached");
    var workers =
        level.getEntitiesOfClass(
            CompanionEntity.class,
            leader.getBoundingBox().inflate(48),
            n ->
                n != leader
                    && n.isAlive()
                    && Objects.equals(n.ownerId(), leader.ownerId())
                    && (entityId < 0 || n.getId() == entityId)
                    && job(n) == null
                    && (n.instruction().isBlank() || n.instruction().equals(leader.instruction())));
    workers.sort(Comparator.comparingDouble(leader::distanceToSqr));
    if (workers.isEmpty()) return object("ok", false, "error", "No available same-owner teammate");
    var worker = workers.getFirst();
    var steps =
        List.of(
            new TaskPlan.Step("acquire", item, "", count, -1, -1, "main", 10, List.of()),
            new TaskPlan.Step(
                "give_item", item, "", count, leader.getId(), -1, "main", 10, List.of()));
    var plan = new TaskPlan(1, "Prepare and deliver " + count + " " + item, "", steps);
    worker.setInstruction("分担作業: " + item + " ×" + count);
    worker.mind().acceptDelegatedPlan(plan);
    var record = new NpcWorkData.Job();
    record.id = UUID.randomUUID();
    record.owner = leader.ownerId();
    record.leader = leader.getUUID();
    record.worker = worker.getUUID();
    record.parentInstruction = leader.instruction();
    record.item = item;
    record.count = count;
    record.initialEntityId = worker.getId();
    data.jobs.removeIf(j -> !j.status.equals("running") && j.worker.equals(worker.getUUID()));
    data.jobs.add(record);
    data.setDirty();
    NpcService.get(level.getServer())
        .record(
            leader,
            "team_delegate",
            plan.summary(),
            worker.getUUID(),
            object("job", record.id.toString(), "item", item, "count", count));
    return object("ok", true, "job", record.id.toString(), "worker", worker.getUUID().toString());
  }

  public static com.google.gson.JsonObject delegatePlan(
      CompanionEntity leader, int entityId, String json) {
    var plan = TaskPlan.parse(json);
    if (!plan.blocked().isEmpty()
        || plan.steps().stream()
            .anyMatch(
                s ->
                    Set.of("delegate", "plan_next").contains(s.action())
                        || s.action().equals("tool")
                            && Set.of("bep_3davater_companion:delegate", "bep_3davater_companion:delegate_task")
                                .contains(s.target())))
      return object(
          "ok", false, "error", "A delegated plan must be finite and cannot recursively delegate");
    if (job(leader) != null || leader.instruction().isBlank())
      return object("ok", false, "error", "Only an active leader may delegate");
    var level = (ServerLevel) leader.level();
    var data = NpcWorkData.get(level);
    if (!NpcWorkData.roomForJob(data))
      return object("ok", false, "error", "Saved team job limit reached");
    var worker =
        level
            .getEntitiesOfClass(
                CompanionEntity.class,
                leader.getBoundingBox().inflate(48),
                n ->
                    n != leader
                        && n.isAlive()
                        && Objects.equals(n.ownerId(), leader.ownerId())
                        && job(n) == null
                        && (entityId < 0 || n.getId() == entityId)
                        && (n.instruction().isBlank()
                            || n.instruction().equals(leader.instruction())))
            .stream()
            .min(Comparator.comparingDouble(leader::distanceToSqr))
            .orElse(null);
    if (worker == null) return object("ok", false, "error", "No available same-owner worker");
    var j = new NpcWorkData.Job();
    j.id = UUID.randomUUID();
    j.owner = leader.ownerId();
    j.leader = leader.getUUID();
    j.worker = worker.getUUID();
    j.parentInstruction = leader.instruction();
    j.item = "";
    j.count = 1;
    j.initialEntityId = worker.getId();
    worker.setInstruction("分担作業: " + plan.summary());
    worker.mind().acceptDelegatedPlan(plan);
    data.jobs.add(j);
    data.setDirty();
    NpcService.get(level.getServer())
        .record(
            leader,
            "team_delegate",
            plan.summary(),
            worker.getUUID(),
            object("job", j.id.toString(), "steps", plan.steps().size()));
    return object("ok", true, "job", j.id.toString(), "worker", j.worker.toString());
  }

  public static com.google.gson.JsonObject status(CompanionEntity leader, int entityId) {
    var jobs = new com.google.gson.JsonArray();
    boolean running = false, failed = false;
    for (var j : NpcWorkData.get((ServerLevel) leader.level()).jobs)
      if (j.leader.equals(leader.getUUID())
          && j.parentInstruction.equals(leader.instruction())
          && (entityId < 0 || j.initialEntityId == entityId)) {
        jobs.add(
            object(
                "job",
                j.id.toString(),
                "worker",
                j.worker.toString(),
                "item",
                j.item,
                "count",
                j.count,
                "status",
                j.status,
                "problem",
                j.problem));
        running |= j.status.equals("running");
        failed |= !Set.of("running", "complete").contains(j.status);
      }
    return object("ok", jobs.size() > 0 && !failed, "running", running, "jobs", jobs);
  }

  public static void finished(CompanionEntity worker, String problem) {
    var j = job(worker);
    if (j == null) return;
    j.status = problem.isEmpty() ? "complete" : "blocked";
    j.problem = problem;
    NpcWorkData.get((ServerLevel) worker.level()).setDirty();
  }

  public static void changed(CompanionEntity actor) {
    var level = (ServerLevel) actor.level();
    var data = NpcWorkData.get(level);
    var toStop = new ArrayList<CompanionEntity>();
    var iterator = data.groups.iterator();
    while (iterator.hasNext()) {
      var group = iterator.next();
      if (group.leader.equals(actor.getUUID())) {
        iterator.remove();
        data.setDirty();
        for (var member : group.members)
          if (!member.equals(actor.getUUID()))
            for (var dimension : level.getServer().getAllLevels())
              if (dimension.getEntity(member) instanceof CompanionEntity n
                  && n.instruction().equals(group.instruction)) toStop.add(n);
      } else if (group.members.remove(actor.getUUID())) data.setDirty();
    }
    for (var j : data.jobs)
      if (j.status.equals("running")) {
        if (j.worker.equals(actor.getUUID())) {
          j.status = "cancelled";
          data.setDirty();
        }
        if (j.leader.equals(actor.getUUID())) {
          j.status = "cancelled";
          data.setDirty();
          for (var dimension : level.getServer().getAllLevels())
            if (dimension.getEntity(j.worker) instanceof CompanionEntity child) toStop.add(child);
        }
      }
    for (var child : toStop) child.setInstruction("");
  }

  public static boolean waiting(CompanionEntity leader, String item) {
    return NpcWorkData.get((ServerLevel) leader.level()).jobs.stream()
        .anyMatch(
            j ->
                j.leader.equals(leader.getUUID())
                    && j.item.equals(item)
                    && j.status.equals("running")
                    && j.parentInstruction.equals(leader.instruction()));
  }
}

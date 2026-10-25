package dev.avatarcraft.npc.ai;

import java.util.*;

/** A bounded provider-authored reservation; finishing a repair never finishes the owner mission. */
public final class NpcRecoveryQueue {
  public static final int MAX_ATTEMPTS = 2;

  public static TaskPlan schedule(TaskPlan plan) {
    if (!plan.blocked().isEmpty()) throw new IllegalArgumentException("Recovery is blocked");
    var steps = new ArrayList<>(plan.steps());
    if (!steps.isEmpty() && steps.getLast().action().equals("plan_next")) steps.removeLast();
    if (steps.size() < 3
        || steps.size() > 5
        || steps.stream()
            .anyMatch(
                s ->
                    Set.of("plan_next", "delegate").contains(s.action())
                        || s.action().equals("tool")
                            && Set.of("bep_3davater_companion:delegate", "bep_3davater_companion:delegate_task")
                                .contains(s.target())))
      throw new IllegalArgumentException("Recovery reservation requires 3 to 5 local task slots");
    steps.add(new TaskPlan.Step("plan_next", "", "", 1, -1, -1, "main", 1, List.of()));
    return new TaskPlan(1, plan.summary(), "", steps);
  }

  public static String prompt(String instruction, String priorProgress) {
    return "RECOVERY_RESERVATION: Repeated local execution failed. Write a reservation of exactly 3"
        + " to 5 executable task steps using the TaskPlan schema, not prose. Use real"
        + " Observation, failedTargets and search results; do not retry unchanged failed"
        + " targets. Include a concrete alternate approach, material preparation or recorded"
        + " return path as needed. Respect permissions, actual inventory and the original"
        + " owner's constraints. Do not delegate recovery recursively. Never relax safety"
        + " checks. The executor appends plan_next automatically: this repair queue alone"
        + " cannot declare the full mission done. If blocked, state the actual missing"
        + " prerequisite instead of inventing a route.\n"
        + "Original mission: "
        + instruction
        + "\nVerified progress and remaining goal: "
        + priorProgress;
  }

  private NpcRecoveryQueue() {}
}

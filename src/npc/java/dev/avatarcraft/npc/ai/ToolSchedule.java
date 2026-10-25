package dev.avatarcraft.npc.ai;

import java.util.*;

/**
 * Dependency-aware ready heap. Completing a prerequisite immediately releases higher priorities.
 */
final class ToolSchedule {
  private final BitSet done;
  private final int[] waiting;
  private final List<List<Integer>> children = new ArrayList<>();
  private final PriorityQueue<Integer> ready;
  private final Set<Integer> deferred = new LinkedHashSet<>();
  private int changed, pass = 1;

  ToolSchedule(List<ToolProgram.Node> nodes, BitSet done) {
    this.done = done;
    waiting = new int[nodes.size()];
    ready =
        new PriorityQueue<>(
            Comparator.<Integer>comparingInt(i -> nodes.get(i).priority())
                .reversed()
                .thenComparingInt(i -> i));
    var ids = new HashMap<String, Integer>();
    for (int i = 0; i < nodes.size(); i++) {
      ids.put(nodes.get(i).id(), i);
      children.add(new ArrayList<>());
    }
    for (int i = 0; i < nodes.size(); i++) {
      for (var id : nodes.get(i).after()) {
        var dependency = ids.get(id);
        if (dependency == null) throw new IllegalArgumentException("Unknown tool dependency");
        children.get(dependency).add(i);
        if (!done.get(dependency)) waiting[i]++;
      }
      if (!done.get(i) && waiting[i] == 0) ready.add(i);
    }
  }

  int next() {
    if (ready.isEmpty() && !deferred.isEmpty() && changed > 0 && pass < 32) {
      for (var i : deferred) if (!done.get(i) && waiting[i] == 0) ready.add(i);
      deferred.clear();
      changed = 0;
      pass++;
    }
    return ready.isEmpty() ? -1 : ready.remove();
  }

  void complete(int index) {
    if (done.get(index)) return;
    done.set(index);
    changed++;
    deferred.remove(index);
    for (var child : children.get(index))
      if (--waiting[child] == 0 && !done.get(child)) ready.add(child);
  }

  void defer(int index) {
    deferred.add(index);
  }

  void changed() {
    changed++;
  }

  int pass() {
    return pass;
  }
}

package dev.avatarcraft.npc.ai;

import java.util.*;

/** Incremental, read-only A*. No game classes, edits, or blocking worker-thread reads. */
public final class WalkingPathSearch {
  public static final int RANGE = 128, MAX_NODES = 65536;

  public record Cell(int x, int y, int z) {
    long key() {
      return ((long) x & 0x3ffffffL) << 38 | ((long) z & 0x3ffffffL) << 12 | ((long) y & 0xfffL);
    }
  }

  public interface Terrain {
    boolean stand(Cell p);

    default boolean transition(Cell from, Cell to) {
      return true;
    }

    default boolean headroom(Cell p) {
      return true;
    }

    /** Extra cost for entering a cell that is passable but better avoided. */
    default int penalty(Cell p) {
      return 0;
    }
  }

  public enum State {
    SEARCHING,
    FOUND,
    UNREACHABLE,
    LIMIT
  }

  private record Node(Cell p, Node parent, int cost, int estimate) {}

  private final Terrain terrain;
  private final Cell start;
  private final Set<Long> goals = new HashSet<>();
  private final PriorityQueue<Node> open =
      new PriorityQueue<>(Comparator.comparingInt(Node::estimate).thenComparingInt(n -> -n.cost));
  private final Map<Long, Integer> costs = new HashMap<>();
  private final int minX, maxX, minY, maxY, minZ, maxZ;
  private State state = State.SEARCHING;
  private List<Cell> path = List.of();
  private int expanded;

  public WalkingPathSearch(Cell start, Collection<Cell> ends, Terrain terrain) {
    this.start = start;
    this.terrain = terrain;
    int lx = Integer.MAX_VALUE, hx = Integer.MIN_VALUE, ly = lx, hy = hx, lz = lx, hz = hx;
    for (var p : ends) {
      if (!inside(p)) continue;
      goals.add(p.key());
      lx = Math.min(lx, p.x);
      hx = Math.max(hx, p.x);
      ly = Math.min(ly, p.y);
      hy = Math.max(hy, p.y);
      lz = Math.min(lz, p.z);
      hz = Math.max(hz, p.z);
    }
    minX = lx;
    maxX = hx;
    minY = ly;
    maxY = hy;
    minZ = lz;
    maxZ = hz;
    if (goals.isEmpty()) state = State.UNREACHABLE;
    else {
      open.add(new Node(start, null, 0, heuristic(start)));
      costs.put(start.key(), 0);
    }
  }

  private boolean inside(Cell p) {
    return Math.abs(p.x - start.x) <= RANGE
        && Math.abs(p.z - start.z) <= RANGE
        && Math.abs(p.y - start.y) <= RANGE;
  }

  private int heuristic(Cell p) {
    int dx = Math.max(0, Math.max(minX - p.x, p.x - maxX)),
        dz = Math.max(0, Math.max(minZ - p.z, p.z - maxZ));
    int dy = Math.max(0, Math.max(minY - p.y, p.y - maxY));
    return 10 * Math.max(dx, dz) + 4 * Math.min(dx, dz) + 4 * dy;
  }

  private Cell neighbour(Cell from, int dx, int dz) {
    for (int dy : new int[] {0, 1, -1}) {
      var p = new Cell(from.x + dx, from.y + dy, from.z + dz);
      if (!inside(p)
          || !terrain.stand(p)
          || !terrain.transition(from, p)
          || dy > 0 && !terrain.headroom(from)
          || dy < 0 && !terrain.headroom(p)) continue;
      if (dx != 0
          && dz != 0
          && (dy != 0
              || !terrain.stand(new Cell(from.x + dx, from.y, from.z))
              || !terrain.stand(new Cell(from.x, from.y, from.z + dz)))) continue;
      return p;
    }
    return null;
  }

  /** Returns after at most budget expansions or at the deadline; resumes the same frontier. */
  public State step(int budget, long deadlineNanos) {
    if (state != State.SEARCHING) return state;
    int count = 0;
    while (!open.isEmpty() && count < budget) {
      if ((count & 15) == 0 && System.nanoTime() >= deadlineNanos) return state;
      if (expanded >= MAX_NODES || costs.size() >= MAX_NODES * 2) {
        state = State.LIMIT;
        return state;
      }
      var n = open.remove();
      if (n.cost != costs.getOrDefault(n.p.key(), Integer.MAX_VALUE)) continue;
      count++;
      expanded++;
      if (goals.contains(n.p.key())) {
        var reverse = new ArrayList<Cell>();
        for (var at = n; at != null; at = at.parent) reverse.add(at.p);
        Collections.reverse(reverse);
        path = List.copyOf(reverse);
        state = State.FOUND;
        return state;
      }
      for (int dx = -1; dx <= 1; dx++)
        for (int dz = -1; dz <= 1; dz++) {
          if (dx == 0 && dz == 0) continue;
          var p = neighbour(n.p, dx, dz);
          if (p == null) continue;
          int g =
              n.cost
                  + (dx == 0 || dz == 0 ? 10 : 14)
                  + 4 * Math.abs(p.y - n.p.y)
                  + terrain.penalty(p)
                  // A diagonal brushes both side cells, so it pays for them as well.
                  + (dx != 0 && dz != 0
                      ? Math.max(
                          terrain.penalty(new Cell(n.p.x + dx, n.p.y, n.p.z)),
                          terrain.penalty(new Cell(n.p.x, n.p.y, n.p.z + dz)))
                      : 0);
          if (g >= costs.getOrDefault(p.key(), Integer.MAX_VALUE)) continue;
          costs.put(p.key(), g);
          open.add(new Node(p, n, g, g + heuristic(p)));
        }
    }
    if (open.isEmpty()) state = State.UNREACHABLE;
    return state;
  }

  public State state() {
    return state;
  }

  public List<Cell> path() {
    return path;
  }

  public int expanded() {
    return expanded;
  }
}

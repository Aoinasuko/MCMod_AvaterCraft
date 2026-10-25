import dev.avatarcraft.npc.ai.WalkingPathSearch;
import java.util.*;

/** Pure graph planner only: excludes Minecraft terrain reads, movement and Tick scheduling. */
public class NpcNavigationBenchmark {
  private static volatile int sink;

  private static WalkingPathSearch run(boolean detour) {
    var search =
        new WalkingPathSearch(
            new WalkingPathSearch.Cell(0, 0, 0),
            List.of(new WalkingPathSearch.Cell(detour ? 32 : 128, 0, 0)),
            p -> p.y() == 0 && (!detour || p.x() != 12 || Math.abs(p.z()) > 32));
    while (search.state() == WalkingPathSearch.State.SEARCHING) search.step(512, Long.MAX_VALUE);
    if (search.state() != WalkingPathSearch.State.FOUND)
      throw new IllegalStateException("Missing benchmark path");
    sink = search.path().size();
    return search;
  }

  public static void main(String[] args) {
    for (boolean detour : new boolean[] {false, true}) {
      for (int i = 0; i < 100; i++) run(detour);
      long[] times = new long[300];
      int expanded = 0;
      for (int i = 0; i < times.length; i++) {
        long begin = System.nanoTime();
        var search = run(detour);
        times[i] = System.nanoTime() - begin;
        expanded = search.expanded();
      }
      Arrays.sort(times);
      System.out.printf(
          Locale.ROOT,
          "{\"scenario\":\"%s\",\"samples\":300,\"nodes\":%d,\"pathNodes\":%d,\"p50Microseconds\":%.3f,\"p95Microseconds\":%.3f,\"scope\":\"pure"
              + " graph only\"}%n",
          detour ? "32-block goal with 33-block lateral detour" : "128-block flat",
          expanded,
          sink,
          times[150] / 1000.,
          times[285] / 1000.);
    }
  }
}

import dev.avatarcraft.npc.ai.LocalActionPolicy;
import java.util.Arrays;

/** Measures ranking only; excludes world scans, pathfinding and provider latency. */
public class NpcPolicyBenchmark {
  private static volatile double sink;

  public static void main(String[] args) {
    var model = LocalActionPolicy.bundled();
    double[][] candidates = new double[24][];
    for (int i = 0; i < candidates.length; i++)
      candidates[i] = LocalActionPolicy.features("approach", false, i * .6, 0, 20, 1, 16);
    for (int warm = 0; warm < 1000; warm++) rank(model, candidates);
    long[] ns = new long[1000];
    for (int i = 0; i < ns.length; i++) {
      long start = System.nanoTime();
      rank(model, candidates);
      ns[i] = System.nanoTime() - start;
    }
    Arrays.sort(ns);
    System.out.printf(
        java.util.Locale.ROOT,
        "{\"candidates\":24,\"samples\":1000,\"p50Microseconds\":%.3f,\"p95Microseconds\":%.3f,\"scope\":\"ranker"
            + " only, not full NPC tick\"}%n",
        ns[500] / 1000.,
        ns[950] / 1000.);
  }

  private static void rank(LocalActionPolicy m, double[][] candidates) {
    double best = Double.NEGATIVE_INFINITY;
    for (var x : candidates) best = Math.max(best, m.score(x));
    sink = best;
  }
}

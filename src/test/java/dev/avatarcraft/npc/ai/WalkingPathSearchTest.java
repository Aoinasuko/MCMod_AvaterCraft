package dev.avatarcraft.npc.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WalkingPathSearchTest {
  @org.junit.jupiter.api.Test
  void descendingNeedsClearanceBeforeTheActorHasDropped() {
    var start = new WalkingPathSearch.Cell(0, 1, 0);
    var lower = new WalkingPathSearch.Cell(1, 0, 0);
    var terrain =
        new WalkingPathSearch.Terrain() {
          public boolean stand(WalkingPathSearch.Cell p) {
            return p.equals(start) || p.equals(lower);
          }

          public boolean headroom(WalkingPathSearch.Cell p) {
            return !p.equals(lower);
          }
        };
    assertEquals(
        WalkingPathSearch.State.UNREACHABLE,
        run(new WalkingPathSearch(start, java.util.List.of(lower), terrain)).state());
  }

  private static WalkingPathSearch run(WalkingPathSearch search) {
    for (int i = 0; i < 1000 && search.state() == WalkingPathSearch.State.SEARCHING; i++)
      search.step(128, Long.MAX_VALUE);
    return search;
  }

  @Test
  void straight128BlocksUsesSparseDirectedSearch() {
    var search =
        run(
            new WalkingPathSearch(
                new WalkingPathSearch.Cell(0, 0, 0),
                List.of(new WalkingPathSearch.Cell(128, 0, 0)),
                p -> p.y() == 0));
    assertEquals(WalkingPathSearch.State.FOUND, search.state());
    assertEquals(129, search.path().size());
    assertTrue(search.expanded() < 1000);
  }

  @Test
  void findsDetourOverThirtyBlocksOffTheOldFourBlockCorridor() {
    var search =
        run(
            new WalkingPathSearch(
                new WalkingPathSearch.Cell(0, 0, 0),
                List.of(new WalkingPathSearch.Cell(32, 0, 0)),
                p -> p.y() == 0 && !(p.x() == 12 && Math.abs(p.z()) <= 32)));
    assertEquals(WalkingPathSearch.State.FOUND, search.state());
    assertTrue(search.path().stream().anyMatch(p -> Math.abs(p.z()) >= 33));
    assertTrue(search.path().stream().noneMatch(p -> p.x() == 12 && Math.abs(p.z()) <= 32));
  }

  @Test
  void doesNotCutBlockedDiagonalCorners() {
    var start = new WalkingPathSearch.Cell(0, 0, 0);
    var end = new WalkingPathSearch.Cell(1, 0, 1);
    var search =
        run(new WalkingPathSearch(start, List.of(end), p -> p.equals(start) || p.equals(end)));
    assertEquals(WalkingPathSearch.State.UNREACHABLE, search.state());
  }

  @Test
  void searchResumesAndCompletedPathDoesNotRecompute() {
    var calls = new AtomicInteger();
    var search =
        new WalkingPathSearch(
            new WalkingPathSearch.Cell(0, 0, 0),
            List.of(new WalkingPathSearch.Cell(128, 0, 0)),
            p -> {
              calls.incrementAndGet();
              return p.y() == 0;
            });
    assertEquals(WalkingPathSearch.State.SEARCHING, search.step(2, Long.MAX_VALUE));
    assertEquals(2, search.expanded());
    run(search);
    int count = calls.get();
    search.step(512, Long.MAX_VALUE);
    assertEquals(WalkingPathSearch.State.FOUND, search.state());
    assertEquals(count, calls.get());
  }

  @Test
  void expiredTimeBudgetAndOutOfRangeTargetNeverPretendSuccess() {
    var start = new WalkingPathSearch.Cell(0, 0, 0);
    var search =
        new WalkingPathSearch(
            start, List.of(new WalkingPathSearch.Cell(128, 0, 0)), p -> p.y() == 0);
    assertEquals(WalkingPathSearch.State.SEARCHING, search.step(512, System.nanoTime() - 1));
    assertEquals(0, search.expanded());
    assertEquals(
        WalkingPathSearch.State.UNREACHABLE,
        new WalkingPathSearch(start, List.of(new WalkingPathSearch.Cell(129, 0, 0)), p -> true)
            .state());
  }
}

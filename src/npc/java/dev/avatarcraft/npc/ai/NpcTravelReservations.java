package dev.avatarcraft.npc.ai;

import java.util.*;

/** Pure bounded reservation math. World collision/route validation happens before each leg. */
public final class NpcTravelReservations {
  public static final int MAX_DISTANCE = 512;
  public static final int LEG_DISTANCE = 112; // spare room for landing-cell resolution

  public static List<List<Integer>> straight(int x, int y, int z, int tx, int ty, int tz) {
    double distance = Math.sqrt(Math.pow(tx - x, 2.) + Math.pow(ty - y, 2.) + Math.pow(tz - z, 2.));
    if (distance > MAX_DISTANCE)
      throw new IllegalArgumentException("TRAVEL_TOO_FAR: maximum reservation is 512 blocks");
    int legs = Math.max(1, (int) Math.ceil(distance / LEG_DISTANCE));
    var result = new ArrayList<List<Integer>>();
    for (int i = 1; i <= legs; i++)
      result.add(
          List.of(
              x + (int) Math.round((tx - x) * (double) i / legs),
              y + (int) Math.round((ty - y) * (double) i / legs),
              z + (int) Math.round((tz - z) * (double) i / legs)));
    return result;
  }

  private NpcTravelReservations() {}
}

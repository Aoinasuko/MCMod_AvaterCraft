package dev.avatarcraft.npc.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlayerChatPolicyTest {
  @Test
  void unnamedChatPrefersOwnNearestNpcAndNamedChatTargetsTheNamedNpc() {
    var peers =
        List.of(
            new PlayerChatPolicy.Candidate(1, "tortobu", true, 36, true),
            new PlayerChatPolicy.Candidate(2, "Another", false, 1, true),
            new PlayerChatPolicy.Candidate(3, "OtherOwned", true, 9, true));
    assertEquals(3, PlayerChatPolicy.recipient("こんにちは", peers).orElseThrow());
    assertEquals(1, PlayerChatPolicy.recipient("@TORTOBU 石を掘って", peers).orElseThrow());
    assertEquals(2, PlayerChatPolicy.recipient("Another 元気？", peers).orElseThrow());
    assertEquals(List.of(3, 1), PlayerChatPolicy.recipients("二人とも石を探して", peers));
    assertEquals(List.of(1, 2), PlayerChatPolicy.recipients("tortobu と Another、こんにちは", peers));
  }

  @Test
  void remoteOrOccludedNpcCannotHearChat() {
    assertTrue(
        PlayerChatPolicy.recipient(
                "こんにちは",
                List.of(
                    new PlayerChatPolicy.Candidate(1, "tortobu", true, 145, true),
                    new PlayerChatPolicy.Candidate(2, "Other", true, 1, false)))
            .isEmpty());
  }

  @Test
  void onlyTheCurrentAuthenticatedOwnerCanStartOrStopWork() {
    assertTrue(PlayerChatPolicy.mayStartTask(true, true, false, "石を掘る"));
    assertTrue(PlayerChatPolicy.mayStartTask(true, true, false, "__stop__"));
    assertFalse(PlayerChatPolicy.mayStartTask(false, true, false, "石を掘る"));
    assertFalse(PlayerChatPolicy.mayStartTask(true, false, false, "石を掘る"));
    assertFalse(PlayerChatPolicy.mayStartTask(true, true, true, "石を掘る"));
    assertFalse(PlayerChatPolicy.mayStartTask(true, true, false, ""));
    assertFalse(PlayerChatPolicy.mayStartTask(true, true, false, "x".repeat(2049)));
  }
}

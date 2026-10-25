package dev.avatarcraft.player.client;

import dev.avatarcraft.avatar.*;
import dev.avatarcraft.client.AvatarSession;
import java.util.*;

/** Player-only pose, arm extraction and event expression clocks. Created only when rendered. */
final class PlayerAvatarState {
  private static final Map<AvatarSession, PlayerAvatarState> STATES = new WeakHashMap<>();
  final AvatarPose pose;
  final FirstPersonArm leftFirstPerson, rightFirstPerson;
  final MotionFacing facing = new MotionFacing();
  final ExpressionPlayer expressions = new ExpressionPlayer(new Random());

  static PlayerAvatarState of(AvatarSession session) {
    return STATES.computeIfAbsent(session, s -> new PlayerAvatarState(s.archive.avatar()));
  }

  private PlayerAvatarState(Avatar avatar) {
    pose = new AvatarPose(avatar);
    leftFirstPerson = new FirstPersonArm(avatar, true);
    rightFirstPerson = new FirstPersonArm(avatar, false);
  }
}

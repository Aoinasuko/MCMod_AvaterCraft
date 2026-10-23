package dev.avatarcraft.avatar;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.*;

class MotionRuntimeTest {
  @Test
  void horizontalMotionsKeepGazeForwardAndFitSwimmingHeight() throws Exception {
    var path = TestAvatarFixture.path();
    var a = AvatarArchive.read(path).avatar();
    for (String action : List.of("swim", "crawl", "elytra")) {
      var pose = new AvatarPose(a);
      int head = a.humanoid.get("Head"), hips = a.humanoid.get("Hips");
      var rest = pose.world[head].getUnnormalizedRotation(new Quaternionf()).normalize();
      var input = new MotionSignals();
      input.speed = .1f;
      input.inWater = action.equals("swim");
      input.crawling = action.equals("crawl");
      input.fallFlying = action.equals("elytra");
      pose.animateMotion(input, 0, new Vector3f(), 0);
      var gaze =
          pose.world[head]
              .getUnnormalizedRotation(new Quaternionf())
              .normalize()
              .mul(rest.conjugate())
              .transform(new Vector3f(0, 0, 1));
      assertTrue(Math.abs(gaze.y) < .3f, action + " looks at the ground");
      float pelvis = (pose.world[hips].m31() - a.feetOffset) * 1.8f / a.height;
      assertTrue(pelvis > 0 && pelvis < .6f, action + " floats above the low player pose");
    }
  }

  @Test
  void sidewaysMovementFacesLookDirectionDespiteVanillaTorsoTurningTowardVelocity() {
    var facing = new MotionFacing();
    for (int i = 0; i < 60; i++)
      assertEquals(180, Math.abs(facing.evaluate(270, 180, .1f, true, i / 60d)), .001);
    float resting = 0;
    for (int i = 120; i <= 180; i++) resting = facing.evaluate(270, 180, .1f, false, i / 60d);
    assertEquals(-90, resting, .001);
  }

  @Test
  void facingUsesShortArcAndDoesNotSnapAcrossAngleWrap() {
    var facing = new MotionFacing();
    assertEquals(179, facing.evaluate(179, 179, .1f, true, 0));
    float next = facing.evaluate(-179, -179, .1f, true, 1 / 60d);
    assertTrue(Math.abs(MotionFacing.wrap(next - 179)) < 1);
  }

  private MotionPack.Key key(float time, float angle, float y) {
    var k = new MotionPack.Key();
    k.time = time;
    k.rotation = new Quaternionf().rotateX(angle).get(new float[4]);
    k.position = new float[] {0, y, 0};
    return k;
  }

  private MotionPack.Clip clip(String layer, String bone, boolean loop, MotionPack.Key... keys) {
    var c = new MotionPack.Clip();
    c.name = "fixture";
    c.layer = layer;
    c.duration = keys[keys.length - 1].time;
    c.loop = loop;
    c.tracks.put(bone, keys);
    return c;
  }

  private MotionPack pack() {
    var p = new MotionPack();
    p.neutral.put("RightUpperArm", key(0, .3f, 0));
    p.neutral.put("LeftUpperArm", key(0, -.3f, 0));
    p.clips.put("idle", clip("base", "Hips", false, key(0, 0, 0), key(1, 0, 0)));
    p.clips.put(
        "walk", clip("base", "LeftLowerLeg", true, key(0, 0, 0), key(1, .8f, 0), key(2, 0, 0)));
    p.clips.put("upper", clip("upper", "RightUpperArm", true, key(0, -1, 0), key(1, -1, 0)));
    p.clips.put("upper_left", clip("upper", "LeftUpperArm", true, key(0, 1, 0), key(1, 1, 0)));
    p.clips.put("hurt", clip("additive", "Spine", false, key(0, .2f, 0), key(1, .2f, 0)));
    var actions = new HashSet<>(MotionPack.BASE);
    actions.addAll(MotionPack.UPPER);
    actions.add("hurt");
    for (String action : actions) {
      var b = new MotionPack.Binding();
      b.layer =
          MotionPack.BASE.contains(action)
              ? "base"
              : MotionPack.UPPER.contains(action) ? "upper" : "additive";
      b.clip =
          b.layer.equals("upper")
              ? "upper"
              : b.layer.equals("additive") ? "hurt" : action.equals("walk") ? "walk" : "idle";
      b.mirroredClip = b.layer.equals("upper") ? "upper_left" : b.clip;
      b.blendSeconds = 0;
      p.actions.put(action, b);
    }
    return p;
  }

  private Avatar avatar(MotionPack motions) {
    var a = new Avatar();
    a.formatVersion = 1;
    a.id = "motion";
    a.name = "Motion";
    a.motions = motions;
    String[] names = {
      "Hips", "Spine", "RightUpperArm", "LeftUpperArm", "LeftLowerLeg", "Head", "Neck"
    };
    a.bones = new Avatar.Bone[names.length];
    for (int i = 0; i < names.length; i++) {
      var b = new Avatar.Bone();
      b.name = names[i];
      b.parent = i == 0 ? -1 : 0;
      b.position = new float[] {0, i == 0 ? 1 : .1f, 0};
      b.rotation = new float[] {0, 0, 0, 1};
      b.scale = new float[] {1, 1, 1};
      a.bones[i] = b;
      a.humanoid.put(names[i], i);
    }
    a.meshes = new Avatar.Mesh[0];
    a.materials = new Avatar.Material[0];
    return a;
  }

  @Test
  void linearQuaternionSamplingAndOneShotHold() {
    var p = pack();
    p.clips.put("one", clip("base", "Spine", false, key(0, 0, 0), key(1, 1, .2f)));
    var middle = p.sample("one", .5).get("Spine");
    assertEquals(.1f, middle.position().y, 1e-5);
    assertEquals(.5f, middle.rotation().angle(), 1e-5);
    assertEquals(1, p.sample("one", 10).get("Spine").rotation().angle(), 1e-5);
  }

  @Test
  void cyclicMotionHasContinuousBoundary() {
    var p = pack();
    var a = p.sample("walk", 1.99999).get("LeftLowerLeg");
    var b = p.sample("walk", 2.00001).get("LeftLowerLeg");
    assertTrue(a.rotation().equals(b.rotation(), .0001f));
  }

  @Test
  void selectorCoversMovementAndEnvironmentPriorities() {
    var s = new MotionSignals();
    assertEquals("idle", s.baseAction());
    s.speed = .1f;
    s.forward = .1f;
    assertEquals("walk", s.baseAction());
    s.sprinting = true;
    assertEquals("run", s.baseAction());
    s.forward = -.1f;
    assertEquals("backward", s.baseAction());
    s.forward = 0;
    s.side = .1f;
    assertEquals("strafe_left", s.baseAction());
    s.side = -.1f;
    assertEquals("strafe_right", s.baseAction());
    s.crouching = true;
    assertEquals("crouch_walk", s.baseAction());
    s.speed = 0;
    assertEquals("crouch_idle", s.baseAction());
    s.grounded = false;
    s.verticalSpeed = .2f;
    assertEquals("jump", s.baseAction());
    s.verticalSpeed = -.2f;
    assertEquals("fall", s.baseAction());
    s.climbing = true;
    assertEquals("climb", s.baseAction());
    s.inWater = true;
    assertEquals("swim_idle", s.baseAction());
    s.speed = .1f;
    assertEquals("swim", s.baseAction());
    s.fallFlying = true;
    assertEquals("elytra", s.baseAction());
    s.passenger = true;
    assertEquals("ride", s.baseAction());
    s.sleeping = true;
    assertEquals("sleep", s.baseAction());
    s.dead = true;
    assertEquals("death", s.baseAction());
  }

  @Test
  void useActionOverridesSwingAndHolding() {
    var s = new MotionSignals();
    s.hasItem = true;
    assertEquals("hold", s.overlayAction());
    s.swingProgress = .5f;
    assertEquals("attack", s.overlayAction());
    s.mining = true;
    assertEquals("mine", s.overlayAction());
    s.useAction = "eat";
    assertEquals("eat", s.overlayAction());
    s.dead = true;
    assertEquals("", s.overlayAction());
  }

  @Test
  void upperBodyMotionLeavesWalkingKneeIntact() {
    var p = new MotionPlayer(pack());
    var s = new MotionSignals();
    s.speed = .1f;
    s.forward = .1f;
    s.useAction = "eat";
    Map<String, MotionPack.BonePose> result = null;
    for (int i = 0; i <= 10; i++) result = p.evaluate(s, i * .1);
    assertEquals(.8f, result.get("LeftLowerLeg").rotation().angle(), .0001);
    assertEquals(1, result.get("RightUpperArm").rotation().angle(), .0001);
  }

  @Test
  void leftHandUsesSeparatelyRetargetedMirroredClip() {
    var p = new MotionPlayer(pack());
    var s = new MotionSignals();
    s.useAction = "eat";
    s.leftHand = true;
    var pose = p.evaluate(s, 0);
    assertEquals(1, pose.get("LeftUpperArm").rotation().angle(), .0001);
    assertEquals(.3f, pose.get("RightUpperArm").rotation().angle(), .0001);
  }

  @Test
  void crossfadeStartsFromCurrentPoseWithoutSnap() {
    var pack = pack();
    pack.actions.get("walk").blendSeconds = .5f;
    var player = new MotionPlayer(pack);
    var s = new MotionSignals();
    player.evaluate(s, 0);
    s.speed = .1f;
    s.forward = .1f;
    var changed = player.evaluate(s, .1);
    assertEquals(0, changed.get("LeftLowerLeg").rotation().angle(), 1e-5);
    var blended = player.evaluate(s, .2).get("LeftLowerLeg");
    assertNotNull(blended);
    assertTrue(blended.rotation().angle() > 0 && blended.rotation().angle() < .08);
  }

  @Test
  void configuredPlaybackSpeedChangesPhase() {
    var pack = pack();
    pack.actions.get("walk").speed = 2;
    var player = new MotionPlayer(pack);
    var s = new MotionSignals();
    s.speed = .1f;
    s.forward = .1f;
    Map<String, MotionPack.BonePose> result = null;
    for (int i = 0; i <= 5; i++) result = player.evaluate(s, i * .1);
    assertEquals(.8f, result.get("LeftLowerLeg").rotation().angle(), .0001);
    pack.clips.put("upper", clip("upper", "RightUpperArm", false, key(0, 0, 0), key(1, 1, 0)));
    pack.actions.get("attack").speed = 2;
    s.swingProgress = .25f;
    result = new MotionPlayer(pack).evaluate(s, 0);
    assertEquals(.5f, result.get("RightUpperArm").rotation().angle(), .0001);
  }

  @Test
  void additiveHurtDoesNotStopLocomotion() {
    var player = new MotionPlayer(pack());
    var s = new MotionSignals();
    s.hurt = true;
    s.speed = .1f;
    s.forward = .1f;
    var result = player.evaluate(s, 0);
    assertEquals("walk", player.baseAction());
    assertEquals(.2f, result.get("Spine").rotation().angle(), .0001);
  }

  @Test
  void landingPlaysAfterFallingThenReturnsToIdle() {
    var player = new MotionPlayer(pack());
    var s = new MotionSignals();
    s.grounded = false;
    s.verticalSpeed = -.3f;
    player.evaluate(s, 0);
    s.grounded = true;
    s.verticalSpeed = 0;
    player.evaluate(s, .1);
    assertEquals("land", player.baseAction());
    player.evaluate(s, 2);
    assertEquals("idle", player.baseAction());
  }

  @Test
  void schemaRejectsIncompleteAssignmentsAndUnorderedKeys() throws Exception {
    var p = pack();
    var a = avatar(p);
    p.validate(a);
    p.actions.remove("jump");
    assertThrows(IOException.class, () -> p.validate(a));
    var invalid = pack();
    invalid.clips.get("walk").tracks.get("LeftLowerLeg")[1].time = 0;
    assertThrows(IOException.class, () -> invalid.validate(a));
  }

  @Test
  void schemaRejectsUpperBodyClipMovingHips() {
    var p = pack();
    p.clips.get("upper").tracks.put("Hips", new MotionPack.Key[] {key(0, 0, 0), key(1, 0, 0)});
    assertThrows(IOException.class, () -> p.validate(avatar(p)));
  }

  @Test
  void humanoidMotionProducesFiniteSkinnedTransforms() {
    var p = pack();
    var a = avatar(p);
    var pose = new AvatarPose(a);
    var signals = new MotionSignals();
    signals.speed = .1f;
    signals.forward = .1f;
    signals.useAction = "eat";
    for (int i = 0; i < 20; i++) pose.animateMotion(signals, i * .05, new Vector3f(), 0);
    for (var matrix : pose.world) assertTrue(matrix.isFinite());
    assertEquals("walk", pose.motionAction());
  }

  @Test
  void configuredExportContainsAllAssignmentsAndHumanJointMotion() throws Exception {
    var path = TestAvatarFixture.path();
    var a = AvatarArchive.read(path).avatar();
    assertNotNull(a.motions);
    assertEquals(34, a.motions.actions.size());
    var walk = a.motions.clips.get(a.motions.actions.get("walk").clip);
    assertTrue(walk.tracks.containsKey("LeftLowerLeg"));
    assertTrue(
        Arrays.stream(walk.tracks.get("LeftLowerLeg"))
            .anyMatch(k -> MotionPack.pose(k).rotation().angle() > .2));
    for (var entry : a.motions.clips.entrySet())
      for (int frame = 0; frame <= 8; frame++)
        for (var bone :
            a.motions.sample(entry.getKey(), entry.getValue().duration * frame / 8).values()) {
          assertTrue(bone.rotation().isFinite(), entry.getKey());
          assertTrue(bone.position().isFinite(), entry.getKey());
          assertEquals(1, bone.rotation().lengthSquared(), .0001, entry.getKey());
        }
    var pose = new AvatarPose(a);
    for (String state : MotionPack.BASE) {
      var input = new MotionSignals();
      switch (state) {
        case "walk" -> {
          input.speed = .1f;
          input.forward = .1f;
        }
        case "run" -> {
          input.speed = .2f;
          input.forward = .2f;
          input.sprinting = true;
        }
        case "jump" -> {
          input.grounded = false;
          input.verticalSpeed = .2f;
        }
        case "fall" -> {
          input.grounded = false;
          input.verticalSpeed = -.2f;
        }
      }
      for (int frame = 0; frame < 5; frame++)
        pose.animateMotion(input, frame * .05, new Vector3f(), 0);
      for (var mesh : a.meshes)
        for (float value : pose.skin(mesh, a.defaults)) assertTrue(Float.isFinite(value));
    }
  }
}

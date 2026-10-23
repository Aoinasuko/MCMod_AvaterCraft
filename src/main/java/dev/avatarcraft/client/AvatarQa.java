package dev.avatarcraft.client;

import com.google.gson.JsonObject;
import dev.avatarcraft.avatar.AvatarArchive;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import org.joml.Vector3f;

/** Development-run-only live input scenarios. Never substitutes synthetic animation signals. */
final class AvatarQa {
  private static final boolean ENABLED = Boolean.getBoolean("avatarcraft.qa");
  private static final Path REQUEST = AvatarLibrary.ROOT.resolve("qa-request.json");
  private static final Path REPORT = AvatarLibrary.ROOT.resolve("qa-report.json");
  private static String scenario = "idle";
  private static int remaining, elapsed, poll;
  private static final Map<String, Integer> actions = new LinkedHashMap<>();
  private static final Map<String, Float> maxAngles = new LinkedHashMap<>(),
      limits = new LinkedHashMap<>();
  private static float lowestFoot = Float.POSITIVE_INFINITY;
  private static int frames;
  private static final Map<String, String> USE_ITEMS =
      Map.of(
          "eat",
          "golden_apple",
          "drink",
          "potion",
          "block",
          "shield",
          "bow",
          "bow",
          "crossbow_charge",
          "crossbow",
          "trident",
          "trident",
          "spyglass",
          "spyglass");

  static void tick() {
    if (!ENABLED) return;
    var mc = Minecraft.getInstance();
    if (mc.player == null || !mc.hasSingleplayerServer()) return;
    try {
      if (++poll % 5 == 0 && Files.isRegularFile(REQUEST)) {
        JsonObject request =
            AvatarArchive.GSON.fromJson(Files.readString(REQUEST), JsonObject.class);
        Files.delete(REQUEST);
        release();
        scenario = request.get("scenario").getAsString();
        elapsed = 0;
        remaining =
            request.has("ticks") ? Math.clamp(request.get("ticks").getAsInt(), 1, 1200) : 200;
        actions.clear();
        maxAngles.clear();
        limits.clear();
        frames = 0;
        lowestFoot = Float.POSITIVE_INFINITY;
        if (scenario.equals("setup")) {
          command("fill -50 159 -50 50 159 50 minecraft:stone");
          command("tp @s 0 160 0 180 0");
          command("time set day");
          command("weather clear");
          command("gamemode survival @s");
          mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
          mc.options.fov().set(60);
          mc.options.pauseOnLostFocus = false;
        }
        if (USE_ITEMS.containsKey(scenario))
          command(
              "item replace entity @s hotbar.0 with minecraft:"
                  + USE_ITEMS.get(scenario)
                  + (scenario.equals("eat") ? " 64" : " 1"));
        else if (!scenario.equals("hurt"))
          command("item replace entity @s hotbar.0 with minecraft:air");
        if (request.has("command")) command(request.get("command").getAsString());
        if (scenario.equals("bow") || scenario.equals("crossbow_charge"))
          command("item replace entity @s hotbar.1 with minecraft:arrow 64");
        if (scenario.equals("hurt")) command("effect give @s minecraft:regeneration 20 4 true");
        if (scenario.equals("cleanup")) {
          command("effect clear @s");
          command("item replace entity @s hotbar.1 with minecraft:air");
          mc.options.fov().set(70);
          mc.options.pauseOnLostFocus = true;
          mc.options.save();
          remaining = 1;
        }
      }
      if (remaining <= 0) return;
      if (mc.gui.screen() != null) {
        remaining = 0;
        release();
        write();
        return;
      }
      boolean walk =
          Set.of(
                  "walk",
                  "run",
                  "crouch_walk",
                  "jump",
                  "backward",
                  "strafe_left",
                  "strafe_right",
                  "swim",
                  "climb")
              .contains(scenario);
      mc.options.keyUp.setDown(
          walk && !Set.of("backward", "strafe_left", "strafe_right").contains(scenario));
      mc.options.keyDown.setDown(scenario.equals("backward"));
      mc.options.keyLeft.setDown(scenario.equals("strafe_left"));
      mc.options.keyRight.setDown(scenario.equals("strafe_right"));
      mc.options.keySprint.setDown(scenario.equals("run") || scenario.equals("swim"));
      mc.player.setSprinting(scenario.equals("run") || scenario.equals("swim"));
      mc.options.keyShift.setDown(scenario.startsWith("crouch"));
      mc.options.keyJump.setDown(scenario.equals("jump") && elapsed % 30 < 3);
      mc.options.keyUse.setDown(USE_ITEMS.containsKey(scenario));
      if (USE_ITEMS.containsKey(scenario) && elapsed % 35 == 5)
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
      if (scenario.equals("creative_fly") && mc.player.getAbilities().mayfly) {
        mc.player.getAbilities().flying = true;
        mc.player.onUpdateAbilities();
      }
      if (scenario.equals("attack") && elapsed % 12 == 0)
        mc.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
      if (scenario.equals("hurt") && elapsed % 40 == 0) command("damage @s 1 minecraft:generic");
      if (scenario.equals("turn")) {
        mc.player.setYRot(180 + elapsed * 1.5f);
        mc.player.setXRot(0);
      }
      mc.player.sendOverlayMessage(
          Component.literal("AvatarCraft QA: " + scenario + " / " + remaining + " ticks"));
      elapsed++;
      remaining--;
      if (remaining == 0) release();
      if (elapsed % 10 == 0 || remaining == 0) write();
    } catch (Exception e) {
      remaining = 0;
      release();
      System.getLogger("AvatarCraft QA").log(System.Logger.Level.ERROR, "Live scenario failed", e);
    }
  }

  private static void command(String value) {
    Minecraft.getInstance().player.connection.sendCommand(value);
  }

  private static void release() {
    var mc = Minecraft.getInstance();
    for (var key :
        List.of(
            mc.options.keyUp,
            mc.options.keyDown,
            mc.options.keyLeft,
            mc.options.keyRight,
            mc.options.keyJump,
            mc.options.keyShift,
            mc.options.keySprint,
            mc.options.keyUse)) key.setDown(false);
    if (mc.player != null) mc.player.setSprinting(false);
  }

  static void record(AvatarSession session) {
    if (!ENABLED || remaining <= 0) return;
    frames++;
    actions.merge(
        session.pose.motionAction() + "/" + session.pose.overlayMotionAction(), 1, Integer::sum);
    session
        .pose
        .physicsStatus()
        .forEach((name, status) -> maxAngles.merge(name, status.angle() * 180, Math::max));
    var avatar = session.archive.avatar();
    for (var spring : avatar.springs) {
      String name = spring.parameter.isBlank() ? spring.name : spring.parameter;
      float limit = spring.maxAngleX;
      for (var n : spring.nodes) limit = Math.max(limit, n.maxAngleX);
      limits.put(name, limit);
    }
    for (String side : List.of("LeftFoot", "RightFoot")) {
      Integer bone = avatar.humanoid.get(side);
      if (bone != null)
        lowestFoot =
            Math.min(
                lowestFoot,
                session.pose.world[bone].getTranslation(new Vector3f()).y - avatar.feetOffset);
    }
  }

  private static void write() throws Exception {
    var player = Minecraft.getInstance().player;
    var result = new LinkedHashMap<String, Object>();
    result.put("scenario", scenario);
    result.put("elapsedTicks", elapsed);
    result.put("remainingTicks", remaining);
    result.put("renderedFrames", frames);
    result.put("actualActions", actions);
    result.put("maxPhysicsAnglesDegrees", maxAngles);
    result.put("angleLimitsDegrees", limits);
    result.put("lowestFootModelY", Float.isFinite(lowestFoot) ? lowestFoot : 0);
    result.put("position", List.of(player.getX(), player.getY(), player.getZ()));
    result.put("gamePose", player.getPose().toString());
    result.put("speed", player.getDeltaMovement().horizontalDistance());
    AvatarLibrary.atomicWrite(REPORT, AvatarArchive.GSON.toJson(result));
  }
}

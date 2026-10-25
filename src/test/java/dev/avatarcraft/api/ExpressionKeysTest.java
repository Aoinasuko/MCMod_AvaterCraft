package dev.avatarcraft.api;

import static org.junit.jupiter.api.Assertions.*;

import dev.avatarcraft.avatar.*;
import dev.avatarcraft.npc.CompanionExpressions;
import dev.avatarcraft.player.PlayerExpressions;
import java.util.*;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class ExpressionKeysTest {
  private AvatarArchive model() throws Exception {
    return AvatarArchive.read(java.nio.file.Path.of(
        "src/main/resources/assets/bep_3davater_loader/avatars/tortobu.avc"));
  }

  @Test
  void gameSignalsAloneNeverTriggerFacesAndKeysRemainPerActor() throws Exception {
    var source = model();
    String shape = source.expressions().shapes(source.avatar()).iterator().next();
    var config = new ExpressionConfig();
    config.events.put("smile", Map.of(shape, 65f));
    config.events.get("eat").put(shape, 35f);
    config.events.get("hurt").put(shape, 90f);
    var archive = new AvatarArchive(source.avatar(), source.images(), config);
    var player = new AvatarAnimation(archive, 1);
    var companion = new AvatarAnimation(archive, 2);
    var signals = new MotionSignals();
    signals.useAction = "eat";
    signals.hurt = true;
    player.update(signals, 0, new Vector3f(), 0);
    assertEquals(source.avatar().defaults.getOrDefault(shape, 0f), player.weights().getOrDefault(shape, 0f));
    player.expressionKeys.set("smile");
    companion.expressionKeys.set(CompanionExpressions.keys(true, true));
    player.update(signals, .01, new Vector3f(), 0);
    companion.update(signals, .01, new Vector3f(), 0);
    assertEquals(65f, player.weights().get(shape));
    assertEquals(90f, companion.weights().get(shape));
    player.expressionKeys.set(PlayerExpressions.keys(true, true, List.of("smile")));
    player.update(signals, .02, new Vector3f(), 0);
    assertEquals(90f, player.weights().get(shape));
    companion.expressionKeys.set(CompanionExpressions.keys(true, false));
    companion.update(signals, .02, new Vector3f(), 0);
    assertEquals(35f, companion.weights().get(shape));
    player.expressionKeys.clear();
    player.update(signals, .03, new Vector3f(), 0);
    assertEquals(source.avatar().defaults.getOrDefault(shape, 0f), player.weights().getOrDefault(shape, 0f));
  }

  @Test
  void customKeysRoundTripAndInvalidKeysCannotReplaceTheCurrentSelection() throws Exception {
    var source = model();
    var config = source.expressions().copy();
    config.events.put("companion.happy", Map.of());
    var restored = AvatarArchive.GSON.fromJson(AvatarArchive.GSON.toJson(config), ExpressionConfig.class);
    restored.validate(source.avatar());
    assertTrue(restored.events.containsKey("companion.happy"));
    var keys = new ExpressionKeys();
    var mutable = new ArrayList<>(List.of("companion.happy"));
    keys.set(mutable);
    mutable.clear();
    assertEquals(List.of("companion.happy"), keys.keys());
    assertThrows(IllegalArgumentException.class, () -> keys.set("../invalid"));
    assertEquals(List.of("companion.happy"), keys.keys());
    restored.events.put("../invalid", Map.of());
    assertThrows(java.io.IOException.class, () -> restored.validate(source.avatar()));
  }
}

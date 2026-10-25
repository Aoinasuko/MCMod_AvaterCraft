package dev.avatarcraft.npc;

import dev.avatarcraft.api.AvatarCatalog;
import dev.avatarcraft.avatar.AvatarArchive;
import java.util.*;

/**
 * User-editable character data, deliberately separate from API credentials and provider settings.
 */
public final class Personality {
  public int version = 1;
  public String id = "companion",
      name = "Companion",
      avatar = "",
      instructions = "",
      speakingStyle = "",
      completionMessage = "",
      goal = "所有者を手伝う";
  public List<String> allowedSkills =
      new ArrayList<>(
          List.of(
              "observe",
              "move_to",
              "follow",
              "equip",
              "find_blocks",
              "dig",
              "place",
              "attack",
              "deposit",
              "withdraw",
              "give_item",
              "find_chests",
              "craft",
              "eat",
              "say",
              "wait",
              "stop",
              "remember_location",
              "forget_location"));
  public int workSpeechSeconds = 45, nearbyConversationSeconds = 30;

  public void validate() {
    if (version != 1 || !AvatarCatalog.validId(id))
      throw new IllegalArgumentException("Invalid personality version or ID");
    if (!avatar.isEmpty() && !AvatarCatalog.validId(avatar))
      throw new IllegalArgumentException("Invalid avatar reference");
    if (name.isBlank()
        || name.length() > 64
        || instructions.length() > 4000
        || speakingStyle.length() > 1000
        || completionMessage == null
        || completionMessage.length() > 512
        || goal.length() > 1000)
      throw new IllegalArgumentException("Personality text exceeds its bounds");
    if (workSpeechSeconds < 30
        || workSpeechSeconds > 60
        || nearbyConversationSeconds < 15
        || nearbyConversationSeconds > 3600)
      throw new IllegalArgumentException(
          "Periodic speech must be every 30..60 seconds; conversation interval must be 15..3600");
    var known =
        Set.of(
            "observe",
            "move_to",
            "follow",
            "equip",
            "find_blocks",
            "dig",
            "place",
            "attack",
            "deposit",
            "withdraw",
            "give_item",
            "find_chests",
            "craft",
            "eat",
            "say",
            "wait",
            "stop",
            "remember_location",
            "forget_location");
    var allKnown = dev.avatarcraft.npc.ai.NpcTools.names();
    if (allowedSkills == null
        || allowedSkills.size() > allKnown.size()
        || !allKnown.containsAll(allowedSkills))
      throw new IllegalArgumentException("Unknown or excessive skills");
    if (new HashSet<>(allowedSkills).size() != allowedSkills.size())
      throw new IllegalArgumentException("Duplicate skills");
  }

  public String json() {
    validate();
    return AvatarArchive.GSON.toJson(this);
  }

  public static Personality parse(String json) {
    if (json == null || json.length() > 16384)
      throw new IllegalArgumentException("Personality JSON exceeds 16 KiB");
    var value = AvatarArchive.GSON.fromJson(json, Personality.class);
    if (value == null
        || value.id == null
        || value.name == null
        || value.avatar == null
        || value.instructions == null
        || value.speakingStyle == null
        || value.completionMessage == null
        || value.goal == null)
      throw new IllegalArgumentException("Personality fields cannot be null");
    value.validate();
    return value;
  }

  public static Personality preset(String id) {
    if (!"tortobu".equals(id))
      throw new IllegalArgumentException(
          "Use an external personality profile for custom characters");
    try (var stream =
        Personality.class.getResourceAsStream("/data/avatarcraftnpc/personalities/tortobu.json")) {
      if (stream == null) throw new IllegalStateException("Missing bundled personality");
      return parse(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }
}

package dev.avatarcraft.npc.client;

import com.google.gson.*;
import dev.avatarcraft.npc.NpcNetwork;
import dev.avatarcraft.npc.memory.JournalArchive;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** Explicitly requested, bounded file reception; names never come from remote path input. */
final class JournalExportClient {
  private static UUID token;
  private static Path partial, destination;
  private static OutputStream output;
  private static MessageDigest digest;
  private static String expectedHash, message = "";
  private static long received, expected;
  private static int index;
  private static long activity;
  static boolean busy() { return token != null; }
  static String status() { return message; }
  static void begin(UUID actor) {
    if (busy()) { message = "エクスポート中です"; return; }
    token = UUID.randomUUID(); received = expected = index = 0; message = "60分の行動ログを準備中…";
    activity = System.currentTimeMillis();
    var data = new JsonObject(); data.addProperty("actor", actor.toString()); data.addProperty("token", token.toString());
    ClientPacketDistributor.sendToServer(new NpcNetwork.Request(-1, "journal_export", data.toString()));
  }
  static void receive(NpcNetwork.Response response) {
    if (token == null) return;
    try {
      var data = JsonParser.parseString(response.data()).getAsJsonObject();
      if (!token.toString().equals(data.get("token").getAsString())) return;
      activity = System.currentTimeMillis();
      switch (response.action()) {
        case "journal_export_start" -> {
          if (output != null) throw new IOException();
          expected = data.get("bytes").getAsLong(); expectedHash = data.get("sha256").getAsString();
          if (expected < 1 || expected > JournalArchive.MAX_BYTES || !expectedHash.matches("[a-f0-9]{64}")) throw new IOException();
          Path root = FMLPaths.GAMEDIR.get().resolve("exports/bep_3davater_companion").toAbsolutePath().normalize();
          Files.createDirectories(root);
          if (!root.toRealPath().startsWith(FMLPaths.GAMEDIR.get().toRealPath())) throw new IOException();
          String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
          destination = root.resolve("actions-" + stamp + "-" + token + ".zip");
          partial = Files.createTempFile(root, ".journal-", ".part");
          output = Files.newOutputStream(partial); digest = MessageDigest.getInstance("SHA-256");
        }
        case "journal_export_part" -> {
          if (output == null || data.get("index").getAsInt() != index++) throw new IOException();
          byte[] bytes = Base64.getDecoder().decode(data.get("data").getAsString());
          if (bytes.length > 32768 || received + bytes.length > expected) throw new IOException();
          output.write(bytes); digest.update(bytes); received += bytes.length;
          message = "転送中 " + received * 100 / expected + "%";
        }
        case "journal_export_done" -> {
          if (output == null || received != expected || !HexFormat.of().formatHex(digest.digest()).equals(expectedHash)) throw new IOException();
          output.close(); output = null; Files.move(partial, destination); partial = null;
          message = "保存しました: exports/bep_3davater_companion/" + destination.getFileName();
          var player = Minecraft.getInstance().player;
          if (player != null) player.sendSystemMessage(Component.literal(message));
          token = null;
        }
        case "journal_export_error" -> { reset(); message = data.get("message").getAsString(); }
        default -> {}
      }
    } catch (Exception error) { reset(); message = "行動ログを保存できません"; }
  }
  static void reset() {
    try { if (output != null) output.close(); } catch (IOException ignored) {}
    try { if (partial != null) Files.deleteIfExists(partial); } catch (IOException ignored) {}
    token = null; output = null; partial = destination = null; digest = null; message = "";
  }
  static void tick() {
    if (busy() && System.currentTimeMillis() - activity > 300_000) {
      reset(); message = "エクスポートがタイムアウトしました";
    }
  }
  private JournalExportClient() {}
}

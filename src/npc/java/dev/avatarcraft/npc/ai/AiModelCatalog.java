package dev.avatarcraft.npc.ai;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Model discovery only: no prompts, inference, API error bodies or credentials leave this service. */
public final class AiModelCatalog {
  private static final int MAX_BYTES = 2 << 20, MAX_MODELS = 2048, MAX_PAGES = 32;
  public record Model(String id, String label) {}

  public static CompletableFuture<List<Model>> fetch(CodexConfig draft, Path workspace) {
    var snapshot = draft.copy();
    return CompletableFuture.supplyAsync(() -> {
      try { return load(snapshot, workspace); }
      catch (Exception e) {
        throw new CompletionException(e instanceof CatalogException ? e
            : new CatalogException("モデル一覧を取得できません。接続先・認証・Codexのログイン状態を確認してください"));
      }
    });
  }

  static List<Model> load(CodexConfig draft, Path workspace) throws Exception {
    var config = draft.copy();
    if (config.model.isBlank()) config.model = "catalog-query";
    config.validate();
    var models = new LinkedHashMap<String, Model>();
    if (config.provider == CodexConfig.Provider.CODEX) {
      try (var bridge = new CodexBridge(config, workspace)) {
        bridge.initialize().get(config.requestTimeoutSeconds * 3L, TimeUnit.SECONDS);
        String cursor = null;
        var cursors = new HashSet<String>();
        for (int page = 0; page < MAX_PAGES; page++) {
          var response = bridge.models(cursor).get(config.requestTimeoutSeconds, TimeUnit.SECONDS);
          collect(response, models, true);
          cursor = string(response, "nextCursor", "");
          if (cursor.isEmpty()) return List.copyOf(models.values());
          if (!cursors.add(cursor)) throw new CatalogException("モデル一覧のページ情報が不正です");
        }
      }
    } else {
      try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(config.requestTimeoutSeconds))
          .followRedirects(HttpClient.Redirect.NEVER).build()) {
      String key = config.resolvedApiKey();
      if (key.chars().anyMatch(c -> c < 32 || c == 127)) throw new CatalogException("APIキーに使用できない文字が含まれています");
      var base = URI.create(config.baseUrl.replaceAll("/+$", "") + "/models");
      if (key.isBlank() && Set.of("api.openai.com", "api.anthropic.com").contains(base.getHost()))
        throw new CatalogException("APIキーを入力するか、環境変数を設定してください");
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.requestTimeoutSeconds);
      String cursor = "";
      var cursors = new HashSet<String>();
      for (int page = 0; page < MAX_PAGES; page++) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new CatalogException("モデル一覧の取得がタイムアウトしました");
        var uri = cursor.isEmpty() ? base : URI.create(base + "?after_id=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(remaining)).header("Accept", "application/json").GET();
        if (config.provider == CodexConfig.Provider.ANTHROPIC) {
          request.header("anthropic-version", "2023-06-01");
          if (!key.isBlank()) request.header("x-api-key", key);
        } else if (!key.isBlank()) request.header("Authorization", "Bearer " + key);
        var response = http.send(request.build(), ignored -> new BoundedBody());
        int status = response.statusCode();
        if (status < 200 || status >= 300) throw new CatalogException(switch (status) {
          case 401, 403 -> "認証できません。APIキー・環境変数・接続先を確認してください";
          case 404, 405 -> "モデル一覧に対応していない接続先です。モデルIDを直接入力できます";
          case 429 -> "モデル一覧の取得回数が制限されています。しばらく待って再試行してください";
          default -> "モデル一覧を取得できません（HTTP " + status + "）";
        });
        var json = JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject();
        collect(json, models, false);
        if (!json.has("has_more") || !json.get("has_more").getAsBoolean()) return List.copyOf(models.values());
        cursor = string(json, "last_id", "");
        if (cursor.isEmpty() || cursor.length() > 256 || !cursors.add(cursor))
          throw new CatalogException("モデル一覧のページ情報が不正です");
      }
      }
    }
    throw new CatalogException("モデル一覧が上限を超えました。モデルIDを直接入力できます");
  }

  private static void collect(JsonObject json, Map<String, Model> models, boolean codex) throws CatalogException {
    if (!json.has("data") || !json.get("data").isJsonArray())
      throw new CatalogException("接続先のモデル一覧を読み取れません。モデルIDを直接入力できます");
    for (var item : json.getAsJsonArray("data")) {
      if (!item.isJsonObject()) continue;
      var row = item.getAsJsonObject();
      if (row.has("hidden") && row.get("hidden").getAsBoolean()) continue;
      String id = codex ? string(row, "model", string(row, "id", "")) : string(row, "id", "");
      if (id.isBlank() || id.length() > 128 || id.chars().anyMatch(c -> c < 32 || c == 127)) continue;
      String label = string(row, codex ? "displayName" : "display_name", id);
      label = label.replaceAll("[\\p{Cntrl}§]", "");
      if (label.length() > 160) label = label.substring(0, 160);
      models.putIfAbsent(id, new Model(id, label));
      if (models.size() > MAX_MODELS) throw new CatalogException("モデル一覧が上限を超えました");
    }
  }
  private static String string(JsonObject value, String key, String fallback) {
    return value.has(key) && value.get(key).isJsonPrimitive() ? value.get(key).getAsString() : fallback;
  }
  public static final class CatalogException extends IOException {
    CatalogException(String message) { super(message); }
  }

  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;
    public CompletionStage<byte[]> getBody() { return body; }
    public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
    public void onNext(List<ByteBuffer> buffers) {
      for (var buffer : buffers) {
        if (bytes.size() + buffer.remaining() > MAX_BYTES) {
          subscription.cancel(); body.completeExceptionally(new CatalogException("モデル一覧の応答が大きすぎます")); return;
        }
        var part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
      }
      subscription.request(1);
    }
    public void onError(Throwable error) { body.completeExceptionally(new CatalogException("モデル一覧の通信が中断されました")); }
    public void onComplete() { body.complete(bytes.toByteArray()); }
  }
}

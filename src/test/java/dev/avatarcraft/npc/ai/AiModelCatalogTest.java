package dev.avatarcraft.npc.ai;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

@Timeout(15)
class AiModelCatalogTest {
  private CodexConfig config(HttpServer server, boolean anthropic) {
    var config = new CodexConfig();
    config.provider = anthropic ? CodexConfig.Provider.ANTHROPIC : CodexConfig.Provider.OPENAI;
    config.baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
    config.apiKey = "fake-catalog-key"; config.apiKeyEnvironment = ""; config.model = "";
    return config;
  }
  @Test void openAiCompatibleCatalogUsesGetAndAuthWithoutNeedingASelectedModel() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    var failure = new AtomicReference<Throwable>();
    server.createContext("/v1/models", exchange -> {
      try {
        assertEquals("GET", exchange.getRequestMethod());
        assertEquals("Bearer fake-catalog-key", exchange.getRequestHeaders().getFirst("Authorization"));
        assertEquals(0, exchange.getRequestBody().readAllBytes().length);
        byte[] response = "{\"data\":[{\"id\":\"local/tool-model\"},{\"id\":\"other-model\",\"display_name\":\"Other\"},{\"id\":\"local/tool-model\"}]}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
      } catch (Throwable e) { failure.set(e); } finally { exchange.close(); }
    }); server.start();
    try {
      var config = config(server, false);
      var models = AiModelCatalog.fetch(config, Path.of("unused")).get();
      assertNull(failure.get()); assertEquals(List.of("local/tool-model", "other-model"), models.stream().map(AiModelCatalog.Model::id).toList());
      assertEquals("Other", models.get(1).label()); assertEquals("", config.model);
    } finally { server.stop(0); }
  }
  @Test void anthropicHeadersAndPaginationStayOnTheChosenEndpoint() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    var pages = new AtomicInteger(); var failure = new AtomicReference<Throwable>();
    server.createContext("/v1/models", exchange -> {
      try {
        assertEquals("fake-catalog-key", exchange.getRequestHeaders().getFirst("x-api-key"));
        assertEquals("2023-06-01", exchange.getRequestHeaders().getFirst("anthropic-version"));
        assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
        int page = pages.incrementAndGet();
        if (page == 2) assertEquals("after_id=model-first", exchange.getRequestURI().getQuery());
        String json = page == 1 ? "{\"data\":[{\"id\":\"model-first\"}],\"has_more\":true,\"last_id\":\"model-first\"}"
            : "{\"data\":[{\"id\":\"model-second\"}],\"has_more\":false}";
        byte[] response = json.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
      } catch (Throwable e) { failure.set(e); } finally { exchange.close(); }
    }); server.start();
    try {
      var models = AiModelCatalog.fetch(config(server, true), Path.of("unused")).get();
      assertNull(failure.get()); assertEquals(2, pages.get()); assertEquals(2, models.size());
    } finally { server.stop(0); }
  }
  @Test void apiErrorBodiesAndSecretsAreNeverReturnedToTheScreen() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/v1/models", exchange -> {
      byte[] response = "fake-catalog-key: private server error details".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(401, response.length); exchange.getResponseBody().write(response); exchange.close();
    }); server.start();
    try {
      var error = assertThrows(ExecutionException.class, () -> AiModelCatalog.fetch(config(server, false), Path.of("unused")).get());
      assertTrue(error.getCause() instanceof AiModelCatalog.CatalogException);
      assertFalse(error.toString().contains("fake-catalog-key")); assertFalse(error.toString().contains("private server"));
      assertTrue(error.getCause().getMessage().contains("認証"));
    } finally { server.stop(0); }
  }
  @Test void redirectsCannotForwardTheEnteredApiKey() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0); var target = new AtomicInteger();
    server.createContext("/v1/models", exchange -> {
      exchange.getResponseHeaders().add("Location", "http://localhost:" + server.getAddress().getPort() + "/redirect-target");
      exchange.sendResponseHeaders(302, -1); exchange.close();
    });
    server.createContext("/redirect-target", exchange -> { target.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); }); server.start();
    try {
      assertThrows(ExecutionException.class, () -> AiModelCatalog.fetch(config(server, false), Path.of("unused")).get());
      assertEquals(0, target.get());
    } finally { server.stop(0); }
  }
  @Test void oversizedResponsesAreBoundedAndUnsupportedCatalogsKeepManualEntryAvailable() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0); var status = new AtomicInteger(404);
    server.createContext("/v1/models", exchange -> {
      try {
        byte[] response = status.get() == 404 ? new byte[0] : new byte[(2 << 20) + 1];
        exchange.sendResponseHeaders(status.get(), response.length);
        exchange.getResponseBody().write(response);
      } finally { exchange.close(); }
    }); server.start();
    try {
      var unavailable = assertThrows(ExecutionException.class, () -> AiModelCatalog.fetch(config(server, false), Path.of("unused")).get());
      assertTrue(unavailable.getCause().getMessage().contains("直接入力")); status.set(200);
      assertThrows(ExecutionException.class, () -> AiModelCatalog.fetch(config(server, false), Path.of("unused")).get());
    } finally { server.stop(0); }
  }
  @Test void switchingConnectionPresetsDoesNotReuseCredentialsOrModelIds() {
    var original = new CodexConfig(); original.provider = CodexConfig.Provider.OPENAI;
    original.apiKey = "fake-catalog-key"; original.apiKeyEnvironment = "A_SECRET_VARIABLE"; original.model = "old-model";
    original.complexModel = "old-planner"; original.maxToolCalls = 41;
    var local = AiConnectionMethod.LM_STUDIO.fresh(original);
    assertEquals("", local.apiKey); assertEquals("", local.apiKeyEnvironment); assertEquals("", local.model); assertEquals("", local.complexModel);
    assertEquals(41, local.maxToolCalls); assertEquals("fake-catalog-key", original.apiKey);
    assertEquals(AiConnectionMethod.LM_STUDIO, AiConnectionMethod.detect(local));
    assertEquals(AiConnectionMethod.OLLAMA, AiConnectionMethod.detect(AiConnectionMethod.OLLAMA.fresh(original)));
  }
}

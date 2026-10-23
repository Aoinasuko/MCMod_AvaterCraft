package dev.avatarcraft.npc.ai;

import net.neoforged.neoforge.common.ModConfigSpec;

/** LOCAL config stays on the server host, never sent as an NPC/client payload. */
public final class NpcAiSettings {
  public static final ModConfigSpec SPEC;
  private static final ModConfigSpec.BooleanValue ENABLED;
  private static final ModConfigSpec.EnumValue<CodexConfig.Provider> PROVIDER;
  private static final ModConfigSpec.ConfigValue<String> URL, KEY, KEY_ENV, MODEL, EXECUTABLE;
  private static final ModConfigSpec.IntValue REQUEST_TIMEOUT,
      TURN_TIMEOUT,
      CALLS,
      CONCURRENT,
      INTERVAL,
      TOKENS;

  static {
    var b = new ModConfigSpec.Builder();
    b.comment(
            "AI settings on this Minecraft/server host. Changes apply when reopening the world.",
            "このMinecraft／サーバーのAI設定。変更後はワールドを開き直してください。")
        .push("ai");
    ENABLED = b.comment("Enable AI / AIを有効にする").define("enabled", true);
    PROVIDER =
        b.comment(
                "CODEX: CLI OAuth; OPENAI: Chat Completions compatible, including LM Studio/Ollama;"
                    + " ANTHROPIC: Messages compatible.")
            .defineEnum("provider", CodexConfig.Provider.CODEX);
    MODEL =
        b.comment("API model ID. Empty uses the Codex default only. / APIの場合はモデルIDが必須")
            .define("model", "");
    URL =
        b.comment(
                "API base URL, including /v1. Examples: https://api.openai.com/v1,"
                    + " https://api.anthropic.com/v1, http://localhost:1234/v1,"
                    + " http://localhost:11434/v1")
            .define("baseUrl", "https://api.openai.com/v1");
    KEY_ENV =
        b.comment(
                "Environment variable containing the API key (takes precedence). Use"
                    + " ANTHROPIC_API_KEY for Anthropic. Empty is allowed for local servers.")
            .define("apiKeyEnvironment", "OPENAI_API_KEY");
    KEY =
        b.comment(
                "Optional direct API key. Stored in plaintext locally; prefer the environment"
                    + " variable. Never share this config with a real key.")
            .define("apiKey", "");
    EXECUTABLE =
        b.comment("Codex CLI executable (CODEX only)")
            .define("codexExecutable", new CodexConfig().executable);
    REQUEST_TIMEOUT = b.defineInRange("requestTimeoutSeconds", 30, 5, 120);
    TURN_TIMEOUT = b.defineInRange("turnTimeoutSeconds", 180, 30, 600);
    CALLS = b.defineInRange("maxToolCalls", 32, 1, 64);
    CONCURRENT = b.defineInRange("maxConcurrentTurns", 3, 2, 4);
    INTERVAL = b.defineInRange("minimumRequestIntervalSeconds", 3, 1, 60);
    TOKENS =
        b.comment("Maximum output tokens per HTTP response")
            .defineInRange("maxOutputTokens", 4096, 256, 32768);
    b.pop();
    SPEC = b.build();
  }

  public static CodexConfig snapshot() {
    var c = new CodexConfig();
    c.enabled = ENABLED.get() && !Boolean.getBoolean("avatarcraft.npcGameTest");
    c.provider = PROVIDER.get();
    c.baseUrl = URL.get();
    c.apiKey = KEY.get();
    c.apiKeyEnvironment = KEY_ENV.get();
    c.model = MODEL.get();
    c.executable = EXECUTABLE.get();
    c.requestTimeoutSeconds = REQUEST_TIMEOUT.get();
    c.turnTimeoutSeconds = TURN_TIMEOUT.get();
    c.maxToolCalls = CALLS.get();
    c.maxConcurrentTurns = CONCURRENT.get();
    c.minimumRequestIntervalSeconds = INTERVAL.get();
    c.maxOutputTokens = TOKENS.get();
    if (c.enabled) c.validate();
    return c;
  }

  private NpcAiSettings() {}
}

package dev.avatarcraft.npc.ai;

/** Runtime snapshot. API secrets stay on the host; OAuth credentials remain managed by Codex. */
public final class CodexConfig {
  public enum Provider {
    CODEX,
    OPENAI,
    ANTHROPIC
  }

  public Provider provider = Provider.CODEX;
  public String baseUrl = "https://api.openai.com/v1",
      apiKey = "",
      apiKeyEnvironment = "OPENAI_API_KEY";
  public int maxOutputTokens = 4096;
  public boolean enabled = true;
  public String executable =
      System.getProperty("os.name").startsWith("Windows") ? "codex.exe" : "codex";
  public String model = "";
  public int requestTimeoutSeconds = 30,
      turnTimeoutSeconds = 180,
      maxToolCalls = 32,
      maxConcurrentTurns = 3,
      minimumRequestIntervalSeconds = 3;

  public void validate() {
    if (provider == null
        || baseUrl == null
        || apiKey == null
        || apiKeyEnvironment == null
        || baseUrl.length() > 2048
        || apiKey.length() > 4096
        || !apiKeyEnvironment.matches("[A-Za-z_][A-Za-z0-9_]*|"))
      throw new IllegalArgumentException("Invalid AI connection settings");
    if (provider != Provider.CODEX) {
      var uri = java.net.URI.create(baseUrl);
      if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getFragment() != null
          || model == null
          || model.isBlank())
        throw new IllegalArgumentException("HTTP provider requires an API base URL and model");
      if (apiKey.indexOf('\r') >= 0 || apiKey.indexOf('\n') >= 0)
        throw new IllegalArgumentException("Invalid API key");
    }
    if (executable == null
        || executable.isBlank()
        || executable.length() > 4096
        || executable.indexOf('\n') >= 0)
      throw new IllegalArgumentException("Invalid Codex executable path");
    if (model == null
        || model.length() > 128
        || requestTimeoutSeconds < 5
        || requestTimeoutSeconds > 120
        || turnTimeoutSeconds < 30
        || turnTimeoutSeconds > 600
        || maxToolCalls < 1
        || maxToolCalls > 64
        || maxConcurrentTurns < 2
        || maxConcurrentTurns > 4
        || minimumRequestIntervalSeconds < 1
        || minimumRequestIntervalSeconds > 60
        || maxOutputTokens < 256
        || maxOutputTokens > 32768)
      throw new IllegalArgumentException("Invalid Codex runtime limits");
  }

  public String resolvedApiKey() {
    String value = apiKeyEnvironment.isBlank() ? null : System.getenv(apiKeyEnvironment);
    return value == null || value.isBlank() ? apiKey : value.strip();
  }
}

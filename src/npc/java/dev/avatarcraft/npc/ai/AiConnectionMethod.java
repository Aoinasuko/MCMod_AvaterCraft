package dev.avatarcraft.npc.ai;

/** GUI presets map to the existing wire protocols. Credentials are never copied between presets. */
public enum AiConnectionMethod {
  CODEX("Codex / ChatGPT OAuth", CodexConfig.Provider.CODEX, ""),
  OPENAI("OpenAI API", CodexConfig.Provider.OPENAI, "https://api.openai.com/v1"),
  ANTHROPIC("Anthropic API", CodexConfig.Provider.ANTHROPIC, "https://api.anthropic.com/v1"),
  LM_STUDIO("LM Studio", CodexConfig.Provider.OPENAI, "http://localhost:1234/v1"),
  OLLAMA("Ollama", CodexConfig.Provider.OPENAI, "http://localhost:11434/v1"),
  CUSTOM("OpenAI互換 API / URL指定", CodexConfig.Provider.OPENAI, "http://localhost:1234/v1");

  public final String label, url;
  public final CodexConfig.Provider provider;
  AiConnectionMethod(String label, CodexConfig.Provider provider, String url) {
    this.label = label; this.provider = provider; this.url = url;
  }
  public CodexConfig fresh(CodexConfig source) {
    var value = source.copy(); value.provider = provider; value.baseUrl = url;
    value.apiKey = ""; value.apiKeyEnvironment = ""; value.model = ""; value.complexModel = "";
    return value;
  }
  public static AiConnectionMethod detect(CodexConfig value) {
    if (value.provider == CodexConfig.Provider.CODEX) return CODEX;
    if (value.provider == CodexConfig.Provider.ANTHROPIC) return ANTHROPIC;
    String url = value.baseUrl.replaceAll("/+$", "");
    for (var method : new AiConnectionMethod[] {OPENAI, LM_STUDIO, OLLAMA}) if (url.equals(method.url)) return method;
    return CUSTOM;
  }
}

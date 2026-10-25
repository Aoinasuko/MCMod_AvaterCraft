package dev.avatarcraft.npc.client;

import dev.avatarcraft.client.*;
import dev.avatarcraft.npc.ai.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.*;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.fml.loading.FMLPaths;

/** Host-local connection editor. No config or key is sent as a multiplayer payload. */
public final class AiModelScreen extends SeriesScreen {
  public static final String TAB_ID = "bep_3davater_companion_ai";
  private CodexConfig draft = NpcAiSettings.editableSnapshot();
  private AiConnectionMethod method = AiConnectionMethod.detect(draft);
  private final Map<AiConnectionMethod, CodexConfig> drafts = new EnumMap<>(AiConnectionMethod.class);
  private final List<Button> modelButtons = new ArrayList<>();
  private String message = "保存した設定はワールドを開き直すと反映されます";
  private long requestVersion;
  private boolean fetching;
  private ControlList form;
  private double scroll;

  public AiModelScreen() { super(TAB_ID); }
  @Override protected void init() {
    if (form != null) scroll = form.scrollAmount();
    clearWidgets(); initTabs(); modelButtons.clear();
    form = addRenderableWidget(new ControlList(12, CONTENT_TOP + 16, width - 24,
        Math.max(30, height - CONTENT_TOP - 74)));
    form.control(control("接続方法: " + method.label, () -> {
      drafts.put(method, draft);
      method = AiConnectionMethod.values()[(method.ordinal() + 1) % AiConnectionMethod.values().length];
      draft = drafts.computeIfAbsent(method, key -> key.fresh(draft)); changed(); init();
    }));
    form.control(control(draft.enabled ? "AI: 有効" : "AI: 無効", () -> { draft.enabled = !draft.enabled; changed(); init(); }));
    if (method == AiConnectionMethod.CODEX) {
      form.field("Codex CLI", edit(draft.executable, 4096, value -> draft.executable = value, "codex.exe / codex"));
      form.heading("ChatGPT OAuth認証はホスト環境のCodexログインを使用します");
    } else {
      form.field("接続先URL", edit(draft.baseUrl, 2048, value -> draft.baseUrl = value, method.url));
      var key = edit(draft.apiKey, 4096, value -> draft.apiKey = value, "APIキー（ローカル保存）");
      key.addFormatter((text, offset) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
      form.field("APIキー", key);
      var environment = edit(draft.apiKeyEnvironment, 128, value -> draft.apiKeyEnvironment = value, "空欄で入力したキーを使用");
      environment.setTooltip(Tooltip.create(Component.literal("環境変数に値がある場合は、入力キーより優先して使用します")));
      form.field("環境変数名", environment);
      if (method == AiConnectionMethod.LM_STUDIO || method == AiConnectionMethod.OLLAMA)
        form.heading("ローカルサーバーで認証不要の場合、APIキーは空欄にできます");
    }
    form.field("通常モデルID", edit(draft.model, 128, value -> draft.model = value,
        method == AiConnectionMethod.CODEX ? "空欄でCodexの既定モデル" : "モデルIDを入力、または一覧から選択"));
    addModelButton(form, "通常モデルを一覧から選択", false);
    form.field("計画用モデルID", edit(draft.complexModel, 128, value -> draft.complexModel = value, "空欄で通常モデルと共通"));
    addModelButton(form, "複雑な計画用モデルを一覧から選択", true);
    form.heading("NPCには会話・ツール呼び出しに対応したモデルを選択してください");
    if (method != AiConnectionMethod.CODEX) form.heading("APIキーはこのPCの設定ファイルに保存されます");
    form.setScrollAmount(scroll);
    int bw = Math.min(150, (width - 36) / 2);
    button("設定を保存", width / 2 - bw - 4, height - 27, bw, this::save);
    button("キャンセル", width / 2 + 4, height - 27, bw, this::onClose);
  }

  private Button control(String label, Runnable action) {
    return Button.builder(Component.literal(label), b -> action.run()).bounds(0, 0, 200, 22).build();
  }
  private EditBox edit(String initial, int limit, Consumer<String> setter, String hint) {
    var field = new EditBox(font, 0, 0, 200, 20, Component.literal(hint));
    field.setMaxLength(limit); field.setValue(initial); field.setHint(Component.literal(hint));
    field.setResponder(value -> { setter.accept(value); changed(); }); return field;
  }
  private void changed() {
    requestVersion++; fetching = false;
    modelButtons.forEach(button -> button.active = true);
    message = "設定を変更しました（未保存）";
  }
  private void addModelButton(ControlList form, String text, boolean complex) {
    var button = control(fetching ? "モデル一覧を取得中…" : text, () -> fetchModels(complex));
    button.active = !fetching; modelButtons.add(button); form.control(button);
  }
  private void fetchModels(boolean complex) {
    long version = ++requestVersion;
    fetching = true; modelButtons.forEach(button -> button.active = false);
    message = "入力した接続先からモデル一覧を取得しています…";
    Path workspace = FMLPaths.CONFIGDIR.get().resolve("bep_3davater_companion/codex-workspace").toAbsolutePath();
    AiModelCatalog.fetch(draft, workspace).whenComplete((models, error) -> minecraft.execute(() -> {
      if (minecraft.gui.screen() != this || version != requestVersion) return;
      fetching = false; modelButtons.forEach(button -> button.active = true);
      if (error != null) {
        Throwable cause = error;
        while (cause.getCause() != null && !(cause instanceof AiModelCatalog.CatalogException)) cause = cause.getCause();
        message = cause instanceof AiModelCatalog.CatalogException ? cause.getMessage()
            : "モデル一覧を取得できません。接続先・認証を確認してください";
      } else if (models.isEmpty()) message = "モデルが見つかりません。モデルIDの直接入力もできます";
      else minecraft.gui.setScreen(new AiModelPickerScreen(this, models, complex, id -> {
        if (complex) draft.complexModel = id; else draft.model = id;
        changed(); message = "モデルを選択しました。保存すると設定に反映します";
      }));
    }));
  }
  private void save() {
    if (draft.enabled && draft.provider != CodexConfig.Provider.CODEX && draft.model.isBlank()) {
      message = "API接続では通常モデルIDを入力するか、一覧から選択してください"; return;
    }
    try {
      NpcAiSettings.saveConnection(draft);
      message = "保存しました。ワールドを開き直すと反映されます";
    } catch (RuntimeException e) { message = "保存できません。接続先URL・モデルID・入力内容を確認してください"; }
  }
  @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
    background(g);
    boolean remote = minecraft.getConnection() != null && !minecraft.hasSingleplayerServer();
    String target = remote ? "このPC用の設定です。接続中のサーバーのAI設定は変更されません" : "このPCで動くワールドのAI接続を設定します";
    g.text(font, font.plainSubstrByWidth(target, width - 24), 12, CONTENT_TOP, 0xffc1cde0);
    g.text(font, font.plainSubstrByWidth(message, width - 24), 12, height - 46, 0xffffdc9b);
    super.extractRenderState(g, mx, my, dt);
  }
  @Override public void removed() { if (form != null) scroll = form.scrollAmount(); }
}

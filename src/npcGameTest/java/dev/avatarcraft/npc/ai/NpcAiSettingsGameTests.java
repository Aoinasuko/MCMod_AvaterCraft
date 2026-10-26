package dev.avatarcraft.npc.ai;

import java.nio.file.Files;
import net.minecraft.gametest.framework.*;
import dev.avatarcraft.npc.bep_3davater_companion;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** Isolated host config persistence test. Never opens an external AI connection. */
public final class NpcAiSettingsGameTests {
  public static void register(RegisterGameTestsEvent event) {
    var env = event.registerEnvironment(bep_3davater_companion.id("ai_settings_qa"), new TestEnvironmentDefinition.AllOf());
    event.registerTest(bep_3davater_companion.id("ai_connection_save_and_validation"), new FunctionGameTestInstance(
        BuiltinTestFunctions.ALWAYS_PASS, new TestData<>(env, bep_3davater_companion.id("qa_empty"), 100, 1, true)) {
      public void run(GameTestHelper h) {
        var before = NpcAiSettings.editableSnapshot();
        var edited = AiConnectionMethod.LM_STUDIO.fresh(before);
        edited.enabled = true; edited.apiKey = "fake-settings-qa-key"; edited.model = "fake-settings-qa-model";
        edited.complexModel = "fake-settings-qa-planner";
        try {
          NpcAiSettings.saveConnection(edited);
          var saved = NpcAiSettings.editableSnapshot();
          h.assertTrue(saved.provider == CodexConfig.Provider.OPENAI && saved.model.equals(edited.model)
              && saved.complexModel.equals(edited.complexModel) && saved.baseUrl.equals(edited.baseUrl)
              && saved.apiKey.equals(edited.apiKey), "Connection fields save together");
          h.assertTrue(saved.maxToolCalls == before.maxToolCalls && saved.maxConcurrentTurns == before.maxConcurrentTurns,
              "Connection editor preserves advanced runtime limits");
          var invalid = edited.copy(); invalid.baseUrl = "file:///invalid";
          boolean rejected = false;
          try { NpcAiSettings.saveConnection(invalid); } catch (RuntimeException expected) { rejected = true; }
          h.assertTrue(rejected && NpcAiSettings.editableSnapshot().baseUrl.equals(edited.baseUrl),
              "Invalid edits do not replace the saved connection");
        } catch (RuntimeException error) { NpcAiSettings.saveConnection(before); throw error; }
        h.runAfterDelay(10, () -> {
          try {
            var file = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("bep_3davater_companion-ai.toml");
            String toml = Files.readString(file);
            h.assertTrue(toml.contains(edited.model) && toml.contains(edited.complexModel) && toml.contains(edited.apiKey),
                "Connection changes persist to the host config file");
            h.succeed();
          } catch (java.io.IOException e) { h.fail("Cannot read isolated AI config"); }
          finally { NpcAiSettings.saveConnection(before); }
        });
      }
    });
  }
}

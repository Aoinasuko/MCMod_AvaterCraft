package dev.avatarcraft;

import dev.avatarcraft.api.*;
import dev.avatarcraft.avatar.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** Verify Loader on a dedicated server without either optional integration. */
public final class LoaderGameTests {
  private static Identifier id(String path) {
    return Identifier.fromNamespaceAndPath(bep_3davater_loader.ID, path);
  }

  public static void register(RegisterGameTestsEvent event) {
    var environment = event.registerEnvironment(id("qa"), new TestEnvironmentDefinition.AllOf());
    event.registerTest(id("standalone_model_and_expression_keys"), new FunctionGameTestInstance(
        BuiltinTestFunctions.ALWAYS_PASS, new TestData<>(environment, id("qa_empty"), 40, 1, true)) {
      public void run(GameTestHelper h) {
        h.assertTrue(!net.neoforged.fml.ModList.get().isLoaded("bep_3davater_player"), "Player Model absent");
        h.assertTrue(!net.neoforged.fml.ModList.get().isLoaded("bep_3davater_companion"), "Companion absent");
        try {
          var directory = java.nio.file.Files.createTempDirectory("bep-loader-qa-");
          try {
            BundledAvatar.install(directory);
            var catalog = new AvatarCatalog(directory);
            catalog.reload();
            var archive = catalog.find("tortobu").orElseThrow().archive();
            h.assertTrue(archive.avatar().name.equals("tortobu"), "Bundled model loads");
            var actor = new AvatarAnimation(archive, 1);
            actor.expressionKeys.set("hurt");
            actor.update(new MotionSignals(), 0, new org.joml.Vector3f(), 0);
            for (var shape : archive.expressions().events.get("hurt").entrySet())
              h.assertTrue(actor.weights().get(shape.getKey()).equals(shape.getValue()), "Named key applies weights");
            h.succeed();
          } finally {
            java.nio.file.Files.deleteIfExists(directory.resolve("tortobu.avc"));
            java.nio.file.Files.deleteIfExists(directory);
          }
        } catch (Exception e) {
          h.fail(e.toString());
        }
      }
    });
  }
}

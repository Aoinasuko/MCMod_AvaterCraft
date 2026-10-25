import com.google.gson.*;
import dev.avatarcraft.npc.ai.TaskPlan;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;

/** Checks every generated expected plan with the actual runtime contract, without a live AI. */
public class NpcCurriculumCheck {
  public static void main(String[] args) throws Exception {
    int count = 0, ambiguous = 0;
    for (String split : new String[] {"train", "validation", "test"}) {
      try (var in =
          new BufferedReader(
              new InputStreamReader(
                  new GZIPInputStream(Files.newInputStream(Path.of(args[0], split + ".jsonl.gz"))),
                  StandardCharsets.UTF_8))) {
        String line;
        while ((line = in.readLine()) != null) {
          var e = JsonParser.parseString(line).getAsJsonObject();
          TaskPlan.parse(e.getAsJsonObject("expectedPlan").toString());
          if (e.get("ambiguous").getAsBoolean()) ambiguous++;
          count++;
        }
      }
    }
    System.out.println(
        "{\"validatedPlans\":" + count + ",\"ambiguousExamples\":" + ambiguous + "}");
  }
}

package dev.avatarcraft.avatar;

import java.util.*;

/** Group morphs by the bones influencing their changed vertices, even on merged body meshes. */
public final class ExpressionShapes {
  public record Shape(String key, String mesh, String name, boolean head) {}

  public static List<Shape> collect(Avatar avatar) {
    var shapes = new ArrayList<Shape>();
    Integer head = avatar.humanoid.get("Head");
    for (var mesh : avatar.meshes) {
      if (mesh.geometry == null) continue;
      for (var morph : mesh.geometry.morphs()) {
        boolean onHead = head != null && headInfluence(avatar, mesh, morph, head);
        if (head == null) onHead = (mesh.name + " " + morph.name()).toLowerCase(Locale.ROOT)
            .matches(".*(head|face|eye|blink|mouth|vrc\\.v_|顔|目|口).*");
        shapes.add(new Shape(mesh.name + ":" + morph.name(), mesh.name, morph.name(), onHead));
      }
    }
    shapes.sort(Comparator.comparing(Shape::head).reversed().thenComparing(Shape::mesh).thenComparing(Shape::name));
    return List.copyOf(shapes);
  }

  private static boolean headInfluence(Avatar avatar, Avatar.Mesh mesh, Avatar.Morph morph, int head) {
    double total = 0, headTotal = 0;
    for (var frame : morph.frames()) {
      for (int i = 0; i < frame.indices().length; i++) {
        double change = 0;
        for (int k = 0; k < 6; k++) change += Math.abs(frame.deltas()[i * 6 + k]);
        if (change < 1e-8) continue;
        int offset = frame.indices()[i] * 16;
        var vertices = mesh.geometry.vertices();
        double influence = 0;
        for (int k = 0; k < 4; k++) {
          if (vertices[offset + 12 + k] == 0) continue;
          int bone = mesh.bones[(int) vertices[offset + 8 + k]];
          for (int b = bone; b >= 0; b = avatar.bones[b].parent)
            if (b == head) { influence += vertices[offset + 12 + k]; break; }
        }
        total += change;
        headTotal += change * influence;
      }
    }
    return total > 0 && headTotal >= total * .5;
  }
}

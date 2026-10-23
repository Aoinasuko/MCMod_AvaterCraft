package dev.avatarcraft.avatar;

import com.google.gson.JsonObject;
import java.util.*;

/** Data only. Coordinates and bind matrices use Unity's original meter space. */
public final class Avatar {
  public int formatVersion;
  public int physicsVersion;
  public String id, name;
  public float height = 1.8f, feetOffset = 0;
  public Bone[] bones;
  public Map<String, Integer> humanoid = new HashMap<>();
  public Mesh[] meshes;
  public Material[] materials;
  public Spring[] springs = {};
  public Collider[] colliders = {};
  public Map<String, Float> defaults = new HashMap<>();
  public String[] warnings = {};
  public transient MotionPack motions;

  public static final class Bone {
    public String name;
    public int parent;
    public float[] position, rotation, scale;
  }

  public static final class Mesh {
    public String name, data;
    public int[] bones;
    public float[][] bindPoses;
    public Submesh[] submeshes;
    public transient MeshData geometry;
  }

  public static final class Submesh {
    public int material;
  }

  public static final class Material {
    public String name, shader, renderType;
    public int renderQueue;
    public JsonObject properties = new JsonObject();

    public float number(String key, float fallback) {
      var p = properties.get(key);
      return p != null && p.isJsonPrimitive() && p.getAsJsonPrimitive().isNumber()
          ? p.getAsFloat()
          : fallback;
    }

    public float[] color(String key, float... fallback) {
      var p = properties.get(key);
      if (p == null || !p.isJsonArray() || p.getAsJsonArray().size() != 4) return fallback;
      var a = p.getAsJsonArray();
      return new float[] {
        a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat(), a.get(3).getAsFloat()
      };
    }

    public String texture(String key) {
      var p = properties.get(key);
      if (p == null || !p.isJsonObject()) return null;
      var t = p.getAsJsonObject().get("texture");
      return t == null || t.isJsonNull() ? null : t.getAsString();
    }

    public float[] uv(String key, String field, float a, float b) {
      var p = properties.get(key);
      if (p == null || !p.isJsonObject() || !p.getAsJsonObject().has(field))
        return new float[] {a, b};
      var v = p.getAsJsonObject().getAsJsonArray(field);
      return new float[] {v.get(0).getAsFloat(), v.get(1).getAsFloat()};
    }
  }

  public static final class Spring {
    public String name = "",
        version = "Version_1_1",
        integrationType = "Simplified",
        immobileType = "AllMotion",
        multiChildType = "Ignore",
        limitType = "None",
        parameter = "";
    public String allowCollision = "True", allowGrabbing = "False", allowPosing = "False";
    public boolean enabled = true,
        active = true,
        ignoreOtherPhysBones = true,
        isAnimated,
        resetWhenDisabled;
    public int root;
    public int[] bones, colliders;
    public float pull, spring, stiffness, gravity, immobile, radius;
    public float[] endpoint;
    public float gravityFalloff,
        maxAngleX = 45,
        maxAngleZ = 45,
        maxStretch,
        maxSquish,
        stretchMotion;
    public float[] limitRotation = {0, 0, 0}, staticFreezeAxis = {0, 0, 0};
    public SpringNode[] nodes = {};
    public JsonObject curves = new JsonObject(), settings = new JsonObject();
  }

  public static final class SpringNode {
    public int bone, depth;
    public float normalizedDepth;
    public boolean simulate = true;
    public float[] tail = {0, 0, 0}, limitRotation = {0, 0, 0, 1};
    public float pull, spring, stiffness, gravity, gravityFalloff, immobile, radius;
    public float maxAngleX = 45, maxAngleZ = 45, maxStretch, maxSquish, stretchMotion;
  }

  public static final class Collider {
    public int bone;
    public float[] position, rotation;
    public float radius, height;
    public boolean inside;
    public String shape;
    public boolean enabled = true, active = true, bonesAsSpheres;
    public String globalCollision = "False";
    public JsonObject settings = new JsonObject();
  }

  public record MeshData(float[] vertices, int[][] indices, List<Morph> morphs) {
    public int vertexCount() {
      return vertices.length / 16;
    }
  }

  public record Morph(String name, List<MorphFrame> frames) {}

  public record MorphFrame(float weight, int[] indices, float[] deltas) {}
}

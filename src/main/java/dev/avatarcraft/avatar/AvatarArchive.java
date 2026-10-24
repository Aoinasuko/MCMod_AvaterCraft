package dev.avatarcraft.avatar;

import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Bounded archive reader: never extracts user controlled paths to the filesystem. */
public record AvatarArchive(
    Avatar avatar, Map<String, byte[]> images, ExpressionConfig expressions) {
  public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  public static final long MAX_ARCHIVE = 256L << 20, MAX_EXPANDED = 512L << 20;

  public static AvatarArchive read(Path path) throws IOException {
    if (Files.size(path) > MAX_ARCHIVE) throw new IOException("Avatar file exceeds 256 MiB");
    var entries = new HashMap<String, byte[]>();
    long total = 0;
    try (var zip = new ZipFile(path.toFile())) {
      var it = zip.entries();
      int count = 0;
      while (it.hasMoreElements()) {
        var e = it.nextElement();
        if (++count > 4096) throw new IOException("Too many archive entries");
        String n = e.getName();
        if (n.startsWith("/")
            || n.contains("..")
            || n.contains("\\")
            || n.contains(":")
            || entries.containsKey(n)) throw new IOException("Unsafe/duplicate archive path: " + n);
        if (e.isDirectory()) continue;
        int limit = n.endsWith(".json") ? 16 << 20 : 128 << 20;
        if (e.getSize() > limit) throw new IOException("Entry too large: " + n);
        byte[] bytes;
        try (var in = zip.getInputStream(e)) {
          bytes = in.readNBytes(limit + 1);
        }
        if (bytes.length > limit || (total += bytes.length) > MAX_EXPANDED)
          throw new IOException("Expanded avatar exceeds limit");
        entries.put(n, bytes);
      }
    }
    return fromEntries(entries);
  }

  /** Remote data is validated entirely in memory; no decrypted .avc is written to disk. */
  public static AvatarArchive read(byte[] data, long maxExpanded) throws IOException {
    if (data.length > MAX_ARCHIVE || maxExpanded < 1 || maxExpanded > MAX_EXPANDED)
      throw new IOException("Avatar size limit");
    var entries = new HashMap<String, byte[]>();
    long total = 0;
    int count = 0;
    try (var zip = new ZipInputStream(new ByteArrayInputStream(data))) {
      for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
        if (++count > 4096) throw new IOException("Too many archive entries");
        String n = e.getName();
        if (n.startsWith("/")
            || n.contains("..")
            || n.contains("\\")
            || n.contains(":")
            || entries.containsKey(n)) throw new IOException("Unsafe/duplicate archive path");
        if (e.isDirectory()) continue;
        int limit = n.endsWith(".json") ? 16 << 20 : 128 << 20;
        limit = (int) Math.min(limit, maxExpanded - total);
        byte[] bytes = zip.readNBytes(limit + 1);
        if (bytes.length > limit || (total += bytes.length) > maxExpanded)
          throw new IOException("Expanded avatar exceeds multiplayer limit");
        entries.put(n, bytes);
      }
    }
    return fromEntries(entries);
  }

  private static AvatarArchive fromEntries(Map<String, byte[]> entries) throws IOException {
    try {
      var avatar =
          GSON.fromJson(
              new String(required(entries, "avatar.json"), StandardCharsets.UTF_8), Avatar.class);
      validate(avatar);
      long vertices = 0, morphEntries = 0;
      for (var mesh : avatar.meshes) {
        mesh.geometry = mesh(required(entries, mesh.data));
        if ((vertices += mesh.geometry.vertexCount()) > 500_000)
          throw new IOException("Avatar exceeds 500000 vertices");
        if (mesh.submeshes.length != mesh.geometry.indices().length)
          throw new IOException("Submesh count mismatch");
        for (int v = 0; v < mesh.geometry.vertexCount(); v++) {
          float sum = 0;
          for (int k = 0; k < 4; k++) {
            int b = (int) mesh.geometry.vertices()[v * 16 + 8 + k];
            float w = mesh.geometry.vertices()[v * 16 + 12 + k];
            if (b < 0 || b >= mesh.bones.length || w < 0 || w > 1)
              throw new IOException("Invalid bone influence");
            sum += w;
          }
          if (Math.abs(sum - 1) > .02f) throw new IOException("Bone weights must sum to one");
        }
        for (var m : mesh.geometry.morphs())
          for (var f : m.frames())
            if ((morphEntries += f.indices().length) > 10_000_000)
              throw new IOException("Too many morph deltas");
      }
      var images = new HashMap<String, byte[]>();
      for (var e : entries.entrySet())
        if (e.getKey().endsWith(".png")) {
          validatePng(e.getValue());
          images.put(e.getKey(), e.getValue());
        }
      for (var mat : avatar.materials)
        for (var p : mat.properties.entrySet())
          if (p.getValue().isJsonObject()) {
            var t = p.getValue().getAsJsonObject().get("texture");
            if (t != null && !t.isJsonNull() && !images.containsKey(t.getAsString()))
              throw new IOException("Missing texture " + t.getAsString());
          }
      var expression =
          entries.containsKey("expressions.json")
              ? GSON.fromJson(
                  new String(entries.get("expressions.json"), StandardCharsets.UTF_8),
                  ExpressionConfig.class)
              : new ExpressionConfig();
      expression.validate(avatar);
      if (entries.containsKey("motions.json")) {
        avatar.motions =
            GSON.fromJson(
                new String(entries.get("motions.json"), StandardCharsets.UTF_8), MotionPack.class);
        if (avatar.motions == null) throw new IOException("Missing motion pack data");
        avatar.motions.validate(avatar);
      }
      return new AvatarArchive(avatar, Collections.unmodifiableMap(images), expression);
    } catch (RuntimeException e) {
      throw new IOException("Invalid avatar: " + e.getMessage(), e);
    }
  }

  private static byte[] required(Map<String, byte[]> entries, String name) throws IOException {
    var value = entries.get(name);
    if (value == null) throw new IOException("Missing " + name);
    return value;
  }

  private static void vector(float[] a, int length) throws IOException {
    if (a == null || a.length != length) throw new IOException("Invalid vector");
    for (float f : a) if (!Float.isFinite(f)) throw new IOException("Non-finite number");
  }

  private static void index(int i, int limit) throws IOException {
    if (i < 0 || i >= limit) throw new IOException("Invalid index " + i);
  }

  public static void validate(Avatar a) throws IOException {
    if (a == null || a.formatVersion != 1)
      throw new IOException("Unsupported avatar format version");
    if (a.id == null || !a.id.matches("[a-z0-9_-]{1,64}") || a.name == null)
      throw new IOException("Invalid avatar identity");
    if (!Float.isFinite(a.height)
        || a.height < .1f
        || a.height > 20
        || !Float.isFinite(a.feetOffset)) throw new IOException("Invalid avatar bounds");
    if (a.bones == null
        || a.bones.length == 0
        || a.bones.length > 4096
        || a.meshes == null
        || a.meshes.length > 256
        || a.materials == null
        || a.materials.length > 512) throw new IOException("Invalid avatar counts");
    for (int i = 0; i < a.bones.length; i++) {
      var b = a.bones[i];
      if (b.parent < -1 || b.parent >= i)
        throw new IOException("Bones must be in parent-first order");
      vector(b.position, 3);
      vector(b.rotation, 4);
      vector(b.scale, 3);
      float q = 0;
      for (float f : b.rotation) q += f * f;
      if (q < .9f || q > 1.1f) throw new IOException("Invalid bone quaternion");
      for (float s : b.scale) if (Math.abs(s) < 1e-8) throw new IOException("Zero bone scale");
    }
    for (int i : a.humanoid.values()) index(i, a.bones.length);
    var meshNames = new HashSet<String>();
    for (var m : a.meshes) {
      if (m.name == null
          || !meshNames.add(m.name)
          || m.bones == null
          || m.bones.length == 0
          || m.bones.length != m.bindPoses.length) throw new IOException("Invalid mesh");
      for (int b : m.bones) index(b, a.bones.length);
      for (float[] b : m.bindPoses) vector(b, 16);
      for (var sub : m.submeshes) if (sub.material != -1) index(sub.material, a.materials.length);
    }
    for (var c : a.colliders) {
      index(c.bone, a.bones.length);
      vector(c.position, 3);
      vector(c.rotation, 4);
      if (!Float.isFinite(c.radius) || c.radius < 0 || !Float.isFinite(c.height) || c.height < 0)
        throw new IOException("Invalid collider");
    }
    for (var s : a.springs) {
      index(s.root, a.bones.length);
      for (int b : s.bones) index(b, a.bones.length);
      for (int c : s.colliders) index(c, a.colliders.length);
      vector(s.endpoint, 3);
      vector(s.limitRotation, 3);
      vector(s.staticFreezeAxis, 3);
      if (!Set.of("None", "Angle", "Hinge", "Polar").contains(s.limitType)
          || !Set.of("Ignore", "First", "Average").contains(s.multiChildType)
          || !Set.of("Simplified", "Advanced").contains(s.integrationType)
          || !Set.of("AllMotion", "World").contains(s.immobileType))
        throw new IOException("Unknown PhysBone mode");
      if (s.nodes == null || s.nodes.length > a.bones.length)
        throw new IOException("Invalid PhysBone node count");
      var nodeIds = new HashSet<Integer>();
      for (var n : s.nodes) {
        index(n.bone, a.bones.length);
        if (!nodeIds.add(n.bone)) throw new IOException("Duplicate PhysBone node");
        vector(n.tail, 3);
        vector(n.limitRotation, 4);
        for (float v :
            new float[] {
              n.pull,
              n.spring,
              n.stiffness,
              n.gravity,
              n.gravityFalloff,
              n.immobile,
              n.radius,
              n.maxAngleX,
              n.maxAngleZ,
              n.maxStretch,
              n.maxSquish,
              n.stretchMotion,
              n.normalizedDepth
            }) if (!Float.isFinite(v)) throw new IOException("Non-finite PhysBone setting");
        if (n.maxAngleX < 0
            || n.maxAngleX > 180
            || n.maxAngleZ < 0
            || n.maxAngleZ > 180
            || n.maxStretch < 0
            || n.maxStretch > 1000
            || n.maxSquish < 0
            || n.maxSquish > 1
            || n.radius < 0) throw new IOException("Invalid PhysBone constraint");
        float q = 0;
        for (float v : n.limitRotation) q += v * v;
        if (q < .9f || q > 1.1f) throw new IOException("Invalid PhysBone limit rotation");
      }
      for (float v : new float[] {s.pull, s.spring, s.stiffness, s.gravity, s.immobile, s.radius})
        if (!Float.isFinite(v)) throw new IOException("Invalid spring");
    }
    if (a.physicsVersion != 0 && a.physicsVersion != 2)
      throw new IOException("Unsupported physics version");
    for (float v : a.defaults.values())
      if (!Float.isFinite(v) || v < 0 || v > 100)
        throw new IOException("Invalid default shape weight");
  }

  private static void validatePng(byte[] b) throws IOException {
    if (b.length < 24 || ByteBuffer.wrap(b).getLong() != 0x89504e470d0a1a0aL)
      throw new IOException("Invalid PNG");
    int w = ByteBuffer.wrap(b).getInt(16), h = ByteBuffer.wrap(b).getInt(20);
    if (w < 1 || h < 1 || w > 4096 || h > 4096) throw new IOException("Texture exceeds 4096x4096");
  }

  static Avatar.MeshData mesh(byte[] bytes) throws IOException {
    var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    try {
      if (b.getInt() != 0x31435641) throw new IOException("Wrong mesh magic");
      int n = count(b, 500_000);
      var v = new float[Math.multiplyExact(n, 16)];
      for (int i = 0; i < n; i++) {
        for (int k = 0; k < 8; k++) v[i * 16 + k] = finite(b);
        for (int k = 8; k < 12; k++) v[i * 16 + k] = b.getInt();
        for (int k = 12; k < 16; k++) v[i * 16 + k] = finite(b);
      }
      int subs = count(b, 512);
      int[][] indices = new int[subs][];
      for (int s = 0; s < subs; s++) {
        int c = count(b, 3_000_000);
        if (c % 3 != 0 || c > b.remaining() / 4) throw new IOException("Invalid triangle count");
        indices[s] = new int[c];
        for (int i = 0; i < c; i++) {
          int ix = b.getInt();
          index(ix, n);
          indices[s][i] = ix;
        }
      }
      int morphCount = count(b, 2048);
      var morphs = new ArrayList<Avatar.Morph>();
      var names = new HashSet<String>();
      long total = 0;
      for (int s = 0; s < morphCount; s++) {
        int len = count(b, 4096);
        byte[] name = new byte[len];
        b.get(name);
        String key = new String(name, StandardCharsets.UTF_8);
        if (!names.add(key)) throw new IOException("Duplicate shape");
        int frames = count(b, 64);
        var fs = new ArrayList<Avatar.MorphFrame>();
        float last = 0;
        for (int f = 0; f < frames; f++) {
          float weight = finite(b);
          if (weight <= last)
            throw new IOException("Morph frames must have increasing positive weights");
          last = weight;
          int c = count(b, n);
          if ((total += c) > 10_000_000 || c > b.remaining() / 28)
            throw new IOException("Morph limit exceeded");
          int[] ids = new int[c];
          float[] ds = new float[c * 6];
          for (int i = 0; i < c; i++) {
            ids[i] = b.getInt();
            index(ids[i], n);
            for (int k = 0; k < 6; k++) ds[i * 6 + k] = finite(b);
          }
          fs.add(new Avatar.MorphFrame(weight, ids, ds));
        }
        morphs.add(new Avatar.Morph(key, List.copyOf(fs)));
      }
      if (b.hasRemaining()) throw new IOException("Unexpected mesh bytes");
      return new Avatar.MeshData(v, indices, List.copyOf(morphs));
    } catch (BufferUnderflowException | ArithmeticException e) {
      throw new IOException("Truncated/oversized mesh", e);
    }
  }

  private static int count(ByteBuffer b, int max) throws IOException {
    int n = b.getInt();
    if (n < 0 || n > max) throw new IOException("Invalid count " + n);
    return n;
  }

  private static float finite(ByteBuffer b) throws IOException {
    float f = b.getFloat();
    if (!Float.isFinite(f)) throw new IOException("Non-finite mesh value");
    return f;
  }
}

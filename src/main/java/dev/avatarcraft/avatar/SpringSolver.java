package dev.avatarcraft.avatar;

import java.util.*;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Fixed-step constrained joints. Never rotate a shared parent once for each child. */
public final class SpringSolver {
  private static final double STEP = 1.0 / 60;
  private final Avatar avatar;
  private final List<Joint> joints = new ArrayList<>();
  private final Map<Avatar.Spring, Status> statuses = new LinkedHashMap<>();
  private final Vector3f previousMovement = new Vector3f();
  private final Matrix4f previousRootFrame = new Matrix4f();
  private double lastTime = Double.NaN, accumulator;
  private float previousYaw;

  public record Status(
      float angle, float stretch, float squish, boolean isGrabbed, boolean isPosed) {}

  private static final class Joint {
    final Avatar.Spring spring;
    final Avatar.SpringNode node;
    final Vector3f position = new Vector3f(), previous = new Vector3f();
    final Matrix4f previousBoneFrame = new Matrix4f();
    final int[] descendants, children, colliders;
    boolean initialized;

    Joint(
        Avatar.Spring s, Avatar.SpringNode n, int[] descendants, int[] children, int[] colliders) {
      spring = s;
      node = n;
      this.descendants = descendants;
      this.children = children;
      this.colliders = colliders;
    }
  }

  public SpringSolver(Avatar avatar) {
    this.avatar = avatar;
    Set<Integer> owned = new HashSet<>();
    var ordered =
        Arrays.stream(avatar.springs).sorted(Comparator.comparingInt(s -> -s.root)).toList();
    for (var s : ordered) {
      statuses.put(s, new Status(0, 0, 0, false, false));
      if (!s.enabled || !s.active) continue;
      Avatar.SpringNode[] nodes = s.nodes.length == 0 ? legacyNodes(s) : s.nodes;
      for (var node : nodes) {
        if (!node.simulate || !owned.add(node.bone) || vec(node.tail).lengthSquared() < 1e-12f)
          continue;
        int[] descendants =
            java.util.stream.IntStream.range(node.bone + 1, avatar.bones.length)
                .filter(i -> descendant(i, node.bone))
                .toArray();
        int[] children =
            java.util.stream.IntStream.range(node.bone + 1, avatar.bones.length)
                .filter(i -> avatar.bones[i].parent == node.bone)
                .toArray();
        var colliders = new LinkedHashSet<Integer>();
        for (int c : s.colliders) colliders.add(c);
        boolean globals =
            "True".equals(s.allowCollision)
                || "Other".equals(s.allowCollision) && permitsSelf(s.settings, "collisionFilter");
        if (globals)
          for (int i = 0; i < avatar.colliders.length; i++) {
            var c = avatar.colliders[i];
            if ("True".equals(c.globalCollision)
                || "Other".equals(c.globalCollision)
                    && permitsSelf(c.settings, "globalCollisionFilter")) colliders.add(i);
          }
        joints.add(
            new Joint(
                s, node, descendants, children, colliders.stream().mapToInt(i -> i).toArray()));
      }
    }
    joints.sort(Comparator.comparingInt(j -> j.node.bone));
  }

  public Map<String, Status> statuses() {
    var result = new LinkedHashMap<String, Status>();
    for (var e : statuses.entrySet())
      result.put(
          e.getKey().parameter.isBlank() ? e.getKey().name : e.getKey().parameter, e.getValue());
    return Collections.unmodifiableMap(result);
  }

  public int jointCount() {
    return joints.size();
  }

  public void update(Matrix4f[] world, double now, Vector3f movement) {
    update(world, now, movement, 0);
  }

  public void update(Matrix4f[] world, double now, Vector3f movement, float bodyYaw) {
    double dt = Double.isFinite(lastTime) ? now - lastTime : 0;
    boolean first = !Double.isFinite(lastTime);
    lastTime = now;
    float yawChange = (bodyYaw - previousYaw + 540) % 360 - 180;
    boolean reset =
        first
            || dt < 0
            || dt > .25
            || movement.distanceSquared(previousMovement) > 16
            || Math.abs(yawChange) > 100;
    float modelScale = 1.8f / avatar.height;
    Matrix4f rootFrame =
        new Matrix4f()
            .rotationY((float) -Math.toRadians(bodyYaw))
            .scale(-modelScale, modelScale, modelScale);
    Matrix4f rootMotion = new Matrix4f(rootFrame).invert().mul(previousRootFrame);
    Vector3f translation =
        new Matrix4f(rootFrame)
            .invert()
            .transformDirection(new Vector3f(previousMovement).sub(movement));
    previousMovement.set(movement);
    previousRootFrame.set(rootFrame);
    previousYaw = bodyYaw;
    if (reset) {
      accumulator = 0;
      for (var j : joints) j.initialized = false;
    } else {
      accumulator += Math.min(Math.max(dt, 0), .1);
      for (var j : joints)
        if (j.initialized) {
          float inertia = 1 - Math.clamp(j.node.immobile, 0, 1);
          var moved = rootMotion.transformPosition(new Vector3f(j.position)).add(translation);
          var old = rootMotion.transformPosition(new Vector3f(j.previous)).add(translation);
          j.position.lerp(moved, inertia);
          j.previous.lerp(old, inertia);
        }
    }
    int steps = (int) ((accumulator + 1e-9) / STEP);
    accumulator = Math.max(0, accumulator - steps * STEP);
    Matrix4f[] animated = Arrays.stream(world).map(Matrix4f::new).toArray(Matrix4f[]::new);
    for (var s : statuses.keySet()) statuses.put(s, new Status(0, 0, 0, false, false));
    for (int step = 0; step < Math.max(1, steps); step++) {
      for (int i = 0; i < world.length; i++) world[i].set(animated[i]);
      for (var j : joints) solve(j, world, steps > 0, step == 0);
    }
  }

  private void solve(Joint j, Matrix4f[] world, boolean integrate, boolean firstStep) {
    var n = j.node;
    var s = j.spring;
    Matrix4f frame = new Matrix4f(world[n.bone]);
    Vector3f anchor = frame.getTranslation(new Vector3f());
    Vector3f restTail = frame.transformPosition(vec(n.tail));
    float length = anchor.distance(restTail);
    if (length < 1e-7f) return;
    Quaternionf alignment =
        new Quaternionf().rotationTo(new Vector3f(0, 1, 0), vec(n.tail).normalize());
    Quaternionf basis =
        frame
            .getUnnormalizedRotation(new Quaternionf())
            .normalize()
            .mul(alignment)
            .mul(
                new Quaternionf(
                    n.limitRotation[0], n.limitRotation[1], n.limitRotation[2], n.limitRotation[3]))
            .normalize();
    if (!j.initialized) {
      j.position.set(restTail);
      j.previous.set(restTail);
      j.previousBoneFrame.set(frame);
      j.initialized = true;
    } else if (firstStep && "AllMotion".equals(s.immobileType) && n.immobile > 0) {
      Matrix4f follow = new Matrix4f(frame).mul(new Matrix4f(j.previousBoneFrame).invert());
      float factor = Math.clamp(n.immobile, 0, 1);
      j.position.lerp(follow.transformPosition(new Vector3f(j.position)), factor);
      j.previous.lerp(follow.transformPosition(new Vector3f(j.previous)), factor);
    }
    if (integrate) {
      var old = new Vector3f(j.position);
      float wobble = Math.clamp(n.spring, 0, 1);
      float retention =
          "Advanced".equals(s.integrationType) ? .45f + wobble * .53f : .3f + wobble * .65f;
      j.position.add(new Vector3f(j.position).sub(j.previous).mul(retention));
      float pull = Math.clamp(n.pull, 0, 1);
      float restoring = (float) (Math.pow(8 + 24 * pull, 2) * STEP * STEP);
      j.position.lerp(restTail, Math.min(.8f, restoring));
      if ("Advanced".equals(s.integrationType))
        j.position.lerp(restTail, Math.clamp(n.stiffness, 0, 1) * .12f);
      var restDirection = new Vector3f(restTail).sub(anchor).normalize();
      var direction = new Vector3f(j.position).sub(anchor);
      float alignmentToRest =
          direction.lengthSquared() < 1e-12f
              ? 1
              : Math.max(0, direction.normalize().dot(restDirection));
      float falloff = 1 - Math.clamp(n.gravityFalloff, 0, 1) * alignmentToRest;
      float gravity = n.gravity * falloff * length * (float) (9.81 * STEP * STEP);
      if (s.version.contains("1_1")) gravity *= Math.max(.05f, pull);
      j.position.y -= gravity;
      float simulatedLength = anchor.distance(j.position);
      float desiredLength = length + (simulatedLength - length) * Math.clamp(n.stretchMotion, 0, 1);
      if (simulatedLength > 1e-8f)
        j.position.sub(anchor).mul(desiredLength / simulatedLength).add(anchor);
      j.previous.set(old);
    }
    PhysicsLimits.tail(j.position, anchor, restTail, basis, n, s.limitType, s.staticFreezeAxis);
    for (int iteration = 0; iteration < 3; iteration++) {
      for (int ci : j.colliders) collide(j.position, anchor, avatar.colliders[ci], world, n.radius);
      PhysicsLimits.tail(j.position, anchor, restTail, basis, n, s.limitType, s.staticFreezeAxis);
    }
    // Limit unresolved velocity at the boundary instead of accumulating an explosive impulse.
    Vector3f velocity = new Vector3f(j.position).sub(j.previous);
    float maxVelocity = length * .2f;
    if (velocity.lengthSquared() > maxVelocity * maxVelocity)
      j.previous.set(j.position).sub(velocity.normalize(maxVelocity));
    Vector3f restDir = new Vector3f(restTail).sub(anchor).normalize();
    Vector3f direction = new Vector3f(j.position).sub(anchor);
    float ratio = direction.length() / length;
    direction.normalize();
    Quaternionf rotation = new Quaternionf().rotationTo(restDir, direction);
    Matrix4f transform =
        new Matrix4f()
            .translation(anchor)
            .rotate(rotation)
            .translate(-anchor.x, -anchor.y, -anchor.z);
    transform.mul(world[n.bone], world[n.bone]);
    for (int bone : j.descendants) transform.mul(world[bone], world[bone]);
    if (Math.abs(ratio - 1) > 1e-5f) stretch(j, world, anchor, direction, ratio);
    j.previousBoneFrame.set(frame);
    float angle = (float) Math.acos(Math.clamp(restDir.dot(direction), -1, 1)) / (float) Math.PI;
    float extension = n.maxStretch <= 0 ? 0 : Math.clamp((ratio - 1) / n.maxStretch, 0, 1);
    float squish = n.maxSquish <= 0 ? 0 : Math.clamp((1 - ratio) / n.maxSquish, 0, 1);
    var oldStatus = statuses.get(s);
    statuses.put(
        s,
        new Status(
            Math.max(oldStatus.angle, angle),
            Math.max(oldStatus.stretch, extension),
            Math.max(oldStatus.squish, squish),
            false,
            false));
  }

  private void stretch(
      Joint j, Matrix4f[] world, Vector3f anchor, Vector3f direction, float ratio) {
    if (j.children.length == 0) {
      Quaternionf axis = new Quaternionf().rotationTo(new Vector3f(0, 1, 0), direction);
      var transform =
          new Matrix4f()
              .translation(anchor)
              .rotate(axis)
              .scale(1, ratio, 1)
              .rotate(new Quaternionf(axis).conjugate())
              .translate(-anchor.x, -anchor.y, -anchor.z);
      transform.mul(world[j.node.bone], world[j.node.bone]);
    } else
      for (int child : j.children) {
        float along = world[child].getTranslation(new Vector3f()).sub(anchor).dot(direction);
        Vector3f shift = new Vector3f(direction).mul(along * (ratio - 1));
        for (int bone : j.descendants)
          if (bone == child || descendant(bone, child))
            world[bone]
                .m30(world[bone].m30() + shift.x)
                .m31(world[bone].m31() + shift.y)
                .m32(world[bone].m32() + shift.z);
      }
  }

  private Avatar.SpringNode[] legacyNodes(Avatar.Spring s) {
    Set<Integer> affected = new HashSet<>();
    for (int b : s.bones) affected.add(b);
    var result = new ArrayList<Avatar.SpringNode>();
    for (int b : s.bones) {
      int[] children =
          affected.stream()
              .filter(i -> avatar.bones[i].parent == b)
              .sorted()
              .mapToInt(i -> i)
              .toArray();
      if (children.length > 1 && "Ignore".equals(s.multiChildType)) continue;
      var n = new Avatar.SpringNode();
      n.bone = b;
      n.tail = s.endpoint.clone();
      if (children.length > 0) n.tail = avatar.bones[children[0]].position.clone();
      n.pull = s.pull;
      n.spring = s.spring;
      n.stiffness = s.stiffness;
      n.gravity = s.gravity;
      n.immobile = s.immobile;
      n.radius = s.radius;
      n.gravityFalloff = s.gravityFalloff;
      n.maxAngleX = s.maxAngleX;
      n.maxAngleZ = s.maxAngleZ;
      n.maxStretch = s.maxStretch;
      n.maxSquish = s.maxSquish;
      n.stretchMotion = s.stretchMotion;
      result.add(n);
    }
    return result.toArray(Avatar.SpringNode[]::new);
  }

  private boolean descendant(int bone, int root) {
    for (int i = avatar.bones[bone].parent; i >= 0; i = avatar.bones[i].parent)
      if (i == root) return true;
    return false;
  }

  private static Vector3f vec(float[] v) {
    return new Vector3f(v[0], v[1], v[2]);
  }

  private static boolean permitsSelf(com.google.gson.JsonObject settings, String field) {
    if (!settings.has(field) || !settings.get(field).isJsonObject()) return true;
    var filter = settings.getAsJsonObject(field);
    return !filter.has("allowSelf") || filter.get("allowSelf").getAsBoolean();
  }

  private static void collide(
      Vector3f p, Vector3f anchor, Avatar.Collider c, Matrix4f[] world, float radius) {
    if (!c.enabled || !c.active) return;
    Matrix4f matrix = world[c.bone];
    Vector3f center = matrix.transformPosition(vec(c.position));
    float scale = matrix.getScale(new Vector3f()).length() / (float) Math.sqrt(3);
    Vector3f axis =
        new Quaternionf(c.rotation[0], c.rotation[1], c.rotation[2], c.rotation[3])
            .transform(new Vector3f(0, 1, 0));
    matrix.transformDirection(axis).normalize();
    if ("Plane".equals(c.shape)) {
      if (c.inside) axis.negate();
      float d = new Vector3f(p).sub(center).dot(axis);
      if (d < radius) p.fma(radius - d, axis);
      return;
    }
    if ("Capsule".equals(c.shape) && c.height > 2 * c.radius) {
      float half = (c.height * .5f - c.radius) * scale;
      center.fma(Math.clamp(new Vector3f(p).sub(center).dot(axis), -half, half), axis);
    }
    if (!c.bonesAsSpheres) {
      Vector3f segment = new Vector3f(p).sub(anchor);
      float t =
          segment.lengthSquared() < 1e-12f
              ? 1
              : Math.clamp(
                  new Vector3f(center).sub(anchor).dot(segment) / segment.lengthSquared(), .05f, 1);
      Vector3f nearest = new Vector3f(anchor).fma(t, segment);
      p.fma(1 / t, pushOut(nearest, center, c.radius * scale, radius, c.inside));
    } else p.add(pushOut(p, center, c.radius * scale, radius, c.inside));
  }

  private static Vector3f pushOut(
      Vector3f p, Vector3f center, float colliderRadius, float particleRadius, boolean inside) {
    float r = Math.max(0, colliderRadius + (inside ? -particleRadius : particleRadius));
    Vector3f d = new Vector3f(p).sub(center);
    float length = d.length();
    if ((!inside && length < r) || (inside && length > r)) {
      if (length < 1e-8f) d.set(0, 1, 0);
      else d.div(length);
      return d.mul(r - length);
    }
    return new Vector3f();
  }
}

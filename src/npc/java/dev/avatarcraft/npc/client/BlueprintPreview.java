package dev.avatarcraft.npc.client;

import com.google.gson.*;
import dev.avatarcraft.npc.NpcMod;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;

/** Extract once, submit immutable actual block-model geometry with alpha. No client world edits. */
final class BlueprintPreview {
  private record Plan(String dimension, List<Cell> cells) {}

  private record Cell(BlockPos pos, BlockState state) {}

  private record Ghost(float x, float y, float z, List<BakedQuad> quads, boolean conflict) {}

  private static final Map<String, Plan> PLANS = new LinkedHashMap<>();
  private static final ContextKey<List<Ghost>> KEY =
      new ContextKey<>(NpcMod.id("blueprint_preview"));

  static void install() {
    NeoForge.EVENT_BUS.addListener(ExtractLevelRenderStateEvent.class, BlueprintPreview::extract);
    NeoForge.EVENT_BUS.addListener(SubmitCustomGeometryEvent.class, BlueprintPreview::submit);
    NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> PLANS.clear());
  }

  static void receive(String json) {
    if (json.length() > 262144) return;
    try {
      var o = JsonParser.parseString(json).getAsJsonObject();
      String npc = o.get("npc").getAsString();
      if (Set.of("complete", "cancelled").contains(o.get("status").getAsString())) {
        PLANS.remove(npc);
        return;
      }
      var origin = o.getAsJsonArray("origin");
      var palette = o.getAsJsonArray("palette");
      var source = o.getAsJsonArray("cells");
      if (origin.size() != 3 || palette.size() > 64 || source.size() > 4096) return;
      var cells = new ArrayList<Cell>();
      for (var entry : source) {
        var c = entry.getAsJsonArray();
        if (c.size() != 4) return;
        int index = c.get(3).getAsInt();
        if (index < 0 || index >= palette.size()) return;
        var id = Identifier.tryParse(palette.get(index).getAsString());
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return;
        var p =
            new BlockPos(
                origin.get(0).getAsInt() + c.get(0).getAsInt(),
                origin.get(1).getAsInt() + c.get(1).getAsInt(),
                origin.get(2).getAsInt() + c.get(2).getAsInt());
        cells.add(new Cell(p, BuiltInRegistries.BLOCK.getValue(id).defaultBlockState()));
      }
      PLANS.put(npc, new Plan(o.get("dimension").getAsString(), List.copyOf(cells)));
      while (PLANS.size() > 64) PLANS.remove(PLANS.keySet().iterator().next());
    } catch (RuntimeException ignored) {
    }
  }

  private static void extract(ExtractLevelRenderStateEvent e) {
    var ghosts = new ArrayList<Ghost>();
    var camera = e.getCamera().position();
    var mc = Minecraft.getInstance();
    for (var plan : PLANS.values()) {
      if (!plan.dimension.equals(e.getLevel().dimension().identifier().toString())) continue;
      for (var c : plan.cells) {
        if (ghosts.size() >= 4096
            || Vec3.atCenterOf(c.pos).distanceToSqr(camera) > 96 * 96
            || !e.getLevel().hasChunkAt(c.pos)
            || e.getLevel().getBlockState(c.pos).is(c.state.getBlock())) continue;
        var parts = new ArrayList<BlockStateModelPart>();
        mc.getModelManager()
            .getBlockStateModelSet()
            .get(c.state)
            .collectParts(e.getLevel(), c.pos, c.state, RandomSource.create(42), parts);
        var quads = new ArrayList<BakedQuad>();
        for (var part : parts) {
          quads.addAll(part.getQuads(null));
          for (var face : Direction.values()) quads.addAll(part.getQuads(face));
        }
        ghosts.add(
            new Ghost(
                (float) (c.pos.getX() - camera.x),
                (float) (c.pos.getY() - camera.y),
                (float) (c.pos.getZ() - camera.z),
                List.copyOf(quads),
                !e.getLevel().getBlockState(c.pos).isAir()));
      }
    }
    e.getRenderState().setRenderData(KEY, List.copyOf(ghosts));
  }

  private static void submit(SubmitCustomGeometryEvent e) {
    var ghosts = e.getLevelRenderState().getRenderData(KEY);
    if (ghosts == null || ghosts.isEmpty()) return;
    var groups = new LinkedHashMap<Identifier, List<Ghost>>();
    for (var ghost : ghosts)
      for (var quad : ghost.quads) {
        var texture = quad.materialInfo().sprite().atlasLocation();
        groups
            .computeIfAbsent(texture, id -> new ArrayList<>())
            .add(new Ghost(ghost.x, ghost.y, ghost.z, List.of(quad), ghost.conflict));
      }
    for (var group : groups.entrySet())
      e.getSubmitNodeCollector()
          .submitCustomGeometry(
              e.getPoseStack(),
              RenderTypes.entityTranslucent(group.getKey()),
              (pose, buffer) -> {
                for (var g : group.getValue())
                  for (var q : g.quads)
                    for (int i = 0; i < 4; i++) {
                      var p = q.position(i);
                      var d = q.direction();
                      long uv = q.packedUV(i);
                      buffer
                          .addVertex(pose, g.x + p.x(), g.y + p.y(), g.z + p.z())
                          .setColor(1f, g.conflict ? .35f : 1f, g.conflict ? .35f : 1f, .35f)
                          .setUv(UVPair.unpackU(uv), UVPair.unpackV(uv))
                          .setOverlay(OverlayTexture.NO_OVERLAY)
                          .setLight(15728880)
                          .setNormal(pose, d.getStepX(), d.getStepY(), d.getStepZ());
                    }
              });
  }
}

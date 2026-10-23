package dev.avatarcraft.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.*;
import dev.avatarcraft.avatar.Avatar;
import java.util.*;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.resources.Identifier;

/** lilToon-inspired shading; original GLSL, not Unity shader bytecode. */
public final class AvatarMaterials {
  private static final Map<String, RenderType> CACHE = new HashMap<>();
  private static final Map<Avatar.Material, String> SIGNATURES = new WeakHashMap<>();

  public static RenderType get(
      Avatar.Material mat, Identifier texture, boolean emission, boolean outline) {
    String signature =
        SIGNATURES.computeIfAbsent(
            mat, m -> m.shader + ":" + m.renderQueue + ":" + m.properties.toString());
    String key = texture + ":" + signature + ":" + emission + ":" + outline;
    return CACHE.computeIfAbsent(
        key,
        k -> {
          var p =
              RenderPipeline.builder()
                  .withLocation(
                      Identifier.fromNamespaceAndPath("avatarcraft", "pipeline/" + CACHE.size()))
                  .withVertexShader(Identifier.fromNamespaceAndPath("avatarcraft", "core/avatar"))
                  .withFragmentShader(Identifier.fromNamespaceAndPath("avatarcraft", "core/avatar"))
                  .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                  .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                  .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                  .withBindGroupLayout(BindGroupLayouts.FOG)
                  .withBindGroupLayout(BindGroupLayouts.LIGHTING)
                  .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                  .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
                  .withVertexBinding(0, DefaultVertexFormat.ENTITY)
                  .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                  .withDepthStencilState(DepthStencilState.DEFAULT)
                  .withCull(outline || mat.number("_Cull", 2) != 0)
                  .withColorTargetState(
                      new ColorTargetState(
                          emission ? BlendFunction.ADDITIVE : BlendFunction.TRANSLUCENT));
          p.withShaderDefine("AVC_CUTOFF", mat.number("_Cutoff", .5f));
          p.withShaderDefine("AVC_SHADOW", mat.number("_UseShadow", 0));
          p.withShaderDefine("AVC_BORDER", mat.number("_ShadowBorder", .5f));
          p.withShaderDefine("AVC_BLUR", Math.max(.001f, mat.number("_ShadowBlur", .1f)));
          float[] shadow = mat.color("_ShadowColor", .7f, .7f, .8f, 1),
              rim = mat.color("_RimColor", 1, 1, 1, 1);
          for (int i = 0; i < 3; i++) {
            p.withShaderDefine("AVC_SHADOW_" + "RGB".charAt(i), shadow[i]);
            p.withShaderDefine("AVC_RIM_" + "RGB".charAt(i), rim[i]);
          }
          p.withShaderDefine("AVC_RIM", mat.number("_UseRim", 0) * rim[3]);
          p.withShaderDefine("AVC_RIM_BORDER", mat.number("_RimBorder", .5f));
          p.withShaderDefine("AVC_RIM_BLUR", Math.max(.001f, mat.number("_RimBlur", .1f)));
          if (emission) p.withShaderDefine("AVC_EMISSION");
          if (outline) p.withShaderDefine("AVC_OUTLINE");
          if (mat.renderQueue < 2500) p.withShaderDefine("AVC_OPAQUE");
          var setup = RenderSetup.builder(p.build()).withTexture("Sampler0", texture).useLightmap();
          return RenderType.create("avatarcraft", setup.createRenderSetup());
        });
  }

  public static void clear() {
    CACHE.clear();
  }
}

package dev.avatarcraft.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.avatarcraft.avatar.Avatar;
import dev.avatarcraft.avatar.MaterialAlpha;
import dev.avatarcraft.avatar.SurfaceMaterial;
import java.util.*;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.resources.Identifier;

/** lilToon-inspired shading; original GLSL, not Unity shader bytecode. */
public final class AvatarMaterials {
  private static final Map<String, RenderType> CACHE = new HashMap<>();
  private static final Map<Avatar.Material, String> SIGNATURES = new WeakHashMap<>();

  public static RenderType get(
      Avatar.Material mat,
      Identifier texture,
      AvatarTextures.Surface surface,
      boolean emission,
      boolean outline) {
    String signature =
        SIGNATURES.computeIfAbsent(
            mat,
            m ->
                m.shader
                    + ":"
                    + m.renderType
                    + ":"
                    + m.renderQueue
                    + ":"
                    + m.properties.toString());
    String key =
        texture + ":" + surface.texture() + ":" + signature + ":" + emission + ":" + outline;
    return CACHE.computeIfAbsent(
        key,
        k -> {
          boolean transparent =
              MaterialAlpha.mode(mat) == MaterialAlpha.Mode.TRANSLUCENT || emission;
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
                  .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
                  .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
                  .withVertexBinding(0, DefaultVertexFormat.ENTITY)
                  .withPrimitiveTopology(
                      transparent ? PrimitiveTopology.QUADS : PrimitiveTopology.TRIANGLES)
                  .withDepthStencilState(
                      new DepthStencilState(
                          DepthStencilState.DEFAULT.depthTest(),
                          !emission && MaterialAlpha.writeDepth(mat)))
                  .withCull(outline || mat.number("_Cull", 2) != 0)
                  .withColorTargetState(
                      transparent
                          ? new ColorTargetState(
                              emission ? BlendFunction.ADDITIVE : BlendFunction.TRANSLUCENT)
                          : ColorTargetState.DEFAULT);
          p.withShaderDefine("AVC_CUTOFF", MaterialAlpha.cutoff(mat));
          SurfaceMaterial.defines(mat).forEach(p::withShaderDefine);
          surface.defines().forEach(p::withShaderDefine);
          for (var property : SurfaceMaterial.SLOTS) {
            String name = "T" + property.toUpperCase(Locale.ROOT);
            p.withShaderDefine(name + "_HAS", surface.present().contains(name) ? 1 : 0);
          }
          String slot = emission ? "_EmissionMap" : outline ? "_OutlineTex" : "_MainTex";
          float[] uvScale = mat.uv(slot, "scale", 1, 1),
              uvOffset = mat.uv(slot, "offset", 0, 0),
              maskScale = mat.uv("_AlphaMask", "scale", 1, 1),
              maskOffset = mat.uv("_AlphaMask", "offset", 0, 0);
          p.withShaderDefine("AVC_U_SCALE", uvScale[0]);
          p.withShaderDefine("AVC_V_SCALE", uvScale[1]);
          p.withShaderDefine("AVC_U_OFFSET", uvOffset[0]);
          p.withShaderDefine("AVC_V_OFFSET", uvOffset[1]);
          p.withShaderDefine("AVC_MASK_U_SCALE", maskScale[0]);
          p.withShaderDefine("AVC_MASK_V_SCALE", maskScale[1]);
          p.withShaderDefine("AVC_MASK_U_OFFSET", maskOffset[0]);
          p.withShaderDefine("AVC_MASK_V_OFFSET", maskOffset[1]);
          p.withShaderDefine("AVC_ALPHA_MASK_MODE", (int) mat.number("_AlphaMaskMode", 0));
          p.withShaderDefine("AVC_ALPHA_MASK_SCALE", mat.number("_AlphaMaskScale", 1));
          p.withShaderDefine("AVC_ALPHA_MASK_VALUE", mat.number("_AlphaMaskValue", 0));
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
          if (MaterialAlpha.mode(mat) != MaterialAlpha.Mode.TRANSLUCENT)
            p.withShaderDefine("AVC_OPAQUE");
          if (MaterialAlpha.mode(mat) == MaterialAlpha.Mode.CUTOUT)
            p.withShaderDefine("AVC_CUTOUT");
          var setup =
              RenderSetup.builder(p.build())
                  .withTexture("Sampler0", texture)
                  .withTexture(
                      "Sampler1",
                      surface.texture(),
                      () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR))
                  .useLightmap();
          if (transparent) setup.sortOnUpload();
          return RenderType.create("avatarcraft", setup.createRenderSetup());
        });
  }

  public static void clear() {
    CACHE.clear();
  }
}

#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:globals.glsl>
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
layout(location=0) in vec2 uv;
layout(location=1) in vec4 tint;
layout(location=2) in vec4 lightmap;
layout(location=3) in vec3 normal;
layout(location=4) in vec3 viewDirection;
layout(location=5) in float sphereDistance;
layout(location=6) in float cylinderDistance;
layout(location=7) in vec3 lightDirection;
layout(location=8) in vec3 modelPosition;
layout(location=0) out vec4 fragColor;
#include <avatarcraft:surface_textures.glsl>
#include <avatarcraft:surface_common.glsl>
#include <avatarcraft:surface_effects.glsl>
void main() {
    vec2 mainUv=primaryUv(uv);
    vec4 color=texture(Sampler0,mainUv)*tint;
    vec3 original=safeNormal(normal)*(gl_FrontFacing?1.0:-1.0);
    vec3 v=safeNormal(viewDirection);
    mat3 frame=surfaceFrame(original,-viewDirection,vec2(uv.x,1.0-uv.y));
    vec3 n=surfaceNormal(original,frame,uv);
    color.a=surfaceAlpha(color.a,sat(tex_AlphaMask(mainUv).r*AVC_ALPHA_MASK_SCALE+AVC_ALPHA_MASK_VALUE),float(AVC_ALPHA_MASK_MODE));
    float edge;
    float dissolved=dissolveValue(tex_DissolveMask(uv).r,(tex_DissolveNoiseMask(uv).r-0.5)*S_DISSOLVENOISESTRENGTH,S_DISSOLVEPARAMS,S_DISSOLVEPOS,uv,modelPosition,edge);
    if(dissolved<0.5) discard;
#if !defined(AVC_OUTLINE)
    color.rgb=mix(color.rgb,surfaceTone(color.rgb,S_MAINTEXHSVG),tex_MainColorAdjustMask(mainUv).r);
    vec3 grad=vec3(tex_MainGradationTex(vec2(sat(color.r),0.5)).r,tex_MainGradationTex(vec2(sat(color.g),0.5)).g,tex_MainGradationTex(vec2(sat(color.b),0.5)).b);
    color.rgb=mix(color.rgb,grad,S_MAINGRADATIONSTRENGTH*tex_MainColorAdjustMask(mainUv).r);
    vec3 edge2,edge3;
    vec4 second=layer2nd(uv,n,v,modelPosition,edge2), third=layer3rd(uv,n,v,modelPosition,edge3);
#if defined(AVC_CUTOUT) || !defined(AVC_OPAQUE)
    if(S_USEMAIN2NDTEX>0.0 && S_MAIN2NDTEXALPHAMODE>0.0) {
        color.a=surfaceAlpha(color.a,second.a,S_MAIN2NDTEXALPHAMODE); second.a=1.0;
    }
    if(S_USEMAIN3RDTEX>0.0 && S_MAIN3RDTEXALPHAMODE>0.0) {
        color.a=surfaceAlpha(color.a,third.a,S_MAIN3RDTEXALPHAMODE); third.a=1.0;
    }
#endif
    vec3 baseColor=surfaceBlend(color.rgb,second.rgb,vec3(second.a),S_MAIN2NDTEXBLENDMODE);
    baseColor=surfaceBlend(baseColor,third.rgb,vec3(third.a),S_MAIN3RDTEXBLENDMODE);
    float ndl=dot(n,safeNormal(lightDirection))*0.5+0.5;
    vec3 border=tex_ShadowBorderMask(mainUv).rgb, blur=tex_ShadowBlurMask(mainUv).rgb;
    float width=max(0.001,S_SHADOWBLUR*blur.r);
    float shade=smoothstep(S_SHADOWBORDER*border.r-width*0.5,S_SHADOWBORDER*border.r+width*0.5,ndl);
    vec3 shadow=mix(S_SHADOWCOLOR.rgb*tex_ShadowColorTex(mainUv).rgb,vec3(1),shade);
    float width2=max(0.001,S_SHADOW2NDBLUR*blur.g), width3=max(0.001,S_SHADOW3RDBLUR*blur.b);
    float shade2=smoothstep(S_SHADOW2NDBORDER*border.g-width2*0.5,S_SHADOW2NDBORDER*border.g+width2*0.5,ndl);
    float shade3=smoothstep(S_SHADOW3RDBORDER*border.b-width3*0.5,S_SHADOW3RDBORDER*border.b+width3*0.5,ndl);
    shadow=mix(shadow,S_SHADOW2NDCOLOR.rgb*tex_Shadow2ndColorTex(mainUv).rgb,(1.0-shade2)*S_SHADOW2NDCOLOR.a);
    shadow=mix(shadow,S_SHADOW3RDCOLOR.rgb*tex_Shadow3rdColorTex(mainUv).rgb,(1.0-shade3)*S_SHADOW3RDCOLOR.a);
    shadow=mix(vec3(1),shadow,sat(S_USESHADOW*S_SHADOWSTRENGTH*tex_ShadowStrengthMask(mainUv).r));
    vec3 lighting=shadow*lightmap.rgb;
    color.rgb*=lighting;
    color.rgb=surfaceBlend(color.rgb,second.rgb*mix(vec3(1),lighting,S_MAIN2NDENABLELIGHTING),vec3(second.a),S_MAIN2NDTEXBLENDMODE);
    color.rgb=surfaceBlend(color.rgb,third.rgb*mix(vec3(1),lighting,S_MAIN3RDENABLELIGHTING),vec3(third.a),S_MAIN3RDTEXBLENDMODE);
    color.rgb=matcap(color.rgb,n,original,frame,v,uv,lighting,shade,color.a);
    color.rgb=matcap2nd(color.rgb,n,original,frame,v,uv,lighting,shade,color.a);
    float rim=1.0-abs(dot(n,v));
    rim=smoothstep(S_RIMBORDER-max(S_RIMBLUR,0.001)*0.5,S_RIMBORDER+max(S_RIMBLUR,0.001)*0.5,rim);
    vec4 rimColor=S_RIMCOLOR*tex_RimColorTex(mainUv);
    color.rgb=surfaceBlend(color.rgb,rimColor.rgb*mix(vec3(1),lighting,S_RIMENABLELIGHTING),vec3(rim*rimColor.a*S_USERIM),S_RIMBLENDMODE);
    color.rgb=emission(color.rgb,baseColor,uv,n,v,color.a);
    color.rgb=emission2nd(color.rgb,baseColor,uv,n,v,color.a);
    color.rgb+=edge*S_DISSOLVECOLOR.rgb+edge2+edge3;
#else
    color.rgb+=edge*S_DISSOLVECOLOR.rgb;
#endif
    color*=ColorModulator;
#if defined(AVC_CUTOUT) || !defined(AVC_OPAQUE)
    if(color.a<AVC_CUTOFF) discard;
#endif
#ifdef AVC_OPAQUE
    color.a=1.0;
#endif
    fragColor=apply_fog(color,sphereDistance,cylinderDistance,FogEnvironmentalStart,FogEnvironmentalEnd,FogRenderDistanceStart,FogRenderDistanceEnd,FogColor);
}

#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
uniform sampler2D Sampler0;
layout(location=0) in vec2 uv;
layout(location=1) in vec4 tint;
layout(location=2) in vec4 lightmap;
layout(location=3) in vec3 normal;
layout(location=4) in vec3 viewDirection;
layout(location=5) in float sphereDistance;
layout(location=6) in float cylinderDistance;
layout(location=7) in vec3 lightDirection;
layout(location=0) out vec4 fragColor;
void main() {
    vec4 color=texture(Sampler0,uv)*tint*ColorModulator;
    if(color.a<AVC_CUTOFF) discard;
#ifdef AVC_OPAQUE
#ifndef AVC_EMISSION
    color.a=1.0;
#endif
#endif
#if !defined(AVC_EMISSION) && !defined(AVC_OUTLINE)
    vec3 n=normalize(normal)*(gl_FrontFacing?1.0:-1.0);
    float ndl=dot(n,normalize(lightDirection))*.5+.5;
    float shade=smoothstep(AVC_BORDER-AVC_BLUR*.5,AVC_BORDER+AVC_BLUR*.5,ndl);
    color.rgb*=mix(vec3(1),mix(vec3(AVC_SHADOW_R,AVC_SHADOW_G,AVC_SHADOW_B),vec3(1),shade),AVC_SHADOW);
    float rim=1.0-abs(dot(n,normalize(viewDirection)));
    rim=smoothstep(AVC_RIM_BORDER-AVC_RIM_BLUR*.5,AVC_RIM_BORDER+AVC_RIM_BLUR*.5,rim);
    color.rgb+=rim*AVC_RIM*vec3(AVC_RIM_R,AVC_RIM_G,AVC_RIM_B);
    color.rgb*=lightmap.rgb;
#endif
    fragColor=apply_fog(color,sphereDistance,cylinderDistance,FogEnvironmentalStart,FogEnvironmentalEnd,FogRenderDistanceStart,FogRenderDistanceEnd,FogColor);
}

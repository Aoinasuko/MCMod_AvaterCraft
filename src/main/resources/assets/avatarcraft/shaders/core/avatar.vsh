#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <minecraft:light.glsl>
layout(location=0) in vec3 Position;
layout(location=1) in vec4 Color;
layout(location=2) in vec2 UV0;
layout(location=3) in ivec2 UV1;
layout(location=4) in ivec2 UV2;
layout(location=5) in vec3 Normal;
uniform sampler2D Sampler2;
layout(location=0) out vec2 uv;
layout(location=1) out vec4 tint;
layout(location=2) out vec4 lightmap;
layout(location=3) out vec3 normal;
layout(location=4) out vec3 viewDirection;
layout(location=5) out float sphereDistance;
layout(location=6) out float cylinderDistance;
layout(location=7) out vec3 lightDirection;
void main() {
    vec4 viewPosition=ModelViewMat*vec4(Position,1.0);
    gl_Position=ProjMat*viewPosition;
    uv=UV0; tint=Color; lightmap=sample_lightmap(Sampler2,UV2);
    normal=mat3(ModelViewMat)*Normal;
    viewDirection=-viewPosition.xyz;
    lightDirection=mat3(ModelViewMat)*(Light0_Direction+Light1_Direction);
    sphereDistance=fog_spherical_distance(Position);
    cylinderDistance=fog_cylindrical_distance(Position);
}

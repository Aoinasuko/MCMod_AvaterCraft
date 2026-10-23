// Independent GLSL approximation of exported lilToon material settings.
float sat(float x) { return clamp(x,0.0,1.0); }
vec3 safeNormal(vec3 n) { return n*inversesqrt(max(dot(n,n),1e-12)); }
vec3 surfaceBlend(vec3 a, vec3 b, vec3 amount, float mode) {
    vec3 target=b;
    if(mode==1.0) target=a+b;
    if(mode==2.0) target=max(a+b-a*b,a);
    if(mode==3.0) target=a*b;
    return mix(a,target,clamp(amount,0.0,1.0));
}
float surfaceAlpha(float a,float b,float mode) {
    if(mode==1.0) return b;
    if(mode==2.0) return a*b;
    if(mode==3.0) return sat(a+b);
    if(mode==4.0) return sat(a-b);
    return a;
}
vec2 rotateUv(vec2 p,float angle) {
    float c=cos(angle),s=sin(angle); p-=0.5;
    return vec2(c*p.x-s*p.y,s*p.x+c*p.y)+0.5;
}
vec2 animatedUv(vec2 p,vec4 motion,float angle) {
    // GameTime has the same 20-minute wrap as the Minecraft globals buffer.
    float seconds=GameTime*1200.0;
    vec2 u=vec2(p.x,1.0-p.y);
    u=rotateUv(u,angle+motion.w*seconds)+motion.xy*seconds;
    return vec2(u.x,1.0-u.y);
}
vec2 primaryUv(vec2 p) {
    return surfaceTransform(animatedUv(p,S_MAINTEX_SCROLLROTATE,0.0),vec4(AVC_U_SCALE,AVC_V_SCALE,AVC_U_OFFSET,AVC_V_OFFSET));
}
vec3 surfaceTone(vec3 rgb,vec4 hsvg) {
    rgb=pow(abs(rgb),vec3(max(hsvg.w,1e-4)));
    vec4 K=vec4(0.0,-1.0/3.0,2.0/3.0,-1.0);
    vec4 p=mix(vec4(rgb.bg,K.wz),vec4(rgb.gb,K.xy),step(rgb.b,rgb.g));
    vec4 q=mix(vec4(p.xyw,rgb.r),vec4(rgb.r,p.yzx),step(p.x,rgb.r));
    float d=q.x-min(q.w,q.y);
    vec3 hsv=vec3(abs(q.z+(q.w-q.y)/(6.0*d+1e-10)),d/(q.x+1e-10),q.x);
    hsv=vec3(fract(hsv.x+hsvg.x),sat(hsv.y*hsvg.y),sat(hsv.z*hsvg.z));
    vec3 c=clamp(abs(fract(hsv.xxx+vec3(0.0,2.0/3.0,1.0/3.0))*6.0-3.0)-1.0,0.0,1.0);
    return hsv.z*mix(vec3(1.0),c,hsv.y);
}
mat3 surfaceFrame(vec3 n,vec3 position,vec2 u) {
    vec3 dx=dFdx(position),dy=dFdy(position);
    vec2 tx=dFdx(u),ty=dFdy(u);
    float determinant=tx.x*ty.y-tx.y*ty.x;
    vec3 t=dx*ty.y-dy*tx.y, b=dy*tx.x-dx*ty.x;
    if(abs(determinant)<1e-10 || dot(t,t)<1e-12) {
        t=cross(abs(n.y)<0.9?vec3(0,1,0):vec3(1,0,0),n);
        return mat3(safeNormal(t),safeNormal(cross(n,t)),n);
    }
    t*=sign(determinant); b*=sign(determinant);
    t=safeNormal(t-n*dot(n,t));
    b=safeNormal(cross(n,t))*sign(dot(cross(n,t),b));
    return mat3(t,b,n);
}
vec3 unpackSurfaceNormal(vec4 texel,float strength) {
    vec2 xy=(texel.xy*2.0-1.0)*strength;
    return safeNormal(vec3(xy,sqrt(max(1.0-dot(xy,xy),1e-6))));
}
vec3 surfaceNormal(vec3 n,mat3 frame,vec2 p) {
    vec3 tangent=vec3(0,0,1);
    if(S_USEBUMPMAP>0.0) tangent=unpackSurfaceNormal(tex_BumpMap(primaryUv(p)),S_BUMPSCALE);
    if(S_USEBUMP2NDMAP>0.0) {
        vec3 second=unpackSurfaceNormal(tex_Bump2ndMap(p),S_BUMP2NDSCALE*tex_Bump2ndScaleMask(primaryUv(p)).r);
        // Whiteout blending retains the contribution from each tangent-space map.
        tangent=safeNormal(vec3(tangent.xy+second.xy,tangent.z*second.z));
    }
    return safeNormal(frame*tangent);
}
vec2 matcapUv(vec3 n,vec3 v,float perspective) {
    vec3 axis=safeNormal(mix(vec3(0,0,1),v,perspective));
    vec3 right=safeNormal(cross(vec3(0,1,0),axis));
    if(dot(right,right)<0.5) right=vec3(1,0,0);
    vec3 up=safeNormal(cross(axis,right));
    return vec2(dot(n,right),-dot(n,up))*0.5+0.5;
}
vec2 layerUv(vec2 p,float mode,vec3 n,vec3 v) {
    return mode==4.0?matcapUv(n,v,1.0):p;
}
float dissolveValue(float sampleValue,float noise,vec4 params,vec4 origin,vec2 p,vec3 position,out float edge) {
    edge=0.0;
    if(params.x==0.0) return 1.0;
    float value=sampleValue+noise;
    vec2 u=vec2(p.x,1.0-p.y);
    if(params.x==2.0) value=(params.y==1.0?rotateUv(u,origin.w).x:distance(u,origin.xy))+noise;
    if(params.x==3.0) value=(params.y==1.0?dot(position,safeNormal(origin.xyz)):distance(position,origin.xyz))+noise;
    edge=1.0-sat(abs(value-params.z)/max(params.w,1e-5));
    return value>params.z?1.0:0.0;
}

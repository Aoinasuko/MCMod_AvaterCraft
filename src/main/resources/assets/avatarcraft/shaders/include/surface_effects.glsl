vec4 layer2nd(vec2 base,vec3 n,vec3 v,vec3 position,out vec3 edgeColor) {
  edgeColor=vec3(0.0);
  if(S_USEMAIN2NDTEX<=0.0) return vec4(0.0);
  vec2 p=animatedUv(layerUv(base,S_MAIN2NDTEX_UVMODE,n,v),S_MAIN2NDTEX_SCROLLROTATE,S_MAIN2NDTEXANGLE);
  vec4 c=tex_Main2ndTex(p)*S_COLOR2ND;
  c.a*=tex_Main2ndBlendMask(primaryUv(base)).r;
#if T_MAIN2NDTEX_HAS > 0
  vec2 decal=surfaceTransform(p,vec4(T_MAIN2NDTEX_SX,T_MAIN2NDTEX_SY,T_MAIN2NDTEX_OX,T_MAIN2NDTEX_OY));
  if(S_MAIN2NDTEXISDECAL>0.0 && (any(lessThan(decal,vec2(0))) || any(greaterThan(decal,vec2(1))))) c.a=0.0;
#endif
  if((S_MAIN2NDTEX_CULL==1.0 && gl_FrontFacing)||(S_MAIN2NDTEX_CULL==2.0 && !gl_FrontFacing)) c.a=0.0;
  if((S_MAIN2NDTEXISLEFTONLY>0.0 && base.x>=0.5)||(S_MAIN2NDTEXISRIGHTONLY>0.0 && base.x<0.5)) c.a=0.0;
  float edge;
  c.a*=dissolveValue(tex_Main2ndDissolveMask(base).r,(tex_Main2ndDissolveNoiseMask(base).r-0.5)*S_MAIN2NDDISSOLVENOISESTRENGTH,S_MAIN2NDDISSOLVEPARAMS,S_MAIN2NDDISSOLVEPOS,base,position,edge);
  edgeColor=S_MAIN2NDDISSOLVECOLOR.rgb*edge*c.a;
  return c;
}
vec4 layer3rd(vec2 base,vec3 n,vec3 v,vec3 position,out vec3 edgeColor) {
  edgeColor=vec3(0.0);
  if(S_USEMAIN3RDTEX<=0.0) return vec4(0.0);
  vec2 p=animatedUv(layerUv(base,S_MAIN3RDTEX_UVMODE,n,v),S_MAIN3RDTEX_SCROLLROTATE,S_MAIN3RDTEXANGLE);
  vec4 c=tex_Main3rdTex(p)*S_COLOR3RD;
  c.a*=tex_Main3rdBlendMask(primaryUv(base)).r;
#if T_MAIN3RDTEX_HAS > 0
  vec2 decal=surfaceTransform(p,vec4(T_MAIN3RDTEX_SX,T_MAIN3RDTEX_SY,T_MAIN3RDTEX_OX,T_MAIN3RDTEX_OY));
  if(S_MAIN3RDTEXISDECAL>0.0 && (any(lessThan(decal,vec2(0))) || any(greaterThan(decal,vec2(1))))) c.a=0.0;
#endif
  if((S_MAIN3RDTEX_CULL==1.0 && gl_FrontFacing)||(S_MAIN3RDTEX_CULL==2.0 && !gl_FrontFacing)) c.a=0.0;
  if((S_MAIN3RDTEXISLEFTONLY>0.0 && base.x>=0.5)||(S_MAIN3RDTEXISRIGHTONLY>0.0 && base.x<0.5)) c.a=0.0;
  float edge;
  c.a*=dissolveValue(tex_Main3rdDissolveMask(base).r,(tex_Main3rdDissolveNoiseMask(base).r-0.5)*S_MAIN3RDDISSOLVENOISESTRENGTH,S_MAIN3RDDISSOLVEPARAMS,S_MAIN3RDDISSOLVEPOS,base,position,edge);
  edgeColor=S_MAIN3RDDISSOLVECOLOR.rgb*edge*c.a;
  return c;
}
vec3 matcap(vec3 color,vec3 n,vec3 original,mat3 frame,vec3 v,vec2 p,vec3 lighting,float shade,float alpha) {
  if(S_USEMATCAP<=0.0) return color;
  vec3 capNormal=safeNormal(mix(original,n,S_MATCAPNORMALSTRENGTH));
  if(S_MATCAPCUSTOMNORMAL>0.0) capNormal=safeNormal(frame*unpackSurfaceNormal(tex_MatCapBumpMap(primaryUv(p)),S_MATCAPBUMPSCALE));
  vec4 cap=tex_MatCapTex(matcapUv(capNormal,v,S_MATCAPPERSPECTIVE))*S_MATCAPCOLOR;
  cap.rgb*=mix(vec3(1),color,S_MATCAPMAINSTRENGTH);
  cap.rgb*=mix(vec3(1),lighting,S_MATCAPENABLELIGHTING);
  vec3 amount=tex_MatCapBlendMask(primaryUv(p)).rgb*cap.a*S_MATCAPBLEND;
  amount*=mix(1.0,shade,S_MATCAPSHADOWMASK)*mix(1.0,alpha,S_MATCAPAPPLYTRANSPARENCY);
  if(S_MATCAPBACKFACEMASK>0.0 && !gl_FrontFacing) amount=vec3(0);
  return surfaceBlend(color,cap.rgb,amount,S_MATCAPBLENDMODE);
}
vec3 matcap2nd(vec3 color,vec3 n,vec3 original,mat3 frame,vec3 v,vec2 p,vec3 lighting,float shade,float alpha) {
  if(S_USEMATCAP2ND<=0.0) return color;
  vec3 capNormal=safeNormal(mix(original,n,S_MATCAP2NDNORMALSTRENGTH));
  if(S_MATCAP2NDCUSTOMNORMAL>0.0) capNormal=safeNormal(frame*unpackSurfaceNormal(tex_MatCap2ndBumpMap(primaryUv(p)),S_MATCAP2NDBUMPSCALE));
  vec4 cap=tex_MatCap2ndTex(matcapUv(capNormal,v,S_MATCAP2NDPERSPECTIVE))*S_MATCAP2NDCOLOR;
  cap.rgb*=mix(vec3(1),color,S_MATCAP2NDMAINSTRENGTH);
  cap.rgb*=mix(vec3(1),lighting,S_MATCAP2NDENABLELIGHTING);
  vec3 amount=tex_MatCap2ndBlendMask(primaryUv(p)).rgb*cap.a*S_MATCAP2NDBLEND;
  amount*=mix(1.0,shade,S_MATCAP2NDSHADOWMASK)*mix(1.0,alpha,S_MATCAP2NDAPPLYTRANSPARENCY);
  if(S_MATCAP2NDBACKFACEMASK>0.0 && !gl_FrontFacing) amount=vec3(0);
  return surfaceBlend(color,cap.rgb,amount,S_MATCAP2NDBLENDMODE);
}
vec3 emission(vec3 color,vec3 base,vec2 p,vec3 n,vec3 v,float alpha) {
  if(S_USEEMISSION<=0.0) return color;
  vec2 u=animatedUv(layerUv(p,S_EMISSIONMAP_UVMODE,n,v),S_EMISSIONMAP_SCROLLROTATE,0.0);
  vec4 e=tex_EmissionMap(u)*S_EMISSIONCOLOR;
  e.rgb*=mix(vec3(1),base,S_EMISSIONMAINSTRENGTH);
  vec3 amount=tex_EmissionBlendMask(primaryUv(p)).rgb*e.a*S_EMISSIONBLEND;
  return surfaceBlend(color,e.rgb,amount,S_EMISSIONBLENDMODE);
}
vec3 emission2nd(vec3 color,vec3 base,vec2 p,vec3 n,vec3 v,float alpha) {
  if(S_USEEMISSION2ND<=0.0) return color;
  vec2 u=animatedUv(layerUv(p,S_EMISSION2NDMAP_UVMODE,n,v),S_EMISSION2NDMAP_SCROLLROTATE,0.0);
  vec4 e=tex_Emission2ndMap(u)*S_EMISSION2NDCOLOR;
  e.rgb*=mix(vec3(1),base,S_EMISSION2NDMAINSTRENGTH);
  vec3 amount=tex_Emission2ndBlendMask(primaryUv(p)).rgb*e.a*S_EMISSION2NDBLEND;
  return surfaceBlend(color,e.rgb,amount,S_EMISSION2NDBLENDMODE);
}

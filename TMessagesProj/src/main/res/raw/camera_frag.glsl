#extension GL_OES_EGL_image_external : require

precision mediump float;

varying vec2 uv;
varying vec2 auv;
uniform samplerExternalOES sTexture;
uniform vec2 pixelWH;
uniform float roundRadius;
uniform float scale;
uniform float alpha;
uniform float shapeFrom, shapeTo, shapeT;
uniform float dual;
uniform float blur;
uniform float fxType;
uniform float fxTime;
uniform vec2 fxTexel;
uniform sampler2D lensFrame; // U message: face lens frame (Snap Camera Kit) in gl_FragCoord space
uniform float lensOn;
uniform vec2 lensViewport;

// U message story camera effects (fxType, 0 is off), see StoryCameraEffects
float fxRand(vec2 p) {
  return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}
float fxLuma(vec3 c) {
  return dot(c, vec3(.299, .587, .114));
}
vec3 fxSample(vec2 p) {
  return texture2D(sTexture, clamp(p, 0., 1.)).rgb;
}
// screen space (0..1, y up) of this pixel and the camera texture coordinate of any screen point,
// so effects stay round and upright whatever the sensor rotation is
vec2 fxS() {
  return gl_FragCoord.xy / max(lensViewport, vec2(1.));
}
float fxAspect() {
  return lensViewport.x / max(lensViewport.y, 1.);
}
// texture step per screen width / height, from derivatives taken once in main() (uniform control flow)
vec2 fxJx;
vec2 fxJy;
vec2 fxTex(vec2 p, vec2 t) {
  vec2 s = fxS();
#ifdef FX_DERIV
  return p + fxJx * (t.x - s.x) + fxJy * (t.y - s.y);
#else
  return p + (t - s);
#endif
}
vec3 fxAt(vec2 p, vec2 t) {
  return fxSample(fxTex(p, t));
}
float fxEdge(vec2 p) {
  float tl = fxLuma(fxSample(p + fxTexel * vec2(-1., -1.)));
  float t  = fxLuma(fxSample(p + fxTexel * vec2( 0., -1.)));
  float tr = fxLuma(fxSample(p + fxTexel * vec2( 1., -1.)));
  float l  = fxLuma(fxSample(p + fxTexel * vec2(-1.,  0.)));
  float r  = fxLuma(fxSample(p + fxTexel * vec2( 1.,  0.)));
  float bl = fxLuma(fxSample(p + fxTexel * vec2(-1.,  1.)));
  float b  = fxLuma(fxSample(p + fxTexel * vec2( 0.,  1.)));
  float br = fxLuma(fxSample(p + fxTexel * vec2( 1.,  1.)));
  float gx = tr + 2. * r + br - tl - 2. * l - bl;
  float gy = bl + 2. * b + br - tl - 2. * t - tr;
  return clamp(length(vec2(gx, gy)) * 2., 0., 1.);
}
vec3 fxSmooth(vec2 p) {
  vec3 c = fxSample(p);
  vec3 sum = c;
  float weight = 1.;
  for (int i = 0; i < 8; i++) {
    float a = float(i) * .7853982;
    vec3 n = fxSample(p + fxTexel * 3. * vec2(cos(a), sin(a)));
    float w = 1. - smoothstep(.0, .12, distance(n, c));
    sum += n * w;
    weight += w;
  }
  vec3 smoothed = sum / weight;
  return mix(c, smoothed, .75) * 1.03 + vec3(.015);
}
uniform float lookOn;
uniform float retouch; // U message: skin smoothing 0..1, under every effect // U message "4K" colour, ported from the procam camera's ENHANCED look

vec3 lkRgb2hsv(vec3 c) {
  vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
  vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
  vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
  float d = q.x - min(q.w, q.y);
  return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + 1e-4)), d / (q.x + 1e-4), q.x);
}
vec3 lkHsv2rgb(vec3 c) {
  vec3 p = abs(fract(c.xxx + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
  return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}
float lkLuma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
vec3 look4k(vec3 c0, vec2 p) {
  // (no chroma denoise here: the camera's own AI / 3D NR does it, and the taps cost the 60 fps encoder)
  const float skinHue = 15.0 / 360.0;
  vec3 c = c0 * vec3(1.03, 1.0, 0.95);
  c = pow(clamp(c, 0.0, 1.0), vec3(0.96));
  float l = lkLuma(c);
  vec3 hsv = lkRgb2hsv(c);
  float chroma = hsv.y;
  float k = 1.3 * (1.0 + 0.15 * (1.0 - chroma) * smoothstep(0.05, 0.14, chroma));
  float dr = hsv.x - floor(hsv.x + 0.5);
  float wr = (1.0 - smoothstep(0.05, 0.11, abs(dr))) * smoothstep(0.35, 0.55, chroma);
  k *= mix(1.0, 0.85, wr);
  float dm = hsv.x - 0.965;
  dm = dm - floor(dm + 0.5);
  float wm = (1.0 - smoothstep(0.08, 0.13, abs(dm))) * smoothstep(0.06, 0.12, chroma);
  k = mix(k, 1.0, wm);
  float lipPull = wm * (1.0 - smoothstep(0.48, 0.64, chroma)) * step(dm, 0.02);
  float dh = hsv.x - skinHue;
  dh = dh - floor(dh + 0.5);
  float w = dh < 0.0 ? 1.0 - smoothstep(0.075, 0.125, -dh) : 1.0 - smoothstep(0.025, 0.045, dh);
  w *= clamp((0.72 - chroma) / 0.22, 0.0, 1.0) * smoothstep(0.12, 0.22, chroma);
  k = mix(k, 1.0, w);
  k = mix(1.0, k, smoothstep(0.03, 0.18, l));
  k = mix(1.0, k, smoothstep(0.04, 0.14, chroma));
  c = clamp(mix(vec3(l), c, k), 0.0, 1.0);
  vec3 oo = lkRgb2hsv(c);
  float od = oo.x - skinHue;
  od = od - floor(od + 0.5);
  oo.x = fract(oo.x - od * 0.62 * w);
  float dl = oo.x - 0.02;
  dl = dl - floor(dl + 0.5);
  oo.x = fract(oo.x - dl * 0.55 * lipPull * (1.0 - w));
  oo.y = mix(oo.y, min(oo.y * 1.2, 0.46), w);
  return lkHsv2rgb(oo);
}
// retouch: an edge-preserving blur over skin tones only (eyes, brows, lips and hair keep their detail),
// a touch of glow and an even tone; the radius is in screen space so it looks the same at any resolution
void rtTap(vec2 p, vec2 o, vec3 c, inout vec3 sum, inout float wsum) {
  vec3 s = fxAt(p, fxS() + o);
  float w = max(0.0, 1.0 - length(s - c) * 5.0);
  sum += s * w;
  wsum += w;
}
vec3 retouched(vec2 p) {
  vec3 c = texture2D(sTexture, p).rgb;
  if (retouch < 0.01) return c;
  float cb = -0.1687 * c.r - 0.3313 * c.g + 0.5 * c.b;
  float cr = 0.5 * c.r - 0.4187 * c.g - 0.0813 * c.b;
  float skin = smoothstep(-0.26, -0.17, cb) * (1.0 - smoothstep(0.0, 0.06, cb))
             * smoothstep(-0.01, 0.04, cr) * (1.0 - smoothstep(0.19, 0.25, cr));
  if (skin < 0.01) return c;
  vec2 r = vec2(0.0055 / fxAspect(), 0.0055) * (0.6 + 0.6 * retouch);
  vec3 sum = c;
  float wsum = 1.0;
  rtTap(p, vec2(r.x, 0.0), c, sum, wsum);
  rtTap(p, vec2(-r.x, 0.0), c, sum, wsum);
  rtTap(p, vec2(0.0, r.y), c, sum, wsum);
  rtTap(p, vec2(0.0, -r.y), c, sum, wsum);
  rtTap(p, r * 0.7071, c, sum, wsum);
  rtTap(p, -r * 0.7071, c, sum, wsum);
  rtTap(p, vec2(r.x, -r.y) * 0.7071, c, sum, wsum);
  rtTap(p, vec2(-r.x, r.y) * 0.7071, c, sum, wsum);
  rtTap(p, vec2(2.0 * r.x, 0.0), c, sum, wsum);
  rtTap(p, vec2(-2.0 * r.x, 0.0), c, sum, wsum);
  rtTap(p, vec2(0.0, 2.0 * r.y), c, sum, wsum);
  rtTap(p, vec2(0.0, -2.0 * r.y), c, sum, wsum);
  vec3 smoothed = sum / wsum;
  float k = skin * retouch;
  c = mix(c, smoothed, k * 0.9);
  // soft glow and a slightly brighter, more even skin
  c = mix(c, vec3(1.0) - (vec3(1.0) - c) * (vec3(1.0) - c), k * 0.22);
  float l = dot(c, vec3(0.299, 0.587, 0.114));
  c = mix(c, mix(vec3(l), c, 0.9), k * 0.3);
  return clamp(c, 0.0, 1.0);
}
vec4 fx(vec2 p) {
  if (lensOn > .5) {
    return vec4(texture2D(lensFrame, gl_FragCoord.xy / lensViewport).rgb, 1.);
  }
  float type = floor(fxType + .5);
  if (type < .5) {
    vec3 base = retouched(p);
    if (lookOn > .5) {
      return vec4(clamp(look4k(base, p), 0., 1.), 1.);
    }
    return vec4(base, 1.);
  }
  vec3 c;
  if (type < 1.5) { // beauty
    c = fxSmooth(p);
  } else if (type < 2.5) { // vivid
    c = retouched(p);
    c = mix(vec3(fxLuma(c)), c, 1.45);
    c = (c - .5) * 1.12 + .5;
  } else if (type < 3.5) { // noir
    float l = fxLuma(retouched(p));
    l = smoothstep(.05, .95, l);
    c = vec3(l);
  } else if (type < 4.5) { // vintage
    c = retouched(p);
    c = vec3(dot(c, vec3(.393, .769, .189)), dot(c, vec3(.349, .686, .168)), dot(c, vec3(.272, .534, .131)));
    c = mix(c, vec3(.92, .82, .65), .12);
    c *= 1. - .35 * length((p - .5) * 1.3);
    c += (fxRand(gl_FragCoord.xy + fxTime) - .5) * .06;
  } else if (type < 5.5) { // glitch
    float band = floor(gl_FragCoord.y / 18.);
    float t = floor(fxTime * 12.);
    float jitter = step(.82, fxRand(vec2(band, t))) * (fxRand(vec2(t, band)) - .5) * .08;
    vec2 q = p + vec2(jitter, 0.);
    float shift = .006 + .01 * step(.9, fxRand(vec2(t)));
    c = vec3(fxSample(q + vec2(shift, 0.)).r, fxSample(q).g, fxSample(q - vec2(shift, 0.)).b);
  } else if (type < 6.5) { // vhs
    float wobble = sin(gl_FragCoord.y * .05 + fxTime * 6.) * .0015;
    vec2 q = p + vec2(wobble, 0.);
    c = vec3(fxSample(q + vec2(.004, 0.)).r, fxSample(q).g, fxSample(q - vec2(.004, 0.)).b);
    c *= .88 + .12 * sin(gl_FragCoord.y * 1.6);
    c += (fxRand(gl_FragCoord.xy * .5 + fxTime) - .5) * .09;
    c = mix(vec3(fxLuma(c)), c, .8) * vec3(1.05, .98, 1.08);
  } else if (type < 7.5) { // neon
    float e = fxEdge(p);
    vec3 tint = .5 + .5 * cos(fxTime * 1.5 + p.xyx * 6. + vec3(0., 2., 4.));
    c = fxSample(p) * .15 + tint * e * 1.6;
  } else if (type < 8.5) { // comic
    vec3 base = floor(fxSample(p) * 5.) / 5.;
    c = mix(base, vec3(0.), step(.35, fxEdge(p)));
  } else if (type < 9.5) { // fisheye
    vec2 asp = vec2(fxAspect(), 1.);
    vec2 d = (fxS() - .5) * asp;
    float r = length(d);
    c = fxAt(p, .5 + d * (.5 + 1.6 * r * r) / asp);
  } else if (type < 10.5) { // kaleidoscope
    vec2 asp = vec2(fxAspect(), 1.);
    vec2 d = (fxS() - .5) * asp;
    float a = atan(d.y, d.x) + fxTime * .2;
    float seg = 1.0471976;
    a = abs(mod(a, seg) - seg * .5);
    c = fxAt(p, .5 + vec2(cos(a), sin(a)) * length(d) / asp);
  } else if (type < 11.5) { // quad mirror
    c = fxSample(.5 - abs(p - .5));
  } else if (type < 12.5) { // pixel
    vec2 cell = fxTexel * 18.;
    c = fxSample((floor(p / cell) + .5) * cell);
  } else if (type < 13.5) { // pulse zoom
    float beat = pow(abs(sin(fxTime * 3.1416 * 1.1)), 8.);
    vec2 d = (p - .5) / (1. + .12 * beat);
    c = vec3(fxSample(.5 + d * 1.01).r, fxSample(.5 + d).g, fxSample(.5 + d * .99).b);
  } else if (type < 14.5) { // duotone
    float l = fxLuma(fxSample(p));
    c = mix(vec3(.16, .05, .45), vec3(1., .45, .62), smoothstep(.1, .9, l));
  } else if (type < 15.5) { // thermal
    float l = fxLuma(fxSample(p));
    c = clamp(vec3(1.5 * l - .2, sin(l * 3.1416), 1. - 1.8 * l + .4 * step(.8, l)), 0., 1.);
  } else if (type < 16.5) { // twin: the left half mirrored
    vec2 t = fxS();
    t.x = t.x > .5 ? 1. - t.x : t.x;
    c = fxAt(p, t);
  } else if (type < 17.5) { // swirl
    vec2 asp = vec2(fxAspect(), 1.);
    vec2 d = (fxS() - .5) * asp;
    float r = length(d);
    float a = 3.2 * smoothstep(.55, 0., r) * (.8 + .2 * sin(fxTime * 1.5));
    float cs = cos(a), sn = sin(a);
    c = fxAt(p, .5 + vec2(d.x * cs - d.y * sn, d.x * sn + d.y * cs) / asp);
  } else if (type < 18.5) { // pinch
    vec2 asp = vec2(fxAspect(), 1.);
    vec2 d = (fxS() - .5) * asp;
    float r = length(d);
    c = fxAt(p, .5 + d * mix(1.9, 1., smoothstep(0., .55, r)) / asp);
  } else if (type < 19.5) { // wave
    vec2 s = fxS();
    c = fxAt(p, s + vec2(sin(s.y * 22. + fxTime * 4.) * .014, sin(s.x * 16. + fxTime * 3.) * .01));
  } else if (type < 20.5) { // 4 frames
    c = fxAt(p, fract(fxS() * 2.));
  } else if (type < 21.5) { // 9 frames
    c = fxAt(p, fract(fxS() * 3.));
  } else if (type < 22.5) { // 3D anaglyph
    vec2 s = fxS();
    c = vec3(fxAt(p, s + vec2(.012, 0.)).r, fxAt(p, s - vec2(.012, 0.)).gb);
  } else if (type < 23.5) { // rainbow
    vec3 hsv = lkRgb2hsv(retouched(p));
    hsv.x = fract(hsv.x + fxTime * .15 + fxS().y * .6);
    hsv.y = max(hsv.y, .55);
    c = lkHsv2rgb(hsv);
  } else if (type < 24.5) { // pencil sketch
    float e = fxEdge(p);
    float shade = fxLuma(fxSample(p));
    c = vec3(.97, .96, .92) * (1. - e * 1.3) * mix(.8, 1., smoothstep(.15, .6, shade));
  } else if (type < 25.5) { // stretch: tall faces
    vec2 s = fxS();
    c = fxAt(p, vec2(s.x, .5 + (s.y - .5) * .62));
  } else if (type < 26.5) { // shake
    float t = fxTime;
    vec2 o = vec2(sin(t * 37.), cos(t * 29.)) * .014 * step(.45, fract(t * 1.3));
    vec2 s = .5 + (fxS() - .5) * .93 + o;
    c = vec3(fxAt(p, s + vec2(.006, 0.)).r, fxAt(p, s).g, fxAt(p, s - vec2(.006, 0.)).b);
  } else if (type < 27.5) { // x-ray
    float l = fxLuma(fxSample(p));
    c = vec3(smoothstep(.95, .1, l)) * vec3(.72, .9, 1.05);
  } else if (type < 28.5) { // tiny planet: the bottom of the frame wrapped into a globe
    vec2 d = (fxS() - .5) * vec2(fxAspect(), 1.);
    float a = atan(d.y, d.x);
    c = fxAt(p, vec2(fract(a / 6.2831853 + .75 + fxTime * .02), clamp(length(d) * 1.9, 0., 1.)));
  } else if (type < 29.5) { // reflection: the top half over water
    vec2 s = fxS();
    if (s.y < .5) {
      s.y = 1. - s.y + sin(s.y * 90. + fxTime * 3.) * .004;
      c = fxAt(p, s) * vec3(.72, .82, .95);
    } else {
      c = fxAt(p, s);
    }
  } else {
    c = fxSample(p);
  }
  return vec4(clamp(c, 0., 1.), 1.);
}

float modI(float a,float b) {
  return floor(a-floor((a+0.5)/b)*b+0.5);
}
bool eq(float a, float b) {
  return abs(a - b) < .1;
}
bool eq(float a, float b, float b2) {
  return abs(a - b) < .1 || abs(a - b2) < .1;
}
float box(vec2 position, vec2 halfSize, float cornerRadius) {
  position = abs(position) - halfSize + cornerRadius;
  return length(max(position, 0.0)) + min(max(position.x, position.y), 0.0) - cornerRadius;
}
float star(in vec2 p, in float r) {
  const vec2  acs = vec2(.9659258, .258819);
  const vec2  ecs = vec2(.8090169, .5877852);
  float bn = mod(atan(p.x,p.y),.52359876)-.26179938;
  p = length(p)*vec2(cos(bn),abs(sin(bn))) - r*acs;
  p += ecs*clamp( -dot(p,ecs), 0.0, r*acs.y/ecs.y);
  return length(p)*sign(p.x);
}
float opSmoothUnion(float d1, float d2, float k) {
  float h = max(k-abs(d1-d2),0.0);
  return min(d1, d2) - h*h*0.25/k;
}
float scene() {
  vec2 p = (auv - vec2(.5)) * vec2(1., pixelWH.x / pixelWH.y);
  vec2 r = .5 * vec2(1., pixelWH.x / pixelWH.y) * scale;
  float R = min(r.x, r.y), rr = roundRadius / pixelWH.y;
  float a = modI(shapeFrom, 3.), b = modI(shapeTo, 3.);
  float boxSDF = box(
    p,
    mix(eq(a, 2.)     ? r : vec2(R), eq(b, 2.)     ? r : vec2(R), shapeT),
    mix(eq(a, 0., 3.) ? R : rr,      eq(b, 0., 3.) ? R : rr,      shapeT)
  ) * pixelWH.x;
  if (eq(3., a, b)) {
    float starSDF = opSmoothUnion(box(p, vec2(R * .78), R), star(p, R * .78), .25) * pixelWH.x;
    float starA = eq(a, 3.) ? 1. - shapeT : 0.;
    float starB = eq(b, 3.) ? shapeT : 0.;
    return mix(boxSDF, starSDF, starA + starB);
  } else {
    return boxSDF;
  }
}
vec4 makeblur() {
  vec2 S = 4. * vec2(1., pixelWH.x / pixelWH.y);
  vec2 st = fract(uv * S);
  st=st*st*(3.0-2.0*st);
  vec2 u = floor(uv * S) / S;
  return mix(
    mix(texture2D(sTexture, u + vec2(0., 0.) / S), texture2D(sTexture, u + vec2(1., 0.) / S), st.x),
    mix(texture2D(sTexture, u + vec2(0., 1.) / S), texture2D(sTexture, u + vec2(1., 1.) / S), st.x),
    st.y
  );
}
vec4 program() {
  if (scale <= 0.)
    return vec4(0.);
  float dalpha = clamp(1. - scene(), 0., 2.) / 2. * alpha;
  if (dalpha <= 0.)
    return vec4(0.);

  if (blur >= 1.)
    return makeblur();
  else if (blur <= 0.)
    return fx(uv) * vec4(1., 1., 1., dalpha);
  else
    return mix(fx(uv), makeblur(), blur) * vec4(1., 1., 1., dalpha);
}
void main() {
#ifdef FX_DERIV
  fxJx = dFdx(uv) * lensViewport.x;
  fxJy = dFdy(uv) * lensViewport.y;
#endif
  gl_FragColor = dual < .5 ? fx(uv) : program();
}
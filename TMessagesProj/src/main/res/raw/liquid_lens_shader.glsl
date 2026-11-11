uniform shader img;

uniform float2 center;
uniform float2 size;
uniform float radius;
uniform float thickness;
uniform float strength;
uniform float aberration;
uniform float light;

// a clear convex lens for sharp content: float precision and an analytic normal keep the edge smooth, and the warp stays
// below the fold, so the content never mirrors
half4 main(in float2 p) {
  float2 q = p - center;
  float2 a = abs(q) - size + radius;
  float sd = length(max(a, 0.0)) + min(max(a.x, a.y), 0.0) - radius;
  if (sd >= 0.0) {
    return img.eval(p);
  }

  float2 n;
  if (a.x > 0.0 && a.y > 0.0) {
    n = normalize(a);
  } else if (a.x > a.y) {
    n = float2(1.0, 0.0);
  } else {
    n = float2(0.0, 1.0);
  }
  n *= sign(q);

  float edge = 1.0 - clamp(-sd / thickness, 0.0, 1.0);
  float warp = edge * edge * sqrt(edge);
  float2 uv = p - n * strength * warp;
  float2 split = n * aberration * warp;

  half4 c = img.eval(uv);
  half r = img.eval(uv - split).r;
  half b = img.eval(uv + split).b;
  c.r = r;
  c.b = b;

  // the rim catches the light from above, and is dimmer on the far side
  float lit = light * edge * edge * edge * (0.55 + 0.45 * dot(n, float2(-0.3, -0.95)));
  half glow = half(clamp(lit, 0.0, 1.0));
  return half4(c.rgb + (1.0 - c.rgb) * glow, c.a + (1.0 - c.a) * glow);
}

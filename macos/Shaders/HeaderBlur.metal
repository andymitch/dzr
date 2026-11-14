#include <metal_stdlib>
#include <SwiftUI/SwiftUI_Metal.h>
using namespace metal;

[[ stitchable ]] half4 headerBlur(float2 pos, SwiftUI::Layer layer, float originY, float maxRadius, float top, float height) {
    float t = clamp(1.0 - (pos.y + originY - top) / height, 0.0, 1.0);
    float r = maxRadius * t * t;
    if (r < 0.5) return layer.sample(pos);
    half4 sum = 0;
    float total = 0;
    const int n = 32;
    for (int i = 0; i < n; i++) {
        float f = (float(i) + 0.5) / float(n);
        float a = float(i) * 2.39996323;
        float w = 1.0 - 0.5 * f;
        sum += layer.sample(pos + float2(cos(a), sin(a)) * sqrt(f) * r) * half(w);
        total += w;
    }
    return sum / half(total);
}

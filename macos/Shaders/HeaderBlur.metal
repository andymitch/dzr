#include <metal_stdlib>
#include <SwiftUI/SwiftUI_Metal.h>
using namespace metal;

[[ stitchable ]] half4 headerBlur(float2 pos, SwiftUI::Layer layer, float originY, float maxRadius, float top, float height) {
    float t = 1.0 - smoothstep(0.0, 1.0, (pos.y + originY - top) / height);
    float r = maxRadius * t;
    if (r < 0.3) return layer.sample(pos);
    const int k = 6;
    float step = r / float(k);
    half4 sum = 0;
    float total = 0;
    for (int y = -k; y <= k; y++) {
        for (int x = -k; x <= k; x++) {
            float w = exp(-float(x * x + y * y) / 18.0);
            sum += layer.sample(pos + float2(x, y) * step) * half(w);
            total += w;
        }
    }
    return sum / half(total);
}

// Prepended to every compute shader (after #version). GLSL ES 3.10.
precision highp float;
precision highp int;
precision highp image2D;
precision highp sampler2D;
layout(local_size_x = 8, local_size_y = 8) in;

const float PI = 3.14159265;

float lin_to_srgb(float c) { return c <= 0.0031308 ? c * 12.92 : 1.055 * pow(c, 1.0 / 2.4) - 0.055; }
vec3 to_lin(vec3 c) {
    c = max(c, 0.0);
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
}
vec3 to_srgb(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return vec3(lin_to_srgb(c.r), lin_to_srgb(c.g), lin_to_srgb(c.b));
}
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

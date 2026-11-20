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

// Shift-lens perspective (a keystone correction): the picture plane turned
// about its horizontal axis (vertical rise / fall) and its vertical axis
// (sideways shift), seen from the camera's focal length. Maps an output pixel
// to where it comes from in the camera's picture, as uv 0...1.
//   warp = (tan ax, cos ax, tan ay, cos ay), warpFZ = (focal length px, zoom)
vec2 shift_uv(vec2 p, vec2 size, vec4 warp, vec2 warpFZ) {
    vec2 c = (p - 0.5 * size) / warpFZ.y;
    float d = warpFZ.x;
    float l1 = d / (d - c.y * warp.x);
    c = vec2(l1 * c.x, l1 * c.y / warp.y);
    float l2 = d / (d - c.x * warp.z);
    c = vec2(l2 * c.x / warp.w, l2 * c.y);
    return (c + 0.5 * size) / size;
}

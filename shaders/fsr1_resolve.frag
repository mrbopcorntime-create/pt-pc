#version 460
// The upscale resolve (upscale_resolve.frag) for AMD FSR 1 (MIT, shaders/fsr1/LICENSE-FidelityFX-FSR.txt): the colour is
// fsr1_easu.frag's output, sharpened with robust contrast-adaptive sharpening (RCAS) when pass.f2.y is set, and mapped back
// from x/(1+x) to the scene's HDR values. Depth, velocity and the reflections are taken as upscale_resolve.frag takes them.
#extension GL_GOOGLE_include_directive : require
#include "common.glsl"
#include "reflection_blend.glsl"

#define IMG_UPSCALED 43

layout(push_constant) uniform PassPush {
    uvec4 ids;
    vec4 f0;
    vec4 f1;
    vec4 f2;  // x: RCAS sharpness in stops (0 is the strongest), y: 1 to sharpen
    mat4 m;
} pass;

layout(location = 0) in vec2 in_uv;
layout(location = 0) out vec4 out_color;
layout(location = 1) out vec4 out_depth;
layout(location = 2) out vec4 out_velocity;

#define A_GPU 1
#define A_GLSL 1
#include "fsr1/ffx_a.h"

AF4 FsrRcasLoadF(ASU2 p) { return texelFetch(sampler2D(images[IMG_UPSCALED], samplers[SMP_POINT_CLAMP]), clamp(ivec2(p), ivec2(0), ivec2(ImgSize(IMG_UPSCALED)) - 1), 0); }
void FsrRcasInputF(inout AF1 r, inout AF1 g, inout AF1 b) {}

#define FSR_RCAS_F 1
#include "fsr1/ffx_fsr1.h"

vec3 FromPerceptual(vec3 c) { return c / max(vec3(1.0) - c, vec3(1.0 / 1024.0)); }

vec4 Reflection(vec2 render_uv) {
    ivec2 size = ivec2(pass.f1.xy);
    vec2 texel = render_uv * pass.f1.xy - 0.5;
    ivec2 p0 = ivec2(floor(texel));
    vec2 f = texel - vec2(p0);
    vec4 sum = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
        ivec2 o = ivec2(i & 1, i >> 1);
        vec4 s = ImgFetch(pass.ids.z, clamp(p0 + o, ivec2(0), size - 1));
        float w = (o.x == 1 ? f.x : 1.0 - f.x) * (o.y == 1 ? f.y : 1.0 - f.y);
        sum += s.a > 0.0 ? s * w : vec4(0.0);
    }
    return sum;
}

void main() {
    vec2 uv = gl_FragCoord.xy * pass.f0.zw;
    vec2 render_uv = uv + pass.f0.xy;
    vec4 source = Img(IMG_HDR, SMP_LINEAR_CLAMP, render_uv);
    vec3 perceptual;
    if (pass.f2.y > 0.5) {
        AU4 con = AU4(AU1_AF1(exp2(-pass.f2.x)), 0u, 0u, 0u);
        AF1 r, g, b;
        FsrRcasF(r, g, b, AU2(gl_FragCoord.xy), con);
        perceptual = vec3(r, g, b);
    } else {
        perceptual = ImgFetch(IMG_UPSCALED, ivec2(gl_FragCoord.xy)).rgb;
    }
    vec3 color = FromPerceptual(clamp(perceptual, vec3(0.0), vec3(1.0)));
    if (pass.ids.z != 0u) {
        vec4 reflection = Reflection(uv);
        if (reflection.a > 0.0) {
            color = SrgbDecode(ReflectionBlendBase(color, pass.f1.w > 0.5) * (1.0 - reflection.a) + reflection.rgb);
        }
    }
    ivec2 size = ivec2(pass.f1.xy);
    ivec2 nearest = clamp(ivec2(floor(render_uv * pass.f1.xy)), ivec2(0), size - 1);
    out_color = vec4(color, source.a);
    out_depth = vec4(ImgFetch(IMG_DEPTH, nearest).x, 0.0, 0.0, 0.0);
    out_velocity = ImgFetch(pass.ids.y, nearest);
}

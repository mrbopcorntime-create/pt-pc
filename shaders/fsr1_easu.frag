#version 460
// AMD FidelityFX Super Resolution 1 (MIT, shaders/fsr1/LICENSE-FidelityFX-FSR.txt), its edge-adaptive spatial upsampling (EASU):
// the lit HDR scene at the render size into the output-sized upscale target (docs/upscaling.md, FSR 1). FSR 1 expects
// perceptual values; every sample is tone mapped with the invertible x/(1+x) here and fsr1_resolve.frag maps it back after
// its sharpening pass (RCAS).
#extension GL_GOOGLE_include_directive : require
#include "common.glsl"

layout(push_constant) uniform PassPush {
    uvec4 ids;
    vec4 f0;  // render width, height, output width, height
    vec4 f1;
    vec4 f2;
    mat4 m;
} pass;

layout(location = 0) in vec2 in_uv;
layout(location = 0) out vec4 out_color;

#define A_GPU 1
#define A_GLSL 1
#include "fsr1/ffx_a.h"

AF4 Perceptual(AF4 c) { return c / (1.0 + max(c, AF4_(0.0))); }

AF4 FsrEasuRF(AF2 p) { return Perceptual(textureGather(sampler2D(images[IMG_HDR], samplers[SMP_LINEAR_CLAMP]), p, 0)); }
AF4 FsrEasuGF(AF2 p) { return Perceptual(textureGather(sampler2D(images[IMG_HDR], samplers[SMP_LINEAR_CLAMP]), p, 1)); }
AF4 FsrEasuBF(AF2 p) { return Perceptual(textureGather(sampler2D(images[IMG_HDR], samplers[SMP_LINEAR_CLAMP]), p, 2)); }

#define FSR_EASU_F 1
#include "fsr1/ffx_fsr1.h"

void main() {
    // FsrEasuCon (ffx_fsr1.h, CPU side) for an input that fills its image
    const AF2 input_size = pass.f0.xy;
    const AF2 output_size = pass.f0.zw;
    const AF2 rcp_input = 1.0 / input_size;
    AU4 con0 = AU4(AU1_AF1(input_size.x / output_size.x), AU1_AF1(input_size.y / output_size.y),
                   AU1_AF1(0.5 * input_size.x / output_size.x - 0.5), AU1_AF1(0.5 * input_size.y / output_size.y - 0.5));
    AU4 con1 = AU4(AU1_AF1(rcp_input.x), AU1_AF1(rcp_input.y), AU1_AF1(1.0 * rcp_input.x), AU1_AF1(-1.0 * rcp_input.y));
    AU4 con2 = AU4(AU1_AF1(-1.0 * rcp_input.x), AU1_AF1(2.0 * rcp_input.y), AU1_AF1(1.0 * rcp_input.x), AU1_AF1(2.0 * rcp_input.y));
    AU4 con3 = AU4(AU1_AF1(0.0 * rcp_input.x), AU1_AF1(4.0 * rcp_input.y), 0u, 0u);
    AF3 color;
    FsrEasuF(color, AU2(gl_FragCoord.xy), con0, con1, con2, con3);
    out_color = vec4(color, 1.0);
}

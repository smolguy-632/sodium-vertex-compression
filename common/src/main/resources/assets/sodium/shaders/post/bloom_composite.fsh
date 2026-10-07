#version 330
#extension GL_ARB_separate_shader_objects : require

// Final FX pass: adds the blurred bloom over the scene and optionally tonemaps the result.
//
// The main target is RGBA8_UNORM, so the scene arrives here already display-referred and
// clamped to 0..1. Bloom is therefore additive in that range rather than a highlight
// recovery pass, and tonemapping is off by default: running a curve over an image that has
// already been tonemapped would darken the midtones and kill the highlight rolloff.

uniform sampler2D InSampler;    // the scene, sampled from the main target
uniform sampler2D BloomSampler; // the blurred bloom, sampled at quarter resolution

layout(std140) uniform FxParams {
    // x = threshold, y = intensity, z = radius, w = exposure
    vec4 u_Params;
    vec4 u_Step;
    // x = tonemap enabled (0 or 1), y = tonemap mode (0 Reinhard, 1 ACES, 2 Filmic)
    vec4 u_Tone;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

vec3 reinhard(vec3 color) {
    return color / (1.0 + color);
}

vec3 aces(vec3 color) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;

    return clamp((color * (a * color + b)) / (color * (c * color + d) + e), 0.0, 1.0);
}

vec3 filmic(vec3 color) {
    // Hable's Uncharted 2 curve, rebased so that it lands in 0..1 instead of 0..3.924.
    const float A = 0.15;
    const float B = 0.50;
    const float C = 0.10;
    const float D = 0.20;
    const float E = 0.02;
    const float F = 0.30;

    return clamp(((color * (A * color + C * B) + D * E) / (color * (A * color + B) + D * F)) - E / F, 0.0, 1.0);
}

void main() {
    vec4 base = texture(InSampler, texCoord);
    vec3 bloom = texture(BloomSampler, texCoord).rgb;

    vec3 color = base.rgb + bloom * u_Params.y;

    if (u_Tone.x > 0.5) {
        color *= u_Params.w;

        int mode = int(u_Tone.y);
        color = mode == 0 ? reinhard(color) : (mode == 2 ? filmic(color) : aces(color));
    }

    // Alpha is passed through untouched: the scene target carries coverage that later
    // passes and the UI read, so writing back the shader's own 1.0 would break it.
    fragColor = vec4(color, base.a);
}
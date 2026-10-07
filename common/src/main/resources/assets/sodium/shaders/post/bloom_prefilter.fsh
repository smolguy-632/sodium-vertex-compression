#version 330
#extension GL_ARB_separate_shader_objects : require

// Downsamples the main target by half and keeps only what is bright enough to bloom.
// Pairing the threshold with the downsample is what makes the result stable: thresholding
// a full-resolution image leaves single-pixel fireflies that the blur then smears into
// visible flicker, while thresholding the already-averaged 4-tap result does not.

uniform sampler2D InSampler;

layout(std140) uniform FxParams {
    // x = threshold (0..1), y = intensity, z = radius, w = exposure
    vec4 u_Params;
    // xy = 1.0 / sourceSize, applied as the half-res downsample step
    vec4 u_Step;
    // x = tonemap enabled, y = tonemap mode
    vec4 u_Tone;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    // Rotated 4-tap box filter: the diagonals cover the four source pixels that map onto
    // this half-res texel without needing a 2x2 in-order fetch.
    vec2 o = u_Step.xy;

    vec3 sum = texture(InSampler, texCoord + vec2(o.x, o.y)).rgb;
    sum += texture(InSampler, texCoord + vec2(-o.x, o.y)).rgb;
    sum += texture(InSampler, texCoord + vec2(o.x, -o.y)).rgb;
    sum += texture(InSampler, texCoord + vec2(-o.x, -o.y)).rgb;

    vec3 color = sum * 0.25;

    // Ramp the contribution in over a band above the threshold instead of using a hard
    // step(). A step makes bloom switch on and off as pixels cross the cutoff, which
    // reads as shimmering edges on moving highlights such as the sun or a torch.
    float brightness = max(color.r, max(color.g, color.b));
    float threshold = u_Params.x;
    float softness = max(threshold * 0.5 + 0.05, 0.05);

    float contribution = smoothstep(threshold, threshold + softness, brightness);

    fragColor = vec4(color * contribution, 1.0);
}
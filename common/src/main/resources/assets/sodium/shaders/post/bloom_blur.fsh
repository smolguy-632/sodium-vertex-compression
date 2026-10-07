#version 330
#extension GL_ARB_separate_shader_objects : require

// Separable Gaussian, run once horizontally and once vertically.
// u_Step.xy carries the direction, so both passes share one pipeline and one shader.

uniform sampler2D InSampler;

layout(std140) uniform FxParams {
    vec4 u_Params;
    vec4 u_Step;
    vec4 u_Tone;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// Offsets and weights for a 9-tap Gaussian collapsed onto 5 bilinear fetches.
// Linear filtering samples the two texels either side of each position for free, so this
// has the same footprint as the 9-tap version at 55% of the texture reads.
const float OFFSETS[3] = float[](0.0, 1.3846153846, 3.2307692308);
const float WEIGHTS[3] = float[](0.2270270270, 0.3162162162, 0.0702702703);

void main() {
    vec2 step = u_Step.xy;

    vec3 color = texture(InSampler, texCoord).rgb * WEIGHTS[0];

    for (int i = 1; i < 3; ++i) {
        vec2 offset = step * OFFSETS[i];
        color += texture(InSampler, texCoord + offset).rgb * WEIGHTS[i];
        color += texture(InSampler, texCoord - offset).rgb * WEIGHTS[i];
    }

    fragColor = vec4(color, 1.0);
}
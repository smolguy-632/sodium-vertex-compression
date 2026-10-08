#version 460 core

#include <sodium:globals.glsl>
#include <sodium:fog.glsl>
#include <sodium:chunk_material.glsl>
#include <sodium:blocklighttest.glsl>
#ifdef NEO_SKY_SHADOWS
#include <sodium:neosky_shadow.glsl>
#endif
#include <minecraft:oit.glsl>

layout(location = 0) in vec4 v_Color; // The interpolated vertex color
layout(location = 1) in vec2 v_TexCoord; // The interpolated block texture coordinates
layout(location = 2) in vec2 v_FragDistance; // The fragment's distance from the camera (cylindrical and spherical)
layout(location = 3) in float fadeFactor;
layout(location = 4) in vec3 v_BtViewPos; // BlockLightTest: view-space position
layout(location = 5) in float v_BtBlockLight; // BlockLightTest: vanilla block-light level, 0..1
#ifdef NEO_SKY_SHADOWS
layout(location = 6) in vec3 v_NeoSkyPos; // NeoSkyCelestia: camera-relative world position
#endif

uniform sampler2D u_BlockTex; // The block texture

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor; // The output fragment for the color framebuffer
#endif

vec4 calculateFinalColor(vec4 color) {
    #ifdef OIT_ACCUMULATE
        color = sampleColorForAccumulation(color);
        vec4 fogColor = vec4(u_FogColor.rgb * color.a, u_FogColor.a);
    #else
        vec4 fogColor = u_FogColor;
    #endif

    #ifdef OIT_ALPHA_ONLY
    float factor = 1.0;
    #else
    float factor = fadeFactor;
    #endif

    return _linearFog(color, v_FragDistance, fogColor, u_EnvironmentFog, u_RenderFog, factor);
}
vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 texelScreenSize) {
    // Convert our UV back up to texel coordinates and find out how far over we are from the center of each pixel
    vec2 uvTexelCoords = uv / pixelSize;
    vec2 texelCenter = round(uvTexelCoords) - 0.5f;
    vec2 texelOffset = uvTexelCoords - texelCenter;

    // Move our offset closer to the texel center based on texel size on screen
    texelOffset = (texelOffset - 0.5f) * pixelSize / texelScreenSize + 0.5f;
    texelOffset = clamp(texelOffset, 0.0f, 1.0f);

    uv = (texelCenter + texelOffset) * pixelSize;
    return textureGrad(source, uv, du, dv);
}

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    return sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
}

// Rotated Grid Super-Sampling
vec4 sampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);

    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);

    float minPixelSize = min(pixelSize.x, pixelSize.y);

    float transitionStart = minPixelSize * 1.0;
    float transitionEnd = minPixelSize * 2.0;
    float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);

    float duLength = length(du);
    float dvLength = length(dv);
    float minDerivative = min(duLength, dvLength);
    float maxDerivative = max(duLength, dvLength);

    float effectiveDerivative = sqrt(minDerivative * maxDerivative);

    float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));

    const vec2 offsets[4] = vec2[](
    vec2(0.125, 0.375),
    vec2(-0.125, -0.375),
    vec2(0.375, -0.125),
    vec2(-0.375, 0.125)
    );

    vec4 rgssColor = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
        vec2 sampleUV = uv + offsets[i] * pixelSize;
        rgssColor += textureLod(source, sampleUV, mipLevelExact);
    }
    rgssColor *= 0.25;

    vec4 nearestColor = sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);

    return mix(nearestColor, rgssColor, blendFactor);
}

void main() {
    vec4 color = u_UseRGSS ? sampleRGSS(u_BlockTex, v_TexCoord, u_TexelSize) : sampleNearest(u_BlockTex, v_TexCoord, u_TexelSize);
    color *= v_Color; // Apply per-vertex color modulator

#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif

    #ifdef OIT_ALPHA_ONLY
    // This pass only contributes coverage. The alpha colour target discards rgb, so running the
    // emitter loop here would pay for 12 light evaluations that are thrown away.
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    // BlockLightTest. v_Color already carries the full vanilla sky+block lightmap, so the emitter
    // light is added on top of it rather than replacing it; see bt_local_light for why.
    vec3 btLight = bt_local_light(v_BtBlockLight, v_BtViewPos, bt_view_normal(v_BtViewPos));
    color.rgb *= min(vec3(1.0) + btLight, vec3(1.6));

    #ifdef NEO_SKY_SHADOWS
    // NeoSkyCelestia. The visibility term only attenuates the *direct* light: shadowed surfaces keep
    // the ambient floor rather than going black, which is what stops shadow interiors reading as
    // holes in the world. v_Color already carries the vanilla lightmap, so this scales the whole
    // term rather than replacing it.
    int neoskySamples = int(u_NeoSkyBlend.z);
    float neoskyVisibility = neosky_shadow_visibility(v_NeoSkyPos, neoskySamples);

    // Remap 0..1 visibility onto a darkening factor: a fully shadowed surface keeps `ambient`
    // worth of its previous brightness rather than dropping to zero.
    float neoskyLightFactor = mix(1.0 - u_NeoSkyAmbient.a, 1.0, neoskyVisibility);
    color.rgb *= neoskyLightFactor;
    #endif

    fragColor = calculateFinalColor(color);
    #endif
}

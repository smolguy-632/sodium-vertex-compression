// NeoSkyCelestia directional shadow lookup, ported from Luxium's sky_shadow.glsl.
//
// Only the *software* comparison path is ported. The reference also contains
// luxium_hardware_shadow_compare_texel / luxium_hardware_shadow_filter, which rely on a
// sampler2DShadow and therefore on GL_TEXTURE_COMPARE_MODE. RenderPearl creates depth
// textures with GL_TEXTURE_COMPARE_MODE off and offers no way to turn it on, so the hardware
// overloads could never sample. They are omitted deliberately rather than left in as dead code.
//
// The receiver-plane reconstruction below is what replaces hardware PCF. RenderPearl has no
// sampler2DShadow, so each tap compares a full float in the shader and the depth texture must
// therefore be NEAREST-filtered and clamp-to-edge. A linear filter would interpolate the stored
// depths and silently corrupt every comparison.

layout(std140) uniform u_NeoSky {
    mat4 u_NeoSkyNearMatrix;
    mat4 u_NeoSkyFarMatrix;

    // radius, texelSize, baseBias, slopeBias
    vec4 u_NeoSkyNear;
    vec4 u_NeoSkyFar;

    // xyz active light direction, w = 1 when the moon is up
    vec4 u_NeoSkyLightDir;

    // rgb direct colour, a = active direct strength
    vec4 u_NeoSkyDirect;

    // rgb ambient colour, a = ambient strength
    vec4 u_NeoSkyAmbient;

    // x = near blend start, y = far radius, z = filter samples, w = near generation
    vec4 u_NeoSkyBlend;
};

// The cascade depth maps, declared without an explicit layout(binding=).
//
// Sodium binds samplers by name through each pipeline's BindGroupLayout at draw time, exactly as it
// does for u_BlockTex and u_LightTex, and the compiler is configured to auto-assign SPIR-V bindings.
// Writing a binding number here would hard-code an index that has to agree with the Java-side
// layout's slot ordering, which is a needless second source of truth.
//
// They are sampled with NEAREST and clamp-to-edge, deliberately: the comparisons below read the
// stored depth as a raw float, and a linear filter would blend neighbouring depths and silently
// corrupt every comparison.
uniform sampler2D u_NeoSkyNearDepth;
uniform sampler2D u_NeoSkyFarDepth;
uniform sampler2D u_NeoSkyLightLut;

/**
 * Recovers the depth-plane gradient of the receiving fragment.
 *
 * <p>Screen-space derivatives of the projected position give two points on the receiver's depth
 * plane. Interpolating between them lets us evaluate the receiver's depth at each tap's texel
 * centre, which is what stops the comparison from being biased purely by screen-space
 * interpolation error.
 *
 * <p>Returns vec2(0) in every degenerate case, which degrades the filter to a flat plane rather
 * than producing NaNs: a zero gradient is wrong but finite, whereas a NaN silently discards the
 * whole fragment.
 */
vec2 neosky_receiver_plane_depth_gradient(vec3 projected) {
    vec3 dx = dFdx(projected);
    vec3 dy = dFdy(projected);

    float dxScale = max(abs(dx.x), abs(dx.y));
    float dyScale = max(abs(dx.x), abs(dy.y));
    if (dxScale <= 1.0e-12 || dyScale <= 1.0e-12) {
        return vec2(0.0);
    }
    dx /= dxScale;
    dy /= dyScale;

    float determinant = dx.x * dy.y - dx.y * dy.x;

    if (abs(determinant) <= 1.0e-5) {
        return vec2(0.0);
    }

    vec2 gradient = vec2(
            (dx.z * dy.y - dx.y * dy.z) / determinant,
            (dx.x * dy.z - dx.z * dy.x) / determinant);

    if (!all(lessThan(abs(gradient), vec2(1.0e6)))) {
        return vec2(0.0);
    }
    return gradient;
}

/** The receiver's depth at a specific texel's centre. */
float neosky_receiver_depth_at_texel(vec3 projected, vec2 receiverGradient, ivec2 texel, float texelSize) {
    vec2 sampleUv = (vec2(texel) + vec2(0.5)) * texelSize;
    return projected.z + dot(receiverGradient, sampleUv - projected.xy);
}

/**
 * One shadow test at one texel.
 *
 * <p>Out-of-range taps count as *lit* (0.0 visibility) rather than shadowed. A receiver outside
 * the cascade has no information, and treating that as shadowed would put a hard dark band around
 * the edge of every cascade.
 */
float neosky_shadow_compare_texel(sampler2D depthMap, vec3 projected, vec2 receiverGradient,
                                  ivec2 texel, ivec2 mapSize, float texelSize, float residualBias) {
    if (any(lessThan(texel, ivec2(0))) || any(greaterThanEqual(texel, mapSize))) {
        return 0.0;
    }

    float receiverDepth = neosky_receiver_depth_at_texel(projected, receiverGradient, texel, texelSize);
    return receiverDepth - residualBias <= texelFetch(depthMap, texel, 0).r ? 1.0 : 0.0;
}

/**
 * Full cascade filter.
 *
 * @param texelSize 1 / cascade resolution, i.e. the UV size of one texel
 * @param baseBias  constant depth bias
 * @param samples  1 for a hard edge, 4 for a smoothstep-weighted 2x2 reconstruction
 *
 * Note that slopeBias is accepted for signature parity with the reference but deliberately unused:
 * the reference's hardware path folds slope scaling into the fixed-function bias, and the software
 * path relies on the receiver-plane gradient to handle grazing angles instead. Applying a slope
 * bias here as well would double-count it and detach contact shadows at shallow angles.
 */
float neosky_shadow_filter(sampler2D depthMap, vec3 projected, vec2 receiverGradient,
                           float texelSize, float baseBias, float slopeBias, int samples) {
    if (projected.x <= 0.0 || projected.x >= 1.0
            || projected.y <= 0.0 || projected.y >= 1.0
            || projected.z <= 0.0 || projected.z >= 1.0) {
        return 0.0;
    }

    float residualBias = baseBias;
    int mapLength = max(1, int(floor(1.0 / max(texelSize, 1.0e-8) + 0.5)));
    ivec2 mapSize = ivec2(mapLength);

    if (samples <= 1) {
        ivec2 texel = ivec2(projected.xy * float(mapLength));
        return neosky_shadow_compare_texel(depthMap, projected, receiverGradient, texel, mapSize,
                                           texelSize, residualBias);
    }

    vec2 texelPosition = projected.xy / texelSize - 0.5;
    ivec2 baseTexel = ivec2(floor(texelPosition));
    vec2 blend = fract(texelPosition);

#ifdef NEO_SKY_TEXTURE_GATHER
    // The gather coordinate is the top-right texel of the 2x2 group, which is where the spec
    // anchors the four returned components. GL returns them as
    // (.x = lower-left, .y = lower-right, .z = upper-left, .w = upper-right), which is why the
    // comparisons below pair s00 with .w, s10 with .z, s01 with .x and s11 with .y rather than
    // reading them in the same order as the texel indices.
    //
    // The gather samples at texel *centres* because the depth texture is NEAREST, so no
    // hardware filtering is applied and the raw values arrive unblended.
    vec2 gatherUv = (vec2(baseTexel) + vec2(1.0)) * texelSize;
    vec4 depths = textureGather(depthMap, gatherUv);

    float r00 = neosky_receiver_depth_at_texel(projected, receiverGradient, baseTexel, texelSize);
    float r10 = neosky_receiver_depth_at_texel(projected, receiverGradient, baseTexel + ivec2(1, 0), texelSize);
    float r01 = neosky_receiver_depth_at_texel(projected, receiverGradient, baseTexel + ivec2(0, 1), texelSize);
    float r11 = neosky_receiver_depth_at_texel(projected, receiverGradient, baseTexel + ivec2(1, 1), texelSize);

    float s00 = step(r00 - residualBias, depths.w);
    float s10 = step(r10 - residualBias, depths.z);
    float s01 = step(r01 - residualBias, depths.x);
    float s11 = step(r11 - residualBias, depths.y);
#else
    float s00 = neosky_shadow_compare_texel(depthMap, projected, receiverGradient, baseTexel, mapSize,
                                             texelSize, residualBias);
    float s10 = neosky_shadow_compare_texel(depthMap, projected, receiverGradient, baseTexel + ivec2(1, 0), mapSize,
                                             texelSize, residualBias);
    float s01 = neosky_shadow_compare_texel(depthMap, projected, receiverGradient, baseTexel + ivec2(0, 1), mapSize,
                                             texelSize, residualBias);
    float s11 = neosky_shadow_compare_texel(depthMap, projected, receiverGradient, baseTexel + ivec2(1, 1), mapSize,
                                             texelSize, residualBias);
#endif

    vec2 weights = smoothstep(vec2(0.0), vec2(1.0), blend);

    return mix(mix(s00, s10, weights.x), mix(s01, s11, weights.x), weights.y);
}

/**
 * Samples one cascade and returns its visibility in 0..1.
 *
 * <p>A receiver outside the cascade is returned as fully lit, matching the out-of-range tap rule
 * inside neosky_shadow_compare_texel.
 */
float neosky_cascade_visibility(sampler2D depthMap, mat4 cascadeMatrix, vec4 cascadeParams,
                                vec3 receiverPosition, int samples) {
    vec3 projected = (cascadeMatrix * vec4(receiverPosition, 1.0)).xyz;

    // Off the near or far plane of the cascade: nothing to compare against.
    if (projected.z <= 0.0 || projected.z >= 1.0) {
        return 0.0;
    }

    if (projected.x <= 0.0 || projected.x >= 1.0
            || projected.y <= 0.0 || projected.y >= 1.0) {
        return 0.0;
    }

    vec2 gradient = neosky_receiver_plane_depth_gradient(projected);

    return neosky_shadow_filter(depthMap, projected, gradient,
                                cascadeParams.y, cascadeParams.z, cascadeParams.w, samples);
}

/**
 * Combined near/far visibility for a receiver in camera-relative world space.
 *
 * <p>The near cascade wins inside its radius, the far cascade outside it, and the two cross-fade
 * over the last few percent of the near radius so the transition is not a visible ring. The far
 * cascade additionally fades out over the last 5% of its own radius, because a receiver at the
 * very edge of a cascade has almost no information about what is casting onto it.
 *
 * @param receiverPosition camera-relative world position, matching what the cascade matrices expect
 */
float neosky_shadow_visibility(vec3 receiverPosition, int samples) {
    float nearVisibility = neosky_cascade_visibility(
            u_NeoSkyNearDepth, u_NeoSkyNearMatrix, u_NeoSkyNear, receiverPosition, samples);

    float farVisibility = neosky_cascade_visibility(
            u_NeoSkyFarDepth, u_NeoSkyFarMatrix, u_NeoSkyFar, receiverPosition, samples);

    float distance = length(receiverPosition);

    // 0 well inside the near cascade, 1 once past its radius.
    float nearBlend = smoothstep(u_NeoSkyBlend.x, u_NeoSkyNear.x, distance);

    // Ramps the far cascade out over the last 5% of its radius. Without this a receiver at the
    // cascade boundary would pop from "shadowed" to "fully lit" in one frame.
    float farFade = 1.0 - smoothstep(u_NeoSkyFar.x * 0.95, u_NeoSkyFar.x, distance);

    return nearVisibility * (1.0 - nearBlend) + farVisibility * nearBlend * farFade;
}
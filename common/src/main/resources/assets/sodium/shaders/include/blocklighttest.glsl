// BlockLightTest, ported from Luxium's include of the same name.
//
// Unlike a screen-space effect, this runs inside the terrain shader, which is what makes it
// correct: bt_luxium_spread_visibility() needs the vanilla block-light level of the receiving
// fragment to decide whether an emitter is already visible through flood-filled space. That value
// only exists per-vertex, so a fullscreen pass reading scene depth could not have produced it.
//
// The emitter arrays are uploaded once per frame by BlockLightEmitters. Emitters are stored in
// *view* space, which is the same space the receiver position arrives in, and differs from
// Luxium's camera-relative world space only by the view rotation; all the distance-based terms
// below are rotation invariant.
//
// The w component packs two values: abs(w) is the emission normalized to 0..1, and a negative
// value marks a light carried in the player's hand. A carried light has no flood fill behind it,
// so it bypasses spread visibility entirely.
#ifndef SODIUM_BLOCK_LIGHT_TEST_GLSL
#define SODIUM_BLOCK_LIGHT_TEST_GLSL

// The emitter arrays are members of the shared u_Globals block, declared in globals.glsl. Every
// terrain stage already binds that block, so no extra bind group entry is needed.

const float BT_LOCAL_NEAR_REJECT = 0.6;
const float BT_LOCAL_MAX_RADIUS = 36.0;

float bt_luxium_radius(float emission) {
    return max(1.0, BT_LOCAL_MAX_RADIUS * clamp(emission, 0.0, 1.0));
}

float bt_luxium_source_intensity(float emission) {
    return 1.18 * pow(clamp(emission, 0.0, 1.0), 0.78);
}

float bt_luxium_attenuation(float distanceToLight, float radius, out float radial) {
    radial = 1.0 - distanceToLight / max(radius, 0.0001);
    if (radial <= 0.0) return 0.0;

    return max((exp(-3.0 * (1.0 - radial)) - 0.0497871) / 0.9502129, 0.0);
}

float bt_luxium_diffuse(vec3 normal, vec3 toLight, float distanceToLight) {
    if (distanceToLight <= BT_LOCAL_NEAR_REJECT) return 1.0;

    vec3 n = normal * inversesqrt(max(dot(normal, normal), 0.00000001));
    vec3 l = toLight / max(distanceToLight, 0.00001);
    float nDotL = max(dot(n, l), 0.0);
    return clamp((nDotL + 0.16) / 1.16, 0.0, 1.0);
}

float bt_luxium_vanilla_curve(float level) {
    float amount = clamp(level, 0.0, 1.0);
    return amount * amount * (2.4 - 1.4 * amount);
}

float bt_luxium_spread_visibility(float blockLightGuide, float attenuation, float radial) {
    float guideBase = max(attenuation, 0.0);
    float guideCurve = mix(guideBase, sqrt(guideBase), 0.4841);
    float expectedGuide = max(guideCurve * 0.72, 0.04);
    float relativeGuide = clamp(blockLightGuide / expectedGuide, 0.0, 2.0);
    float floodVisibility = smoothstep(0.035, 0.48, relativeGuide);
    floodVisibility *= smoothstep(0.0, 0.08, radial);
    return floodVisibility;
}

/**
 * Reconstructs the shading normal from screen-space derivatives of the view position.
 *
 * The cross product's winding is arbitrary, so it is flipped to face the camera: the camera sits
 * at the view-space origin, hence a front-facing surface has dot(normal, viewPos) < 0.
 */
vec3 bt_view_normal(vec3 viewPos) {
    vec3 normal = normalize(cross(dFdx(viewPos), dFdy(viewPos)));

    if (dot(normal, viewPos) > 0.0) {
        normal = -normal;
    }

    return normal;
}

/**
 * Returns the local emitter light for a surface, without the vanilla replacement term.
 *
 * Luxium's bt_illumination() returns `mix(vanillaApprox, black, replacementWeight) + localLight`,
 * because its shader receives the sky and block light as separate varyings and can therefore
 * rebuild the whole lighting sum from scratch. Sodium bakes both halves into v_Color via a single
 * combined lightmap lookup, so there is no way to subtract the block-light half again; calling
 * Luxium's function verbatim here would count vanilla block light twice. The emitter term is
 * rotation- and scale-independent and stays exactly as Luxium computes it, spread visibility
 * included; only the vanilla replacement half is dropped.
 */
vec3 bt_local_light(float level, vec3 receiver, vec3 normal) {
    if (u_BtCount <= 0) {
        return vec3(0.0);
    }

    float blockLightGuide = bt_luxium_vanilla_curve(level);

    vec3 localLight = vec3(0.0);

    for (int i = 0; i < 12; ++i) {
        if (i >= u_BtCount) break;

        vec3 toLight = u_BtEmitters[i].xyz - receiver;
        float distSq = dot(toLight, toLight);
        if (distSq <= 0.00000001) continue;

        float emission = abs(u_BtEmitters[i].w);
        if (emission <= 0.00001) continue;

        float radius = bt_luxium_radius(emission);
        if (distSq > radius * radius) continue;

        float dist = sqrt(distSq);
        float radial;
        float attenuation = bt_luxium_attenuation(dist, radius, radial);
        if (attenuation <= 0.0) continue;

        float diffuse = bt_luxium_diffuse(normal, toLight, dist);

        bool carried = u_BtEmitters[i].w < 0.0;
        float visibility = carried
                ? 1.0
                : bt_luxium_spread_visibility(blockLightGuide, attenuation, radial);

        float sourceIntensity = bt_luxium_source_intensity(emission);
        float weight = attenuation * diffuse * visibility;
        if (weight > 0.0001) {
            localLight += u_BtColors[i].rgb * sourceIntensity * weight;
        }
    }

    return localLight;
}

#endif
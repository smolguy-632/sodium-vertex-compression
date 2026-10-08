#version 330
#extension GL_ARB_separate_shader_objects : require

#include <sodium:globals.glsl>
#include <sodium:fog.glsl>
#include <sodium:chunk_vertex.glsl>

layout(location = 0) out vec4 v_Color;
layout(location = 1) out vec2 v_TexCoord;

// BlockLightTest: view-space position (camera sits at the view-space origin) and the fragment's
// vanilla block-light level, normalized to 0..1. Every vertex of a quad belongs to one block and
// therefore shares a light level, so a plain interpolated float carries the value exactly.
layout(location = 4) out vec3 v_BtViewPos;
layout(location = 5) out float v_BtBlockLight;

#ifdef NEO_SKY_SHADOWS
// NeoSkyCelestia: the fragment's camera-relative world position, which is the space the cascade
// matrices are composed for. u_RegionOffset is already camera-relative, so `position` below is
// exactly the coordinate the shadow lookup expects with no extra correction.
layout(location = 6) out vec3 v_NeoSkyPos;
#endif

#ifdef USE_FOG
layout(location = 2) out vec2 v_FragDistance;
layout(location = 3) out float fadeFactor;
#endif

uniform isamplerBuffer u_SectionTimeInfo;

#ifdef VULKAN
layout(push_constant) uniform PC {
    vec3 u_RegionOffset;
    int u_CurrentTime;
    uint u_RegionID;
};
#else
uniform vec3 u_RegionOffset;
uniform int u_CurrentTime;
uniform uint u_RegionID;
#endif

#ifndef OIT_ALPHA_ONLY
uniform sampler2D u_LightTex; // The light map texture sampler
#endif

uvec3 _get_relative_chunk_coord(uint pos) {
    // Packing scheme is defined by LocalSectionIndex
    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 _get_draw_translation(uint pos) {
    return _get_relative_chunk_coord(pos) * vec3(16.0);
}

void main() {
    _vert_init();

    // Transform the chunk-local vertex position into world model space
    vec3 translation = u_RegionOffset + _get_draw_translation(_draw_id);
    vec3 position = _vert_position + translation;

#ifdef USE_FOG
    v_FragDistance = getFragDistance(position);

    int chunkId = int(_draw_id);
    int chunkFade = texelFetch(u_SectionTimeInfo, int((u_RegionID * 256u) + uint(chunkId))).r;
    int fadeTime = u_CurrentTime - chunkFade;
    float elapsed = float(fadeTime);
    float fade = clamp(float(u_CurrentTime - chunkFade) * u_FadePeriodInv, 0.0, 1.0);
    fadeFactor = (chunkFade < 0) ? 1.0 : fade;
#endif

    // Transform the vertex position into model-view-projection space
    vec4 viewPos = u_ModelViewMatrix * vec4(position, 1.0);
    gl_Position = u_ProjectionMatrix * viewPos;

    v_BtViewPos = viewPos.xyz;

    #ifdef NEO_SKY_SHADOWS
    // The cascade matrices were composed for camera-relative coordinates, and `position` is already
    // in that frame because u_RegionOffset carries the camera subtraction.
    v_NeoSkyPos = position;
    #endif

    // CompactChunkVertex.encodeLight() stores the block level biased into 8..248 and the vertex
    // shader divides the raw byte by 256, so undo both steps to recover the original 0..15 level.
    v_BtBlockLight = clamp(floor(_vert_tex_light_coord.x * 256.0 + 0.5) - 8.0, 0.0, 15.0) / 15.0;

    // Add the light color to the vertex color, and pass the texture coordinates to the fragment shader
#ifndef OIT_ALPHA_ONLY
    v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);
#else
    v_Color = _vert_color;
#endif

    v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord; // FMA for precision
}

#version 330
#extension GL_ARB_separate_shader_objects : require

#include <sodium:chunk_vertex.glsl>

// Caster pass: projects chunk geometry into one cascade's clip space so the depth-only fragment
// shader can record how far away the nearest caster is.
//
// This deliberately does NOT include globals.glsl. The terrain vertex shader needs u_ModelViewMatrix
// and u_ProjectionMatrix from there, but the caster pass projects through the cascade's own light
// matrix instead, so those two would be dead weight in the bind group. Only the region offset comes
// from the push constants, exactly as in the terrain pass, because the recorded MultiDrawBatch
// commands were built around that same region-local addressing.

layout(std140) uniform u_NeoSkyCaster {
    mat4 u_CascadeMatrix;
};

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

uvec3 _get_relative_chunk_coord(uint pos) {
    // Packing scheme is defined by LocalSectionIndex. Identical to the terrain vertex shader:
    // changing it here would place casters at the wrong section offsets.
    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 _get_draw_translation(uint pos) {
    return _get_relative_chunk_coord(pos) * vec3(16.0);
}

void main() {
    _vert_init();

    // Same world-space position the terrain pass builds, so a caster and the surface it shadows
    // agree on where the geometry is.
    vec3 translation = u_RegionOffset + _get_draw_translation(_draw_id);
    vec3 position = _vert_position + translation;

    // u_CascadeMatrix already carries the camera-relative translation and the bias matrix, so the
    // position is handed over in exactly the space the cascade was composed for.
    gl_Position = u_CascadeMatrix * vec4(position, 1.0);
}
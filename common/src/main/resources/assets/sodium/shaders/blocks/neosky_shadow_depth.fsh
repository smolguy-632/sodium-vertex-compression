#version 460 core

// Depth-only caster fragment shader.
//
// It writes no colour at all: the pipeline declares no colour target, so there is no fragColor here
// and nothing to blend. The depth value gl_FragCoord.z writes is the entire output, and that is
// enough for a shadow map.
//
// Note what is absent. The terrain fragment shader samples the block atlas for the alpha cutout
// test and the lightmap for skylight. Neither is bound here: cutout geometry is included wholesale
// by this pass, and a caster that casts through a leaf's transparent pixels is an acceptable
// approximation that costs nothing to produce.

// Empty translation unit by design. See the note above.
void main() {
}
// The position of the vertex around the model origin
vec3 _vert_position;

// The block texture coordinate of the vertex
vec2 _vert_tex_diffuse_coord;
vec2 _vert_tex_diffuse_coord_bias;

// The light texture coordinate of the vertex
vec2 _vert_tex_light_coord;

// The color of the vertex
vec4 _vert_color;

// The index of the draw command which this vertex belongs to
uint _draw_id;

// The material bits for the primitive
uint _material_params;

#ifdef USE_VERTEX_COMPRESSION
/**
 * Packed position bit layout, one width per axis.
 *
 * ShaderChunkRenderer.applyPositionLayoutDefines() injects these three as valued defines taken from the
 * "Position Bits" settings, and CompactChunkVertex quantized the mesh with those very same widths. Keep
 * the two sides in step: editing a width on one side only silently misplaces terrain.
 *
 * They arrive as float literals, the way every valued define does, hence the uint() conversion. Should a
 * define ever go missing, the identifier is left undeclared and the shader fails to compile, which is the
 * intended outcome -- decoding with guessed widths would be far harder to notice than a black screen.
 *
 * Layout within the 32-bit word, low bits first: X, then Y, then Z.
 */
const uint POSITION_X_BITS = uint(SODIUM_POSITION_X_BITS);
const uint POSITION_Y_BITS = uint(SODIUM_POSITION_Y_BITS);
const uint POSITION_Z_BITS = uint(SODIUM_POSITION_Z_BITS);

const uint POSITION_X_MASK    = (1u << POSITION_X_BITS) - 1u;
const uint POSITION_Y_MASK    = (1u << POSITION_Y_BITS) - 1u;
const uint POSITION_Z_MASK    = (1u << POSITION_Z_BITS) - 1u;

const uint POSITION_Y_SHIFT   = POSITION_X_BITS;
const uint POSITION_Z_SHIFT   = POSITION_X_BITS + POSITION_Y_BITS;

const float POSITION_X_MAX    = float(POSITION_X_MASK);
const float POSITION_Y_MAX    = float(POSITION_Y_MASK);
const float POSITION_Z_MAX    = float(POSITION_Z_MASK);

const uint TEXTURE_BITS         = 15u;
const uint TEXTURE_MAX_COORD    = 1u << TEXTURE_BITS;
const uint TEXTURE_MAX_VALUE    = TEXTURE_MAX_COORD - 1u;

const float VERTEX_SCALE = 32.0 / POSITION_X_MAX;
const float VERTEX_OFFSET = -8.0;

layout(location = 0) in uint a_Position;
layout(location = 1) in vec4 a_Color;
layout(location = 2) in uvec2 a_TexCoord;
layout(location = 3) in uvec4 a_LightAndData;

/**
 * Unpacks X, Y and Z from the single 32-bit position word produced by CompactChunkVertex.packPosition().
 */
uvec3 _unpack_position(uint data) {
    uint x = (data >>  0u) & POSITION_X_MASK;
    uint y = (data >> POSITION_Y_SHIFT) & POSITION_Y_MASK;
    uint z = (data >> POSITION_Z_SHIFT) & POSITION_Z_MASK;

    return uvec3(x, y, z);
}

vec2 _get_texcoord() {
    return vec2(a_TexCoord & TEXTURE_MAX_VALUE) / float(TEXTURE_MAX_COORD);
}

vec2 _get_texcoord_bias() {
    return mix(vec2(-1.0), vec2(1.0), bvec2(a_TexCoord >> TEXTURE_BITS));
}

void _vert_init() {
    vec3 unpacked = vec3(_unpack_position(a_Position));
    vec3 scale = vec3(POSITION_X_MAX, POSITION_Y_MAX, POSITION_Z_MAX);

    _vert_position = (unpacked / scale) * 32.0 + VERTEX_OFFSET;
    _vert_color = a_Color;
    _vert_tex_diffuse_coord = _get_texcoord();
    _vert_tex_diffuse_coord_bias = _get_texcoord_bias();

    _vert_tex_light_coord = vec2(a_LightAndData.xy) / vec2(256.0);

    _material_params = a_LightAndData[2];
    _draw_id = a_LightAndData[3];
}

#else
#error "Vertex compression must be enabled"
#endif
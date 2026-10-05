package net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics;
import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.VertexBits;
import net.minecraft.util.Mth;

/**
 * Packed chunk vertex format.
 *
 * <p>The position is encoded as a single 32-bit integer that packs three fixed-width unsigned fields:
 * {@code X_BITS | Y_BITS | Z_BITS} (least-significant field first). Because every field is a whole
 * number of bits, the position always fits in one {@code uint32} and therefore in a 4-byte attribute,
 * which is half the size of the previous two-{@code uint32} ({@code RG32_UINT}) attribute.
 *
 * <p>Every field shares one normalization window of {@link #MODEL_RANGE} blocks, matching the range the
 * section mesher may emit (block models can extend past the 16-block section bounds). Reducing a field's
 * bit width therefore reduces <em>precision</em> (blocks per stored step), never the representable range.
 *
 * <p>Two precision profiles are selectable at build time through the Gradle property
 * {@code -Pvertex.bits=<profile>}, see {@code docs/vertex-format.md}.
 */
public class CompactChunkVertex implements ChunkVertexType {
    /**
     * Packed position bit layout, generated at build time from the {@code vertex.bits} Gradle property.
     * See {@code buildSrc}-adjacent task {@code :common:generateVertexBits}.
     */
    public static final int X_BITS = VertexBits.X_BITS;
    public static final int Y_BITS = VertexBits.Y_BITS;
    public static final int Z_BITS = VertexBits.Z_BITS;

    public static final int X_SHIFT = VertexBits.X_SHIFT;
    public static final int Y_SHIFT = VertexBits.Y_SHIFT;
    public static final int Z_SHIFT = VertexBits.Z_SHIFT;

    public static final int X_MAX = VertexBits.X_MAX;
    public static final int Y_MAX = VertexBits.Y_MAX;
    public static final int Z_MAX = VertexBits.Z_MAX;

    public static final int POSITION_BITS = VertexBits.TOTAL_BITS;

    private static final float MODEL_ORIGIN = VertexBits.MODEL_ORIGIN;
    private static final float MODEL_RANGE = VertexBits.MODEL_RANGE;

    /** Byte stride of a single vertex. */
    public static final int STRIDE = 16;

    public static final VertexFormat VERTEX_FORMAT = VertexFormat.builder(0)
            .addAttribute("a_Position", GpuFormat.R32_UINT)
            .addAttribute("a_Color", GpuFormat.RGBA8_UNORM)
            .addAttribute("a_TexCoord", GpuFormat.RG16_UINT)
            .addAttribute("a_LightAndData", GpuFormat.RGBA8_UINT).build();

    public static final int TEXTURE_MAX_VALUE = 1 << 15;

    @Override
    public VertexFormat getVertexFormat() {
        return VERTEX_FORMAT;
    }

    @Override
    public ChunkVertexEncoder getEncoder() {
        return (ptr, materialBits, vertices, section) -> {
            // Calculate the center point of the texture region which is mapped to the quad
            float texCentroidU = 0.0f;
            float texCentroidV = 0.0f;

            for (var vertex : vertices) {
                texCentroidU += vertex.u;
                texCentroidV += vertex.v;
            }

            texCentroidU *= (1.0f / 4.0f);
            texCentroidV *= (1.0f / 4.0f);

            for (int i = 0; i < 4; i++) {
                var vertex = vertices[i];

                int position = packPosition(vertex.x, vertex.y, vertex.z);

                int u = encodeTexture(texCentroidU, vertex.u);
                int v = encodeTexture(texCentroidV, vertex.v);

                int light = encodeLight(vertex.light);

                MemoryIntrinsics.putInt(ptr +  0L, position);
                MemoryIntrinsics.putInt(ptr +  4L, ColorARGB.mulRGB(vertex.color, vertex.ao));
                MemoryIntrinsics.putInt(ptr +  8L, packTexture(u, v));
                MemoryIntrinsics.putInt(ptr + 12L, packLightAndData(light, materialBits, section));

                ptr += STRIDE;
            }

            return ptr;
        };
    }

    /**
     * Packs X, Y and Z into a single 32-bit integer as {@code X_BITS | Y_BITS | Z_BITS}.
     * Identical layout to {@code _deinterleave_position()} in {@code chunk_vertex.glsl}.
     */
    private static int packPosition(float x, float y, float z) {
        int px = quantizePosition(x, X_BITS, X_MAX);
        int py = quantizePosition(y, Y_BITS, Y_MAX);
        int pz = quantizePosition(z, Z_BITS, Z_MAX);

        return (px << X_SHIFT) | (py << Y_SHIFT) | (pz << Z_SHIFT);
    }

    private static int quantizePosition(float position, int bits, int max) {
        float normalized = normalizePosition(position);

        // Clamp before scaling so out-of-range geometry saturates instead of wrapping around.
        if (normalized < 0.0f) {
            normalized = 0.0f;
        } else if (normalized > 1.0f) {
            normalized = 1.0f;
        }

        return Math.min((int) (normalized * max), max);
    }

    private static float normalizePosition(float v) {
        return (MODEL_ORIGIN + v) / MODEL_RANGE;
    }

    private static int packTexture(int u, int v) {
        return ((u & 0xFFFF) << 0) | ((v & 0xFFFF) << 16);
    }

    private static int encodeTexture(float center, float x) {
        // Shrink the texture coordinates (towards the center of the mapped texture region) by the minimum
        // addressable unit (after quantization.) Then, encode the sign of the bias that was used, and apply
        // the inverse transformation on the GPU with a small epsilon.
        //
        // This makes it possible to use much smaller epsilons for avoiding texture bleed, since the epsilon is no
        // longer encoded into the vertex data (instead, we only store the sign.)
        int bias = (x < center) ? 1 : -1;
        int quantized = Math.round(x * TEXTURE_MAX_VALUE) + bias;

        return (quantized & 0x7FFF) | (sign(bias) << 15);
    }

    private static int encodeLight(int light) {
        int sky = Mth.clamp(((light >>> 16) & 0xFF) + 8, 8, 248);
        int block = Mth.clamp(((light >>>  0) & 0xFF) + 8, 8, 248);

        return (block << 0) | (sky << 8);
    }

    private static int packLightAndData(int light, int material, int section) {
        return ((light & 0xFFFF) << 0) |
                ((material & 0xFF) << 16) |
                ((section & 0xFF) << 24);
    }

    private static int sign(int x) {
        // Shift the sign-bit to the least significant bit's position
        // (0) if positive, (1) if negative
        return (x >>> 31);
    }

}
package net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics;
import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.VertexPositionLayout;
import net.minecraft.util.Mth;

/**
 * Packed chunk vertex format.
 *
 * <p>The position is encoded as a single 32-bit integer that packs three fixed-width unsigned fields:
 * {@code X_BITS | Y_BITS | Z_BITS} (least-significant field first). Because every field is a whole
 * number of bits, the position always fits in one {@code uint32} and therefore in a 4-byte attribute,
 * which is half the size of the previous two-{@code uint32} ({@code RG32_UINT}) attribute.
 *
 * <p>Every field shares one normalization window of {@link VertexPositionLayout#MODEL_RANGE} blocks,
 * matching the range the section mesher may emit (block models can extend past the 16-block section
 * bounds). Reducing a field's bit width therefore reduces <em>precision</em> (blocks per stored step),
 * never the representable range.
 *
 * <p>The bit widths are chosen at runtime from the three "Position Bits" sliders in the video settings.
 * Each encoder instance snapshots that layout so every quad written by one buffer builder is quantized
 * consistently; the shader reads the matching layout through the defines emitted by
 * {@code ShaderChunkRenderer.createShaderConstants()}.
 */
public class CompactChunkVertex implements ChunkVertexType {
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
        // Snapshot the layout for the lifetime of this buffer builder. The setting can only change through a
        // renderer reload, which discards and rebuilds every mesh, so no single mesh ever mixes two layouts.
        var layout = SodiumClientMod.options().performance.positionLayout();

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

                int position = packPosition(layout, vertex.x, vertex.y, vertex.z);

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
     * Identical layout to {@code _unpack_position()} in {@code chunk_vertex.glsl}.
     */
    private static int packPosition(VertexPositionLayout layout, float x, float y, float z) {
        int px = quantizePosition(x, layout.getXMax());
        int py = quantizePosition(y, layout.getYMax());
        int pz = quantizePosition(z, layout.getZMax());

        return (px << layout.getXShift()) | (py << layout.getYShift()) | (pz << layout.getZShift());
    }

    private static int quantizePosition(float position, int max) {
        float normalized = normalizePosition(position);

        // Clamp before scaling so out-of-range geometry saturates instead of wrapping around.
        if (normalized < 0.0f) {
            normalized = 0.0f;
        } else if (normalized > 1.0f) {
            normalized = 1.0f;
        }

        // Round to nearest rather than truncate. Truncation would bias every value downwards by up to a
        // full step, which is what made the block-select outline sit a whole block away from the terrain.
        return Math.min(Math.round(normalized * max), max);
    }

    private static float normalizePosition(float v) {
        return (VertexPositionLayout.MODEL_ORIGIN + v) / VertexPositionLayout.MODEL_RANGE;
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
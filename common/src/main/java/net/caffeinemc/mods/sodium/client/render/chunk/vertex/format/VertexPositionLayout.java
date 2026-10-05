package net.caffeinemc.mods.sodium.client.render.chunk.vertex.format;

import net.caffeinemc.mods.sodium.client.gui.options.TextProvider;
import net.minecraft.network.chat.Component;

/**
 * Selectable bit layouts for the packed chunk vertex position.
 *
 * <p>The position is stored as one {@code uint32} split into three fixed-width unsigned fields laid out
 * low bits first as {@code X | Y | Z}. Every field quantizes the same {@link #MODEL_RANGE}-block window
 * (spanning section-local {@code -2 .. 18}), so a field's bit width controls <em>precision</em> and never
 * the representable range.
 *
 * <p>Maximum quantization error is half a stored step, i.e. {@code MODEL_RANGE / (2 << bits)}:
 *
 * <pre>
 *   COMPACT  (8/9/8)  -> 0.063 blocks
 *   BALANCED (9/9/9)  -> 0.031 blocks
 *   PRECISE  (11/9/11)-> 0.008 blocks
 * </pre>
 *
 * <p>All profiles fit inside a single 32-bit word and therefore share one {@code VertexFormat} with a
 * 16-byte stride, so switching profiles never requires reconfiguring GPU vertex buffers.
 *
 * <p>Each profile contributes one bare shader define (see {@link #getShaderDefine()}) so that
 * {@code chunk_vertex.glsl} derives its decode constants from the very same layout the encoder used.
 */
public enum VertexPositionLayout implements TextProvider {
    COMPACT(8, 9, 8, "sodium.options.vertex_position_layout.compact"),
    BALANCED(9, 9, 9, "sodium.options.vertex_position_layout.balanced"),
    PRECISE(11, 9, 11, "sodium.options.vertex_position_layout.precise");

    /** Model-space window covered by the packed position, in blocks. */
    public static final float MODEL_ORIGIN = 8.0f;
    public static final float MODEL_RANGE = 32.0f;

    private final int xBits;
    private final int yBits;
    private final int zBits;
    private final Component name;

    VertexPositionLayout(int xBits, int yBits, int zBits, String nameKey) {
        if (xBits < 1 || yBits < 1 || zBits < 1) {
            throw new IllegalArgumentException("Every position field needs at least one bit");
        }

        if (xBits + yBits + zBits > Integer.SIZE) {
            throw new IllegalArgumentException("Position layout " + xBits + "/" + yBits + "/" + zBits
                    + " does not fit in a single 32-bit word");
        }

        this.xBits = xBits;
        this.yBits = yBits;
        this.zBits = zBits;
        this.name = Component.translatable(nameKey);
    }

    public int getXBits() {
        return this.xBits;
    }

    public int getYBits() {
        return this.yBits;
    }

    public int getZBits() {
        return this.zBits;
    }

    public int getXShift() {
        return 0;
    }

    public int getYShift() {
        return this.xBits;
    }

    public int getZShift() {
        return this.xBits + this.yBits;
    }

    /** Largest value representable by the X field; likewise for the Y/Z variants. */
    public int getXMax() {
        return (1 << this.xBits) - 1;
    }

    public int getYMax() {
        return (1 << this.yBits) - 1;
    }

    public int getZMax() {
        return (1 << this.zBits) - 1;
    }

    public int getTotalBits() {
        return this.xBits + this.yBits + this.zBits;
    }

    /**
     * Bare shader define selecting this layout inside {@code chunk_vertex.glsl}, e.g.
     * {@code VERTEX_BITS_11_9_11}.
     */
    public String getShaderDefine() {
        return "VERTEX_BITS_" + this.xBits + "_" + this.yBits + "_" + this.zBits;
    }

    @Override
    public Component getLocalizedName() {
        return this.name;
    }
}

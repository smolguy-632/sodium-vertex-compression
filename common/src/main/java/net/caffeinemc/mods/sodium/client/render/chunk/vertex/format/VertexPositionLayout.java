package net.caffeinemc.mods.sodium.client.render.chunk.vertex.format;

/**
 * The bit widths of the packed chunk vertex position, one per axis.
 *
 * <p>The position is stored as one {@code uint32} split into three fixed-width unsigned fields laid out
 * low bits first as {@code X | Y | Z}. Every field quantizes the same {@link #MODEL_RANGE}-block window
 * (spanning section-local {@code -2 .. 18}), so a field's bit width controls <em>precision</em> and never
 * the representable range.
 *
 * <p>Maximum quantization error is half a stored step, i.e. {@code MODEL_RANGE / (2 << bits)}, so each
 * axis is worth about {@code 32 / (2 << bits)} blocks of error:
 *
 * <pre>
 *   8 bits -> 0.063 blocks
 *   9 bits -> 0.031 blocks
 *  11 bits -> 0.008 blocks
 * </pre>
 *
 * <p>The three widths must total no more than {@link Integer#SIZE} bits; keeping that within 32 is the
 * player's job in the settings screen. This record is the single value both sides of the pipeline agree
 * on: {@code CompactChunkVertex} quantizes with it and {@code chunk_vertex.glsl} decodes with it through
 * the defines {@code ShaderChunkRenderer} derives from the same instance.
 */
public record VertexPositionLayout(int xBits, int yBits, int zBits) {
    /** Model-space window covered by the packed position, in blocks. */
    public static final float MODEL_ORIGIN = 8.0f;
    public static final float MODEL_RANGE = 32.0f;

    /** Upper bound the settings screen offers for a single axis. */
    public static final int MAX_AXIS_BITS = 16;

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
        return 1 << this.xBits;
    }

    public int getYMax() {
        return 1 << this.yBits;
    }

    public int getZMax() {
        return 1 << this.zBits;
    }

    public int getTotalBits() {
        return this.xBits + this.yBits + this.zBits;
    }
}

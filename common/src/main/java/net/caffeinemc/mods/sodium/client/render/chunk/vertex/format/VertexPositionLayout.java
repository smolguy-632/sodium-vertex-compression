package net.caffeinemc.mods.sodium.client.render.chunk.vertex.format;

/**
 * The bit widths of the packed chunk vertex position, one per axis.
 *
 * <p>The position is stored as one {@code uint32} split into three fixed-width unsigned fields laid out
 * low bits first as {@code X | Y | Z}. Every field quantizes the same {@link #MODEL_RANGE}-block window
 * (spanning section-local {@code -2 .. 18}), so a field's bit width controls <em>precision</em> and never
 * the representable range.
 *
 * <h2>Why a field of {@code n} bits occupies {@code n + 1} bits of the word</h2>
 *
 * <p>An axis quantized over {@link #MODEL_RANGE} blocks with divisor {@code 1 << bits} has lattice points
 * every {@code MODEL_RANGE / (1 << bits)} blocks. Because that divisor is a power of two and
 * {@link #MODEL_RANGE} is 32, every integer block boundary lands exactly on a lattice point, which is what
 * keeps adjacent sections flush instead of leaving gaps.
 *
 * <p>That inclusive endpoint is the catch: a field quantized this way spans {@code 0 .. 2^bits}
 * <em>inclusive</em>, which is {@code 2^bits + 1} distinct values and therefore needs {@code bits + 1} bits
 * to store. So each axis is given one bit more than its precision setting asks for. Deriving the mask as
 * {@code 1 << bits} instead would select a single bit rather than a whole field, and every vertex would
 * decode to a near-constant position.
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
 * <p>Because every axis carries one bit of overhead, the three settings can only total
 * {@link #MAX_TOTAL_PRECISION_BITS} between them; the canonical constructor enforces that, so an over-budget
 * combination of slider values gives up precision instead of overflowing the word. This record is the
 * single value both sides of the pipeline agree on: {@code CompactChunkVertex} quantizes with it and
 * {@code chunk_vertex.glsl} decodes with it through the defines {@code ShaderChunkRenderer} derives from
 * the same instance.
 */
public record VertexPositionLayout(int xBits, int yBits, int zBits) {
    /** Model-space window covered by the packed position, in blocks. */
    public static final float MODEL_ORIGIN = 8.0f;
    public static final float MODEL_RANGE = 32.0f;

    /** Upper bound the settings screen offers for a single axis. */
    public static final int MAX_AXIS_BITS = 16;

    /** Lower bound the settings screen offers for a single axis. */
    public static final int MIN_AXIS_BITS = 1;

    /**
     * Total precision bits the three axes may spend, excluding the one bit of overhead each axis carries.
     * Three axes cost three extra bits, so {@code 29 + 3 == 32} fills the word exactly.
     */
    public static final int MAX_TOTAL_PRECISION_BITS = Integer.SIZE - 3;

    public VertexPositionLayout {
        xBits = clampAxis(xBits);
        yBits = clampAxis(yBits);
        zBits = clampAxis(zBits);

        // Take a bit from whichever axis currently holds the most, one at a time. Reducing a fixed axis
        // first would let one slider keep every bit it asked for and starve the others: 16/16/16 would
        // become 16/12/1, leaving Z at one bit, i.e. sixteen-block steps. Taking from the largest keeps
        // the three axes within a bit of each other, so an over-budget request degrades all three evenly
        // instead of destroying one. Ties break in X, Y, Z order, so a given input always maps to the same
        // layout on both sides of the pipeline.
        while (totalPrecisionBits(xBits, yBits, zBits) > MAX_TOTAL_PRECISION_BITS) {
            if (zBits >= yBits && zBits >= xBits && zBits > MIN_AXIS_BITS) {
                zBits--;
            } else if (yBits >= xBits && yBits > MIN_AXIS_BITS) {
                yBits--;
            } else if (xBits > MIN_AXIS_BITS) {
                xBits--;
            } else {
                // Every axis sits at the floor and the total still does not fit. MAX_AXIS_BITS is chosen so
                // this is unreachable, but fail loudly rather than looping forever if that ever changes.
                throw new IllegalStateException("Position bit budget cannot be satisfied: " +
                        totalPrecisionBits(xBits, yBits, zBits) + " > " + MAX_TOTAL_PRECISION_BITS);
            }
        }
    }

    /**
     * Clamps one axis' precision setting into the range the settings screen exposes, so a hand-edited or
     * out-of-range config cannot produce a shift wider than the word.
     */
    private static int clampAxis(int bits) {
        return Math.max(MIN_AXIS_BITS, Math.min(MAX_AXIS_BITS, bits));
    }

    private static int totalPrecisionBits(int x, int y, int z) {
        return x + y + z;
    }

    /**
     * Reduces the three precision settings until they fit the 32-bit word. Prefer this wherever the widths
     * come from user-editable state, so an over-budget combination reduces precision instead of silently
     * corrupting every vertex position.
     */
    public static VertexPositionLayout fitWithinWord(int xBits, int yBits, int zBits) {
        return new VertexPositionLayout(xBits, yBits, zBits);
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

    /** Width of the X field <em>in the word</em>: its precision plus one bit of overhead. */
    public int getXFieldBits() {
        return this.xBits + 1;
    }

    /** Width of the Y field <em>in the word</em>: its precision plus one bit of overhead. */
    public int getYFieldBits() {
        return this.yBits + 1;
    }

    /** Width of the Z field <em>in the word</em>: its precision plus one bit of overhead. */
    public int getZFieldBits() {
        return this.zBits + 1;
    }

    public int getXShift() {
        return 0;
    }

    public int getYShift() {
        return this.getXFieldBits();
    }

    public int getZShift() {
        return this.getXFieldBits() + this.getYFieldBits();
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

    /** Selects every bit of the X field, including the overhead bit; likewise for the Y/Z variants. */
    public int getXMask() {
        return (1 << this.getXFieldBits()) - 1;
    }

    public int getYMask() {
        return (1 << this.getYFieldBits()) - 1;
    }

    public int getZMask() {
        return (1 << this.getZFieldBits()) - 1;
    }

    /** Total precision bits requested across the three axes, excluding per-axis overhead. */
    public int getTotalBits() {
        return totalPrecisionBits(this.xBits, this.yBits, this.zBits);
    }

    /** Bits of the 32-bit word actually occupied by the three fields, including per-axis overhead. */
    public int getTotalFieldBits() {
        return this.getXFieldBits() + this.getYFieldBits() + this.getZFieldBits();
    }
}
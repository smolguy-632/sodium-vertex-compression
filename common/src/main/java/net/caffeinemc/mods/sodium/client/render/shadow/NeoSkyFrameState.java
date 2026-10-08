package net.caffeinemc.mods.sodium.client.render.shadow;

import org.joml.Matrix4fc;

/**
 * Immutable snapshot of one cascade as of a single frame.
 *
 * <p>Modelled on Luxium's {@code NeoSkyCelestiaFrameState}, which carries three of these per frame
 * (two terrain cascades plus the entity map). Here each cascade keeps its own, so the composite
 * frame state is simply the near one plus the far one.
 *
 * @param lightMatrix bias x projection x light-rotation, mapping camera-relative world to [0,1]^3
 * @param radius      nominal cascade radius, used for the cross-cascade blend
 * @param texelSize   world units per texel, i.e. one over the cascade's world extent
 * @param baseBias    constant depth bias subtracted before comparing
 * @param slopeBias   slope-scaling factor; see {@code neosky_shadow.glsl} for why the software
 *                    path currently uses {@code baseBias} on its own
 * @param generation  incremented on every rebuild, so the shader can detect a stale sample
 */
public record NeoSkyFrameState(Matrix4fc lightMatrix,
                                float radius,
                                float texelSize,
                                float baseBias,
                                float slopeBias,
                                int generation) {

    /** Fraction of the near radius at which the near-to-far cross-fade begins. */
    public static final float CASCADE_BLEND_START = 0.94F;

    /**
     * The far cascade fades out over the last five percent of its radius.
     *
     * <p>Without this, terrain at the very edge of the far cascade would pop from "shadowed" to
     * "fully lit" the instant it crossed the boundary.
     */
    public static final float FAR_FADE_START = 0.95F;
}
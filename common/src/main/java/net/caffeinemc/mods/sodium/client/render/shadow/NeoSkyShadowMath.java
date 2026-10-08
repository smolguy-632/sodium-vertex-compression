package net.caffeinemc.mods.sodium.client.render.shadow;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

/**
 * Stabilization and light-space projection maths for the NeoSkyCelestia shadow cascades.
 *
 * <p>Ported from Luxium's {@code NeoSkyCelestiaMath}. Every constant here matches the reference;
 * nothing is approximated.
 *
 * <p>The single most important function in this class is
 * {@link #snapCenterToLightTexels(Vector3f, Matrix4fc, float, int)}. Without it the cascade origin
 * creeps by fractions of a texel every frame and every shadow edge crawls. Two details in the port
 * are load-bearing and easy to get wrong:
 *
 * <ul>
 *   <li>Snapping is done <em>relative to a 1024-block lattice anchor</em>. Snapping absolute world
 *       coordinates directly would quantise badly at large coordinates, because the float mantissa
 *       runs out long before a single texel is large enough to matter.
 *   <li>Only light-space X and Y are snapped. Depth Z is deliberately left alone — snapping it would
 *       quantise the depth range and produce visible stepping as the cascade is rebuilt.
 * </ul>
 */
public final class NeoSkyShadowMath {
    /**
     * Nearest depth plane. Pulled well below the usual 0.1 so a near-plane-aligned light still has
     * somewhere to put geometry.
     */
    public static final float MIN_NEAR_PLANE = 0.25F;

    /** Extra world-space depth in front of the cascade, so geometry just outside the radius still casts. */
    public static final float MIN_FRONT_PADDING = 2.0F;

    /** Side of the anchor lattice, in blocks. Matches the reference exactly. */
    private static final double ANCHOR_GRANULARITY = 1024.0;

    private static final Vector4f TMP_DIR = new Vector4f();

    private NeoSkyShadowMath() {
    }

    /**
     * NDC {@code [-1,1]} to texture space {@code [0,1]}: halve, then offset by a half.
     *
     * <p>This is the outermost factor of the cascade matrix, so it is the last step applied to a
     * clip-space position before {@code texelFetch} addressing.
     */
    public static Matrix4f biasMatrix(Matrix4f out) {
        return out.set(0.5F, 0.0F, 0.0F, 0.0F,
                0.0F, 0.5F, 0.0F, 0.0F,
                0.0F, 0.0F, 0.5F, 0.0F,
                0.5F, 0.5F, 0.5F, 1.0F);
    }

    /**
     * Builds the rotation part of a light-space view looking down the light direction.
     *
     * <p>The direction is negated first: light travels <em>from</em> the sun <em>toward</em> the
     * scene, so the camera for this view sits on the sun's side looking at the world.
     *
     * @param lightDirection normalized direction toward the light source
     * @param up             must not be parallel to {@code lightDirection}; a degenerate axis would
     *                       produce a singular matrix and a black cascade
     */
    public static Matrix4f lightViewRotation(Vector3fc lightDirection, Vector3f up, Matrix4f out) {
        // Fall back to a different axis when the light is (near) vertical, otherwise lookAlong
        // produces a singular basis and the whole cascade collapses.
        float ay = Math.abs(lightDirection.y());
        Vector3f safeUp = (ay > 0.999F) ? new Vector3f(0.0F, 0.0F, 1.0F) : up;

        return out.setLookAlong(new Vector3f(-lightDirection.x(), -lightDirection.y(), -lightDirection.z()),
                safeUp);
    }

    /**
     * Snaps a cascade centre onto the light-space texel grid. See the class javadoc for why the
     * anchor and the Z-skip both matter.
     *
     * @param center     world-space centre, usually the camera
     * @param lightRotation rotation-only matrix from {@link #lightViewRotation}
     * @param radius     the projection radius in world units, not the nominal radius
     * @param resolution cascade resolution in texels
     */
    public static Vector3f snapCenterToLightTexels(Vector3fc center, Matrix4fc lightRotation,
                                                   float radius, int resolution, Vector3f out) {
        float texelWorldSize = radius * 2.0F / (float) Math.max(1, resolution);

        double anchorX = Math.floor(center.x() / ANCHOR_GRANULARITY) * ANCHOR_GRANULARITY;
        double anchorY = Math.floor(center.y() / ANCHOR_GRANULARITY) * ANCHOR_GRANULARITY;
        double anchorZ = Math.floor(center.z() / ANCHOR_GRANULARITY) * ANCHOR_GRANULARITY;

        // Work relative to the anchor so the float arithmetic stays small and exact.
        float rx = (float) (center.x() - anchorX);
        float ry = (float) (center.y() - anchorY);
        float rz = (float) (center.z() - anchorZ);

        // world -> light. w = 0 drops the translation so this is a pure rotation; Vector4f.mul is
        // used rather than Matrix4fc.transformDirection so there is no ambiguity about whether
        // the result gets renormalised.
        TMP_DIR.set(rx, ry, rz, 0.0F).mul(lightRotation);

        float lx = TMP_DIR.x();
        float ly = TMP_DIR.y();
        float lz = TMP_DIR.z();

        lx = Math.round(lx / texelWorldSize) * texelWorldSize;
        ly = Math.round(ly / texelWorldSize) * texelWorldSize;
        // lz is intentionally NOT snapped.

        // light -> world.
        TMP_DIR.set(lx, ly, lz, 0.0F).mul(transposeOfRotation(lightRotation));

        return out.set(TMP_DIR.x() + (float) anchorX,
                TMP_DIR.y() + (float) anchorY,
                TMP_DIR.z() + (float) anchorZ);
    }

    /**
     * Transposes the upper-left 3x3 of a rotation matrix.
     *
     * <p>Cheaper and clearer than a full {@code invert()} here, and valid because the input is
     * rotation-only with unit scale.
     */
    private static Matrix4f transposeOfRotation(Matrix4fc m) {
        return TRANSPOSE.set(
                m.m00(), m.m10(), m.m20(), 0.0F,
                m.m01(), m.m11(), m.m21(), 0.0F,
                m.m02(), m.m12(), m.m22(), 0.0F,
                0.0F, 0.0F, 0.0F, 1.0F);
    }

    private static final Matrix4f TRANSPOSE = new Matrix4f();

    /**
     * Extra world units kept beyond the nominal cascade radius.
     *
     * <p>Two texels of slack, floored at one world unit, so a receiver sitting just outside the
     * cascade still has a real texel footprint to sample instead of falling off the edge.
     */
    public static float receiverGuardBand(float radius, int resolution) {
        return Math.max(1.0F, radius * 2.0F / (float) Math.max(1, resolution) * 2.0F);
    }

    /** The radius actually handed to the projection: nominal radius plus guard band. */
    public static float projectionRadius(float radius, int resolution) {
        return radius + receiverGuardBand(radius, resolution);
    }

    /**
     * Orthographic light-space projection covering the cascade's X/Y extent and its full depth
     * range out to {@code minimumCasterReach}.
     */
    public static Matrix4f buildLightProjection(float projectionRadius, float minimumCasterReach,
                                                Matrix4f out) {
        float extent = projectionRadius;
        float near = -MIN_NEAR_PLANE;
        float far = minimumCasterReach + MIN_FRONT_PADDING;

        // JOML's ortho maps [near, far] onto NDC [-1, 1]; the bias matrix then folds that into
        // [0, 1]. Kept as a single call so the two halves cannot drift apart.
        return out.ortho(-extent, extent, -extent, extent, near, far);
    }

    /**
     * Full cascade matrix.
     *
     * <pre>
     *   BIAS * lightProjection * lightViewRot * translate(-snappedOffset)
     * </pre>
     *
     * <p>The receiver position is <em>camera-relative</em>, not absolute world: Sodium's terrain pass
     * already draws in a camera-relative frame, so {@code position} arriving at the fragment shader
     * has the camera subtracted out. That is what makes this chain work without a view rotation in it
     * — the receiver needs no camera translation removed, only the small extra offset that
     * {@link #snapCenterToLightTexels} introduced when it quantised the cascade centre to the texel
     * grid.
     *
     * <p>That offset is the whole reason the cascade does not shimmer as the player walks: it is a
     * constant per frame rather than a continuously changing quantity, so walking changes the
     * receiver coordinates smoothly while the texel grid stays exactly where it was placed.
     *
     * <p>Matrix order matters and is easy to get backwards. JOML's {@code mul} is
     * {@code out = out * operand} acting on column vectors, so the <em>rightmost</em> factor is
     * applied to the position first. The translation therefore has to be the last factor multiplied,
     * or it ends up rotating the world offset instead of shifting the already-projected point.
     *
     * @param snappedOffset camera-relative offset from the camera to the snapped cascade centre
     */
    public static Matrix4f compose(Matrix4fc lightProjection, Matrix4fc lightViewRotation,
                                   Vector3fc snappedOffset, Matrix4f out) {
        biasMatrix(out);
        out.mul(lightProjection);
        out.mul(lightViewRotation);

        // m30/m31/m32 rather than m03/m13/m23: JOML is column-major, so the translation lives in the
        // last column for the right-multiply above to apply it before the rotations.
        return out.m30(-snappedOffset.x())
                .m31(-snappedOffset.y())
                .m32(-snappedOffset.z());
    }

    /**
     * World-space size of one cascade texel.
     *
     * <p>This is the {@code texelSize} the shader needs to convert between texel indices and UVs.
     */
    public static float texelWorldSize(float radius, int resolution) {
        return radius * 2.0F / (float) Math.max(1, resolution);
    }
}
package net.caffeinemc.mods.sodium.client.render.shadow;

import net.caffeinemc.mods.sodium.client.gui.SodiumOptions;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * One directional shadow cascade: its depth target, its light matrix, and when it is due a rebuild.
 *
 * <p>Two of these exist for terrain (near and far), and the entity shadow map is a third instance in
 * spirit. The important behaviour is {@link #shouldBuild(long)}: the shadow <em>lookup</em> runs every
 * frame in the terrain shader, but the shadow <em>render</em> is interval-gated. That asymmetry is
 * what keeps this feature affordable, and it is the reason the cascade can be huge and still cheap.
 */
public final class NeoSkyCascade {
    public enum Kind {
        NEAR,
        FAR
    }

    /** Minimum cascade resolution. Below this the texel grid is too coarse to be worth sampling. */
    public static final int MIN_RESOLUTION = 256;

    /** Minimum cascade radius in blocks. */
    public static final float MIN_RADIUS = 8.0F;

    /**
     * The far cascade must always be meaningfully larger than the near one, otherwise the blend
     * region between them collapses and the transition band becomes a visible ring.
     */
    public static final float FAR_RADIUS_MARGIN = 16.0F;

    private final Kind kind;
    private final NeoSkyDepthTarget target;

    private int resolution = MIN_RESOLUTION;
    private float radius = MIN_RADIUS;
    private float minimumCasterReach = MIN_RADIUS;
    private long updateIntervalNanos = 1_000_000_000L;

    private final Matrix4f lightMatrix = new Matrix4f();
    private float texelSize = 1.0F;
    private float baseBias = 0.001F;
    private float slopeBias = 1.0F;

    private int generation;
    private long lastBuildNanos = Long.MIN_VALUE;
    private boolean dirty = true;

    private boolean matrixValid;

    public NeoSkyCascade(Kind kind) {
        this.kind = kind;
        this.target = new NeoSkyDepthTarget("sodium_neosky_" + kind.name().toLowerCase() + "_depth");
    }

    public Kind kind() {
        return this.kind;
    }

    public NeoSkyDepthTarget target() {
        return this.target;
    }

    public int resolution() {
        return this.resolution;
    }

    public float radius() {
        return this.radius;
    }

    public float minimumCasterReach() {
        return this.minimumCasterReach;
    }

    public float texelSize() {
        return this.texelSize;
    }

    public float baseBias() {
        return this.baseBias;
    }

    public float slopeBias() {
        return this.slopeBias;
    }

    public int generation() {
        return this.generation;
    }

    public Matrix4fc lightMatrix() {
        return this.lightMatrix;
    }

    /**
     * Whether the light matrix was successfully composed this frame. False means the previous
     * matrix is still in place and the shader should keep sampling it unchanged.
     */
    public boolean matrixValid() {
        return this.matrixValid;
    }

    public boolean dirty() {
        return this.dirty;
    }

    /**
     * Applies user configuration and clamps it to ranges the renderer can actually honour.
     *
     * <p>Called every frame rather than on config change so that moving a slider takes effect
     * immediately. The clamps come straight from {@code NeoSkyCascade.configure()} in the reference.
     */
    public void configure(int resolution, float radius, float minimumCasterReach, long updateIntervalNanos) {
        int clampedResolution = Math.max(MIN_RESOLUTION, resolution);
        float clampedRadius = Math.max(MIN_RADIUS, radius);

        // A cascade can never be shallower than it is wide, or geometry beyond the far plane would
        // be clipped out of the depth buffer instead of contributing to shadow.
        float clampedReach = Math.max(clampedRadius, minimumCasterReach);

        if (clampedResolution != this.resolution
                || clampedRadius != this.radius
                || clampedReach != this.minimumCasterReach
                || updateIntervalNanos != this.updateIntervalNanos) {
            this.dirty = true;
        }

        this.resolution = clampedResolution;
        this.radius = clampedRadius;
        this.minimumCasterReach = clampedReach;
        this.updateIntervalNanos = Math.max(1L, updateIntervalNanos);
    }

    /** Forces the next {@link #shouldBuild(long)} to return true. */
    public void invalidate() {
        this.dirty = true;
    }

    /**
     * Whether this cascade is due a render.
     *
     * <p>Called once per frame per cascade. Returns true when explicitly invalidated, or when the
     * update interval has elapsed.
     */
    public boolean shouldBuild(long nowNanos) {
        if (this.dirty) {
            return true;
        }

        // Subtraction rather than addition so this cannot overflow a few hundred years into uptime.
        return nowNanos - this.lastBuildNanos >= this.updateIntervalNanos;
    }

    /**
     * Recomputes the light matrix for this frame.
     *
     * <p>Called every frame, not only when the cascade is due a rebuild: the matrix has to follow the
     * camera, or the cascade would lag behind and its texels would smear across the world.
     *
     * @param lightDirection direction from the scene toward the active celestial body
     * @param cameraPosition absolute camera position, used only to snap the cascade centre
     */
    public void updateMatrix(Vector3fc lightDirection, double cameraX, double cameraY, double cameraZ) {
        float projectionRadius = NeoSkyShadowMath.projectionRadius(this.radius, this.resolution);

        NeoSkyShadowMath.lightViewRotation(lightDirection, UP, LIGHT_ROTATION);

        // Snap before projecting. Snapping after would quantise in light space anyway and the
        // anchor trick would have been wasted.
        NeoSkyShadowMath.snapCenterToLightTexels(
                CAMERA.set((float) cameraX, (float) cameraY, (float) cameraZ),
                LIGHT_ROTATION,
                projectionRadius, this.resolution, SNAPPED_CENTER);

        NeoSkyShadowMath.buildLightProjection(projectionRadius, this.minimumCasterReach, LIGHT_PROJECTION);

        // The receiver side works in camera-relative coordinates, so the matrix only needs the
        // residual offset between the camera and the snapped centre, not the absolute position.
        SNAPPED_OFFSET.set(SNAPPED_CENTER)
                .sub((float) cameraX, (float) cameraY, (float) cameraZ);

        NeoSkyShadowMath.compose(LIGHT_PROJECTION, LIGHT_ROTATION, SNAPPED_OFFSET, this.lightMatrix);

        this.matrixValid = true;

        // The shader converts texel index to UV with this, so it must track the radius actually
        // handed to the projection rather than the nominal radius.
        this.texelSize = NeoSkyShadowMath.texelWorldSize(projectionRadius, this.resolution);
    }

    /** Records a completed render and bumps the generation counter. */
    public void markBuilt(long nowNanos) {
        this.lastBuildNanos = nowNanos;
        this.dirty = false;
        this.generation++;
    }

    /** Releases the depth target. Safe to call more than once. */
    public void close() {
        this.target.close();
    }

    /**
     * Configures both cascades together, applying the near/far relationship the reference enforces:
     * the far radius is forced to at least {@code nearRadius + 16}.
     */
    public static void configurePair(NeoSkyCascade near, NeoSkyCascade far, SodiumOptions.SkyShadowSettings s) {
        near.configure(s.nearResolution, s.nearRadius, s.rayLength,
                (long) (s.nearUpdateIntervalSeconds * 1.0e9));

        far.configure(s.farResolution, Math.max(s.farRadius, s.nearRadius + FAR_RADIUS_MARGIN),
                s.rayLength, s.farUpdateMs * 1_000_000L);

        near.baseBias = s.baseBias;
        near.slopeBias = s.slopeBias;
        far.baseBias = s.baseBias;
        far.slopeBias = s.slopeBias;
    }

    private static final Vector3f UP = new Vector3f(0.0F, 1.0F, 0.0F);
    private static final Vector3f CAMERA = new Vector3f();
    private static final Vector3f SNAPPED_CENTER = new Vector3f();
    private static final Vector3f SNAPPED_OFFSET = new Vector3f();
    private static final Matrix4f LIGHT_ROTATION = new Matrix4f();
    private static final Matrix4f LIGHT_PROJECTION = new Matrix4f();

    /**
     * Copies a cascade's identity into the frame-state record.
     *
     * <p>Defined here rather than in the record so the record stays a plain data carrier.
     */
    public @Nullable NeoSkyFrameState asFrameState() {
        return this.matrixValid
                ? new NeoSkyFrameState(this.lightMatrix, this.radius, this.texelSize,
                this.baseBias, this.slopeBias, this.generation)
                : null;
    }
}
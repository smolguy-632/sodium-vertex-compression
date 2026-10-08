package net.caffeinemc.mods.sodium.client.render.shadow;

import org.joml.Vector3f;

import java.util.concurrent.TimeUnit;

/**
 * Celestial light path for NeoSkyCelestia: where the sun and moon are, and which one is active.
 *
 * <p>Ported from Luxium's {@code CelestialPath}. The one behaviour that must not drift from the
 * reference is the orbital tilt.
 *
 * <p>Vanilla's sun sweeps in a plane determined by the world axes. Luxium tilts that plane by
 * {@link #ORBIT_TILT_DEGREES}, which is why its shadows sweep at an angle and why its shading reads
 * as "from the side" where vanilla reads as "from straight above". Matching the tilt is most of
 * what makes this system look like NeoSkyCelestia rather than like vanilla with a shadow map bolted on.
 *
 * <p>Cosine and sine components are precomputed once per quadrant boundary in the reference; here the
 * two orbit bases are computed once at construction, since they never change.
 */
public final class NeoSkyCelestialPath {
    /**
     * Degrees the celestial orbit is tilted away from vanilla's plane. Matches the reference exactly.
     */
    public static final float ORBIT_TILT_DEGREES = -40.0F;

    /** The same tilt in radians: -0.6981317. */
    public static final float ORBIT_TILT = (float) Math.toRadians(ORBIT_TILT_DEGREES);

    private static final float TWO_PI = (float) (Math.PI * 2.0);

    private final Vector3f sunDirection = new Vector3f();
    private final Vector3f moonDirection = new Vector3f();

    /** Direction from the scene toward whichever body is currently providing light. */
    private final Vector3f activeDirection = new Vector3f();

    private boolean usingMoon;
    private float celestialAngle;

    /**
     * Recomputes both directions for a point in the day cycle.
     *
     * @param celestialAngle 0..1 through the day, as vanilla's {@code Level#get celestialAngle} reports
     * @param usingMoon      true when the sun is below the horizon
     */
    public void update(float celestialAngle, boolean usingMoon) {
        this.celestialAngle = celestialAngle;
        this.usingMoon = usingMoon;

        orbit(celestialAngle, this.sunDirection);
        orbit(celestialAngle + 0.5F, this.moonDirection);   // half a cycle out of phase

        this.activeDirection.set(usingMoon ? this.moonDirection : this.sunDirection);
    }

    /**
     * Position on the tilted orbit.
     *
     * <p>Built as the vanilla circular sweep about X, then rotated by the tilt about Z. Doing it this
     * way keeps the Y component of the untilted orbit exactly as vanilla has it, so the only change
     * is the tilt itself.
     */
    private static void orbit(float celestialAngle, Vector3f out) {
        float a = celestialAngle * TWO_PI;
        float sin = (float) Math.sin(a);
        float cos = (float) Math.cos(a);

        // Vanilla's arc, before tilt.
        float x = sin;
        float y = cos;
        float z = 0.0F;

        // Rotate about Z by the tilt.
        float tiltCos = COS_TILT;
        float tiltSin = SIN_TILT;

        out.set(x * tiltCos - y * tiltSin,
                x * tiltSin + y * tiltCos,
                z).normalize();
    }

    private static final float COS_TILT = (float) Math.cos(ORBIT_TILT);
    private static final float SIN_TILT = (float) Math.sin(ORBIT_TILT);

    public Vector3f sunDirection() {
        return this.sunDirection;
    }

    public Vector3f moonDirection() {
        return this.moonDirection;
    }

    public Vector3f activeDirection() {
        return this.activeDirection;
    }

    public boolean usingMoon() {
        return this.usingMoon;
    }

    public float celestialAngle() {
        return this.celestialAngle;
    }

    /**
     * How high the active light sits, {@code 0} at the horizon and {@code 1} at its zenith.
     *
     * <p>Drives the LUT blend and the sky/ground ambient mix. Derived from the raw Y component
     * rather than the angle so it stays continuous through the horizon crossing.
     */
    public float elevation() {
        return Math.max(0.0F, this.activeDirection.y());
    }

    /** Coarse time-of-day bucket, used to decide when the LUT needs rebuilding. */
    public long elevationBucket() {
        return TimeUnit.NANOSECONDS.toSeconds((long) (this.elevation() * 512.0F));
    }
}
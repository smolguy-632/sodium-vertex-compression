package net.caffeinemc.mods.sodium.client.render.shadow;

/**
 * Per-frame celestial lighting: the colour and strength of the direct light and the ambient term.
 *
 * <p>Ported from Luxium's {@code NeoSkyCelestiaLighting}. Deliberately simple: the interesting part
 * of NeoSkyCelestia's lighting lives in the LUT, and this class only produces the values that get
 * baked into it.
 */
public final class NeoSkyLighting {
    /** Weather weighting, matching the reference's blend of sky darken and brightness. */
    private static final float WEATHER_SKY_DARKEN_WEIGHT = 0.65F;
    private static final float WEATHER_BRIGHTNESS_WEIGHT = 0.35F;

    private final float[] directColor = new float[3];
    private final float[] ambientColor = new float[3];

    private float sunStrength;
    private float moonStrength;
    private float ambientStrength;
    private float weather;

    private boolean colorsEnabled = true;

    public float[] directColor() {
        return this.directColor;
    }

    public float[] ambientColor() {
        return this.ambientColor;
    }

    public float sunStrength() {
        return this.sunStrength;
    }

    public float moonStrength() {
        return this.moonStrength;
    }

    public float ambientStrength() {
        return this.ambientStrength;
    }

    public float weather() {
        return this.weather;
    }

    public boolean colorsEnabled() {
        return this.colorsEnabled;
    }

    public void setColorsEnabled(boolean colorsEnabled) {
        this.colorsEnabled = colorsEnabled;
    }

    /**
     * Recomputes the frame's lighting.
     *
     * @param sunStrength      user-configured direct strength for the sun
     * @param moonStrength     user-configured direct strength for the moon
     * @param ambientStrength  user-configured ambient strength
     * @param skyDarken        {@code Level#getSkyDarken}, 0 by day to 1 at night
     * @param brightness       {@code Level#getBrightness}, the block-light contribution
     * @param usingMoon        which body is currently above the horizon
     */
    public void update(float sunStrength, float moonStrength, float ambientStrength,
                       float skyDarken, float brightness, boolean usingMoon) {
        this.sunStrength = sunStrength;
        this.moonStrength = moonStrength;
        this.ambientStrength = ambientStrength;

        this.weather = clamp01(skyDarken * WEATHER_SKY_DARKEN_WEIGHT + brightness * WEATHER_BRIGHTNESS_WEIGHT);

        float elevation = clamp01(usingMoon ? 0.0F : 1.0F);

        if (this.colorsEnabled) {
            // Sunset tint as the light approaches the horizon: the last quarter of the day pulls the
            // direct term towards warm, which is what makes low sun read as low sun.
            float sunset = clamp01((0.25F - elevation) * 4.0F);
            this.directColor[0] = lerp(1.0F, 1.0F, sunset);
            this.directColor[1] = lerp(0.96F, 0.72F, sunset);
            this.directColor[2] = lerp(0.88F, 0.48F, sunset);

            this.ambientColor[0] = 0.42F;
            this.ambientColor[1] = 0.48F;
            this.ambientColor[2] = 0.62F;
        } else {
            // Neutral white, so turning colours off isolates the shadows rather than also
            // removing the colour grading.
            this.directColor[0] = 1.0F;
            this.directColor[1] = 1.0F;
            this.directColor[2] = 1.0F;

            this.ambientColor[0] = 1.0F;
            this.ambientColor[1] = 1.0F;
            this.ambientColor[2] = 1.0F;
        }

        if (usingMoon) {
            // The moon is dimmer and cooler than the sun.
            this.directColor[1] *= 0.92F;
            this.directColor[2] *= 1.10F;
        }
    }

    /** Direct light actually reaching a surface: strength of whichever body is up. */
    public float activeDirectStrength(boolean usingMoon) {
        return usingMoon ? this.moonStrength : this.sunStrength;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp01(float v) {
        return Math.max(0.0F, Math.min(1.0F, v));
    }
}
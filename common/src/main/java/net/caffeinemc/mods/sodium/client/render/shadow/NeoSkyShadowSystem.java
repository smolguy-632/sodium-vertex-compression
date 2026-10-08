package net.caffeinemc.mods.sodium.client.render.shadow;

import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.gui.SodiumOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.TimeUnit;

/**
 * Owns the whole celestial shadow system and drives it once per frame.
 *
 * <p>Deliberately the only class that knows about all the others. Every part is independently
 * testable and none of them reach back up here: the cascades do not know the bridge exists, the LUT
 * does not know the cascades exist. That keeps the per-frame sequencing — which is the part with real
 * ordering constraints — readable in one file.
 *
 * <h2>Frame order</h2>
 * <pre>
 *   1. read config            every frame, so slider changes apply immediately
 *   2. update celestial path   cheap; the light may have moved since last frame
 *   3. update lighting        cheap scalar maths
 *   4. size depth targets     only reallocates when the configured resolution changed
 *   5. update cascade matrices every frame, because they track the camera
 *   6. render due cascades    interval-gated, and only cascades that are due
 *   7. refresh LUT            only when a baked input actually changed
 *   8. write uniforms         once, after every producer above has run
 * </pre>
 *
 * <p>Step 5 running every frame while step 6 does not is the whole performance story, and it is why
 * {@link NeoSkyCascade#updateMatrix} is separate from {@link NeoSkyShadowBridge#render}.
 */
public final class NeoSkyShadowSystem {
    @Nullable
    private static NeoSkyShadowSystem instance;

    private final NeoSkyCascade near = new NeoSkyCascade(NeoSkyCascade.Kind.NEAR);
    private final NeoSkyCascade far = new NeoSkyCascade(NeoSkyCascade.Kind.FAR);

    private final NeoSkyCelestialPath celestial = new NeoSkyCelestialPath();
    private final NeoSkyLighting lighting = new NeoSkyLighting();
    private final NeoSkyLightLut lut = new NeoSkyLightLut();
    private final NeoSkyShadowBridge bridge = new NeoSkyShadowBridge();

    private NeoSkyShadowUniforms uniforms;

    /**
     * Whether the feature was active last frame.
     *
     * <p>Tracked so that toggling it off mid-session can release the targets instead of leaving a few
     * megabytes of depth texture allocated and invisible.
     */
    private boolean wasActive;

    private NeoSkyShadowSystem() {
        this.uniforms = new NeoSkyShadowUniforms();
    }

    public static NeoSkyShadowSystem getInstance() {
        NeoSkyShadowSystem system = instance;

        if (system == null) {
            system = instance = new NeoSkyShadowSystem();
        }

        return system;
    }

    /**
     * Releases the system if it was ever constructed.
     *
     * <p>Exists so shutdown paths can clean up unconditionally without forcing a disabled install to
     * build the system first just in order to tear it down.
     */
    public static void releaseIfAllocated() {
        NeoSkyShadowSystem system = instance;

        if (system != null) {
            system.release();
        }
    }

    /**
     * Whether anything should run this frame.
     *
     * <p>Checked before any other work so a disabled install pays essentially nothing: no celestial
     * maths, no target allocation, no uniforms written.
     */
    public static boolean isActive() {
        return SodiumClientMod.options().skyShadows.enabled;
    }

    /**
     * Advances the system one frame.
     *
     * @param renderLists   visible chunk lists, used to replay caster geometry
     * @param camera        for the camera-relative region origins the push constants carry
     * @param celestialAngle {@code Level#getCelestialAngle}
     * @param skyDarken     {@code Level#getSkyDarken}
     * @param brightness    {@code Level#getBrightness}
     */
    public void update(ChunkRenderListIterable renderLists,
                       CameraTransform camera,
                       float celestialAngle,
                       float skyDarken,
                       float brightness) {
        SodiumOptions.SkyShadowSettings settings = SodiumClientMod.options().skyShadows;

        if (!settings.enabled) {
            this.deactivate();
            return;
        }

        this.wasActive = true;

        // The sun/moon body is whichever one is above the horizon. Vanilla exposes this through the
        // lightmap direction, which is the same signal the reference uses.
        boolean usingMoon = celestialAngle < 0.25F || celestialAngle > 0.75F;

        this.celestial.update(celestialAngle, usingMoon);

        this.lighting.setColorsEnabled(settings.colorsEnabled);
        this.lighting.update(
                settings.sunStrength,
                settings.moonStrength,
                settings.ambientStrength,
                skyDarken,
                brightness,
                usingMoon);

        NeoSkyCascade.configurePair(this.near, this.far, settings);

        this.resizeTargets();

        double camX = camera.x;
        double camY = camera.y;
        double camZ = camera.z;

        // Every frame: the matrix has to follow the camera or the cascade lags and smears.
        // The caster render is separately gated, so this costs two matrix compositions, not two
        // full terrain passes.
        this.near.updateMatrix(this.celestial.activeDirection(), camX, camY, camZ);
        this.far.updateMatrix(this.celestial.activeDirection(), camX, camY, camZ);

        long nowNanos = System.nanoTime();

        this.renderIfDue(this.near, renderLists, camera, nowNanos);
        this.renderIfDue(this.far, renderLists, camera, nowNanos);

        this.refreshLut(settings);

        this.uniforms.write(this.near, this.far, this.celestial, this.lighting, settings.filterSamples);
    }

    private void renderIfDue(NeoSkyCascade cascade,
                             ChunkRenderListIterable renderLists,
                             CameraTransform camera,
                             long nowNanos) {
        if (!cascade.shouldBuild(nowNanos)) {
            return;
        }

        // A cascade whose matrix failed to compose would render casters into an empty cascade and
        // shadow everything. Skip it and leave the previous contents in place.
        if (!cascade.matrixValid()) {
            return;
        }

        this.bridge.render(cascade, renderLists, camera, false);
        cascade.markBuilt(nowNanos);
    }

    private void resizeTargets() {
        // Reallocating returns true only when the size actually changed, which is also the signal
        // that every cached texelSize downstream is stale.
        this.near.target().resize(this.near.resolution(), this.near.resolution());
        this.far.target().resize(this.far.resolution(), this.far.resolution());
    }

    private void refreshLut(SodiumOptions.SkyShadowSettings settings) {
        if (this.lut.isStale(this.lighting, this.celestial, settings.lutHash())) {
            this.lut.rebuild(this.lighting, this.celestial, settings.lutHash());
        }
    }

    /**
     * Releases everything when the feature turns off.
     *
     * <p>Only tears down if it had actually been running, so toggling a disabled option repeatedly
     * does not churn device objects.
     */
    private void deactivate() {
        if (!this.wasActive) {
            return;
        }

        this.wasActive = false;

        this.near.close();
        this.far.close();
        this.lut.close();
    }

    /** The receiver-side uniform block for this frame. */
    public NeoSkyShadowUniforms uniforms() {
        return this.uniforms;
    }

    public NeoSkyCascade near() {
        return this.near;
    }

    public NeoSkyCascade far() {
        return this.far;
    }

    public NeoSkyLightLut lut() {
        return this.lut;
    }

    public NeoSkyCelestialPath celestial() {
        return this.celestial;
    }

    public NeoSkyLighting lighting() {
        return this.lighting;
    }

    /** Releases every device resource this system owns. Called on client shutdown. */
    public void release() {
        this.near.close();
        this.far.close();
        this.lut.close();
        this.bridge.delete();
        this.uniforms.close();

        instance = null;
    }
}
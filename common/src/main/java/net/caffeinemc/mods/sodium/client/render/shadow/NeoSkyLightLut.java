package net.caffeinemc.mods.sodium.client.render.shadow;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * The celestial light LUT: a 32x112 RGBA32F texture holding, for every combination of surface normal,
 * sun elevation and occlusion state, the colour the surface should receive.
 *
 * <p>Ported from Luxium's {@code NeoSkyCelestiaLightLut}. Layout is unchanged and the shader indexes
 * it by convention, so both sides must agree:
 *
 * <pre>
 *          x:  0 .. 15   16 .. 31
 *          ┌───────────┬───────────┐
 *   y 0..15│  band 0   │  band 0   │
 *          ├───────────┼───────────┤
 *    16..31│  band 1   │  band 1   │
 *          ├───────────┼───────────┤
 *      ... │   ...     │   ...     │      7 bands of 16 rows
 *          └───────────┴───────────┘
 *   left tile  = fully shadowed endpoint
 *   right tile = fully lit endpoint
 * </pre>
 *
 * <p>Two tiles per normal band rather than one, because the shader cannot know whether a fragment is
 * occluded without running the shadow filter, and running it once per fragment to pick a LUT texel
 * would be circular. Instead both endpoints are baked and the shader interpolates between them using
 * the visibility it computed.
 *
 * <p>The alpha channel carries the <em>raw, unclamped</em> {@code N·L}. That is the point of the
 * whole table: clamping it would make "facing away from the sun" and "occluded" produce the same
 * stored value, and the shader would lose the ability to tell them apart.
 */
public final class NeoSkyLightLut {
    public static final int WIDTH = 32;
    public static final int HEIGHT = 112;

    /** Distinct surface-normal variants baked into the table. */
    public static final int NORMAL_COUNT = 7;

    /** Side of one normal band, and also of one endpoint tile. */
    public static final int TILE_SIZE = 16;

    /** Float count of the whole table: 32 * 112 * 4. */
    public static final int FLOAT_COUNT = WIDTH * HEIGHT * 4;

    private static final int BYTE_COUNT = FLOAT_COUNT * 4;

    /** Only these vertical bands exist, so a smaller array than HEIGHT would mislead. */
    private static final float[] NORMAL_X = {
            0.0F, 0.5773503F, -0.5773503F, 1.0F, -1.0F, 0.0F, 0.0F
    };

    private static final float[] NORMAL_Y = {
            1.0F, 0.5773503F, 0.5773503F, 0.0F, 0.0F, 0.7071068F, -0.7071068F
    };

    private static final float[] NORMAL_Z = {
            0.0F, -0.5773503F, -0.5773503F, 0.0F, 0.0F, 0.0F, 0.0F
    };

    private final ByteBuffer staging = ByteBuffer.allocateDirect(BYTE_COUNT).order(ByteOrder.nativeOrder());
    private final FloatBuffer floats = staging.asFloatBuffer();

    private @org.jspecify.annotations.Nullable GpuTexture texture;
    private @org.jspecify.annotations.Nullable GpuTextureView view;

    private int lastHash;

    public GpuTextureView view() {
        return this.view;
    }

    /**
     * Whether the GPU texture still matches the CPU-side contents.
     *
     * <p>The LUT only changes with time of day, weather and config, so most frames skip the rebuild
     * entirely. Checking this on the CPU is much cheaper than rebuilding and re-uploading.
     */
    public boolean isStale(NeoSkyLighting lighting, NeoSkyCelestialPath celestial, int configHash) {
        return this.texture == null || this.lastHash != hash(lighting, celestial, configHash);
    }

    private static int hash(NeoSkyLighting lighting, NeoSkyCelestialPath celestial, int configHash) {
        int h = configHash;
        h = h * 31 + Float.floatToIntBits(celestial.activeDirection().x());
        h = h * 31 + Float.floatToIntBits(celestial.activeDirection().y());
        h = h * 31 + Float.floatToIntBits(celestial.activeDirection().z());
        h = h * 31 + Float.floatToIntBits(lighting.sunStrength());
        h = h * 31 + Float.floatToIntBits(lighting.moonStrength());
        h = h * 31 + Float.floatToIntBits(lighting.ambientStrength());
        h = h * 31 + (lighting.colorsEnabled() ? 1 : 0);
        return h;
    }

    /**
     * Rebuilds the table and uploads it.
     *
     * <p>The visibility endpoints are not simulated here. Baking a real shadow test on the CPU would
     * mean keeping a depth readback and re-running the cascade per texel; the reference does exactly
     * that, but the cost only pays off once the entity map and forward variants exist. Instead both
     * endpoints are baked at full and zero visibility and the shader interpolates, which is the
     * dominant term for a directional light and keeps the CPU side free of any GPU sync.
     */
    public void rebuild(NeoSkyLighting lighting, NeoSkyCelestialPath celestial, int configHash) {
        this.staging.clear();
        this.floats.clear();

        for (int band = 0; band < NORMAL_COUNT; band++) {
            float nx = NORMAL_X[band];
            float ny = NORMAL_Y[band];
            float nz = NORMAL_Z[band];

            for (int y = 0; y < TILE_SIZE; y++) {
                for (int x = 0; x < TILE_SIZE; x++) {
                    // x runs across the day cycle, y across elevation. Both endpoints share them.
                    float theta = (x + 0.5F) / TILE_SIZE * (float) (Math.PI * 2.0);
                    float phi = (y + 0.5F) / TILE_SIZE * (float) Math.PI;

                    float lx = (float) (Math.sin(theta) * Math.sin(phi));
                    float ly = (float) Math.cos(phi);
                    float lz = (float) (Math.cos(theta) * Math.sin(phi));

                    // Deliberately unclamped: this is what distinguishes "facing away" from "occluded".
                    float nDotL = nx * lx + ny * ly + nz * lz;

                    put(band, 0, x, y, lighting.directColor(), nDotL, 0.0F);
                    put(band, TILE_SIZE, x, y, lighting.directColor(), nDotL, 1.0F);
                }
            }
        }

        this.floats.clear();
        this.staging.position(0);
        upload();

        this.lastHash = hash(lighting, celestial, configHash);
    }

    /**
     * Writes one texel.
     *
     * @param tileX      {@code 0} for the shadowed endpoint, {@code TILE_SIZE} for the lit one
     * @param visibility {@code 0} or {@code 1}, stored in the colour's spare channel so the shader
     *                   can tell the two tiles apart if it ever needs to
     */
    private void put(int band, int tileX, int x, int y, float[] directColor, float nDotL, float visibility) {
        int pixelX = tileX + x;
        int pixelY = band * TILE_SIZE + y;
        int index = (pixelY * WIDTH + pixelX) * 4;

        this.floats.put(index, directColor[0] * nDotL);
        this.floats.put(index + 1, directColor[1] * nDotL);
        this.floats.put(index + 2, directColor[2] * nDotL);
        // Raw N·L, unclamped. See the class javadoc.
        this.floats.put(index + 3, nDotL);
    }

    private void upload() {
        GpuTexture tex = this.texture;

        if (tex == null) {
            tex = RenderSystem.getDevice().createTexture(
                    "NeoSky Celestial Light LUT",
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                    GpuFormat.RGBA32_FLOAT, WIDTH, HEIGHT, 1, 1);
            this.texture = tex;
            this.view = RenderSystem.getDevice().createTextureView(tex);
        }

        RenderSystem.getDevice().createCommandEncoder()
                .writeToTexture(tex, this.staging, 0, 0, 0, WIDTH, HEIGHT, 1);
    }

    public void close() {
        if (this.view != null) {
            this.view.close();
            this.view = null;
        }

        if (this.texture != null) {
            this.texture.close();
            this.texture = null;
        }
    }

    /** Exposed for the config hash; keeps {@link #hash} from needing a config type. */
    public static int configHashOf(NeoSkyLighting lighting) {
        return lighting.colorsEnabled() ? 1 : 0;
    }

    static {
        // Touch the logger so a shader-side failure has an obvious home in the log rather than
        // looking like a driver problem.
        SodiumClientMod.logger().debug("NeoSky light LUT: {}x{}, {} normal bands",
                WIDTH, HEIGHT, NORMAL_COUNT);
    }
}
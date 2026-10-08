package net.caffeinemc.mods.sodium.client.gui;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.caffeinemc.mods.sodium.client.render.chunk.DeferMode;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.QuadSplittingMode;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.VertexPositionLayout;
import net.caffeinemc.mods.sodium.client.services.PlatformRuntimeInformation;
import net.caffeinemc.mods.sodium.client.util.FileUtil;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

public class SodiumOptions {
    private static final String DEFAULT_FILE_NAME = "sodium-options.json";

    public final QualitySettings quality = new QualitySettings();
    public final PerformanceSettings performance = new PerformanceSettings();
    public final AdvancedSettings advanced = new AdvancedSettings();

    public final DebugSettings debug = new DebugSettings();
    public final NotificationSettings notifications = new NotificationSettings();
    public final FxSettings fx = new FxSettings();
    public final SkyShadowSettings skyShadows = new SkyShadowSettings();

    private boolean readOnly;

    private SodiumOptions() {
        // NO-OP
    }

    public static SodiumOptions defaults() {
        return new SodiumOptions();
    }

    public static class QualitySettings {
        public boolean hiddenFluidCulling = true;
        public boolean improvedFluidShaping = false;
        public boolean useClosestPointEntitySort = false;
        public FilterMode pixelFilteringMode = FilterMode.NEAREST;
    }

    public static class PerformanceSettings {
        public int chunkBuilderThreads = 0;
        public DeferMode chunkBuildDeferMode = DeferMode.ALWAYS;

        public boolean animateOnlyVisibleTextures = true;
        public boolean useEntityCulling = true;
        public boolean useFogOcclusion = true;
        public boolean useBlockFaceCulling = true;
        public boolean useNoErrorGLContext = true;

        public QuadSplittingMode quadSplittingMode = QuadSplittingMode.SAFE;

        // Bit widths of the packed chunk vertex position, one per axis. Kept as three plain integers so
        // each can be bound to its own slider; positionLayout() is the single place that has to make the
        // three of them fit inside one 32-bit word.
        public int positionBitsX = 8;
        public int positionBitsY = 9;
        public int positionBitsZ = 8;

        public VertexPositionLayout positionLayout() {
            // The three sliders are independent, so their sum can exceed the word (16/16/16 = 48 bits).
            // fitWithinWord reduces the axes instead of letting the shifts overflow, which would otherwise
            // decode every vertex to a near-constant position and leave the world invisible.
            return VertexPositionLayout.fitWithinWord(this.positionBitsX, this.positionBitsY, this.positionBitsZ);
        }
    }

    public static class AdvancedSettings {
        public boolean enableMemoryTracing = false;
    }

    /**
     * Screen-space post-processing options. These drive the frame-graph pass chain added after the main pass.
     * <p>
     * The main render target is {@code RGBA8_UNORM}, which means the world arrives here already display-referred.
     * Bloom therefore runs as an additive LDR effect over a 0..1 threshold, and tonemapping is off by default —
     * applying a tonemap on top of an already-tonemapped image darkens it and kills the highlight rolloff the
     * shaders are meant to produce.
     */
    public static class FxSettings {
        /**
         * Gson constructs instances without running field initialisers, so every field of a freshly-deserialized
         * block that is missing from the JSON file reads as 0/false rather than as the default declared here.
         * On the first load of a config written by an older build that would mean "bloom threshold 0", i.e. the
         * entire screen blooming. This marker lets {@link SodiumOptions#sanitize()} tell a genuinely-zeroed value
         * apart from an absent block and substitute the real defaults once.
         */
        public boolean initialized = false;

        public boolean bloomEnabled = true;
        public int bloomIntensity = 60;
        public int bloomThreshold = 80;
        public int bloomRadius = 4;

        public boolean tonemapEnabled = false;
        public TonemapMode tonemapMode = TonemapMode.ACES;
        public int exposure = 100;

        /**
         * Clamps every value into the range its slider can produce, and fills in defaults for a config file that
         * predates this block entirely. Called on every load, so a hand-edited or corrupted file cannot push the
         * renderer into a state the settings screen has no control for.
         */
        void sanitize() {
            if (!this.initialized) {
                var defaults = new FxSettings();
                this.bloomEnabled = defaults.bloomEnabled;
                this.bloomIntensity = defaults.bloomIntensity;
                this.bloomThreshold = defaults.bloomThreshold;
                this.bloomRadius = defaults.bloomRadius;
                this.tonemapEnabled = defaults.tonemapEnabled;
                this.tonemapMode = defaults.tonemapMode;
                this.exposure = defaults.exposure;
                this.initialized = true;
            }

            this.bloomIntensity = clamp(this.bloomIntensity, 0, 200);
            this.bloomThreshold = clamp(this.bloomThreshold, 0, 100);
            this.bloomRadius = clamp(this.bloomRadius, 1, 8);
            this.exposure = clamp(this.exposure, 25, 400);

            if (this.tonemapMode == null) {
                this.tonemapMode = TonemapMode.ACES;
            }
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        /**
         * True when at least one pass has anything to do. Used to skip adding passes to the frame graph entirely
         * rather than adding a chain whose every shader ends up a no-op.
         */
        public boolean isAnyEffectActive() {
            return this.bloomEnabled || this.tonemapEnabled;
        }
    }

    /**
     * Directional celestial shadow settings.
     *
     * <p>Defaults are deliberately conservative and the master toggle is off, because the shadow
     * caster pass is a second terrain draw. Resolution and radius trade against each other: a wider
     * cascade at fixed resolution gives coarser texels, and that is the dial that decides whether
     * contact shadows look tight or smeared.
     */
    public static class SkyShadowSettings {
        /**
         * See {@link FxSettings#initialized} for why this marker exists. A config file written
         * before this block exists would otherwise read every field as 0, which means
         * "256 resolution, radius 0, filter 0" rather than something usable.
         */
        public boolean initialized = false;

        /** Master toggle. Shadows are entirely skipped while this is false. */
        public boolean enabled = false;

        /** Near cascade resolution in texels. Clamped to at least 256. */
        public int nearResolution = 2304;

        /** Near cascade radius in blocks. */
        public float nearRadius = 31.0F;

        /** Near cascade rebuild interval in seconds. Low, because it is cheap and follows the camera. */
        public float nearUpdateIntervalSeconds = 1.2F;

        /** Far cascade resolution in texels. */
        public int farResolution = 1536;

        /**
         * Far cascade radius in blocks. Forced to at least {@code nearRadius + 16} by
         * {@link net.caffeinemc.mods.sodium.client.render.shadow.NeoSkyCascade#configurePair}.
         */
        public float farRadius = 248.0F;

        /** Far cascade rebuild interval in milliseconds. High, because it covers most of the view. */
        public int farUpdateMs = 1050;

        /**
         * Shadow filter taps. Binary: 1 for a hard edge, 4 for a soft one. Stored as a count because
         * that is what the shader branches on, not a boolean.
         */
        public int filterSamples = 1;

        /**
         * How far along the light direction casters are traced, in blocks. Bounds the depth range of
         * each cascade's projection.
         */
        public float rayLength = 248.0F;

        /** Constant depth bias, subtracted before the manual comparison. */
        public float baseBias = 0.002F;

        /** Slope-scaling factor for the bias. Kept for hardware parity; see the GLSL note. */
        public float slopeBias = 1.0F;

        /** Sun direct strength. */
        public float sunStrength = 0.9F;

        /** Moon direct strength. Deliberately dimmer than the sun. */
        public float moonStrength = 0.25F;

        /** Ambient strength. */
        public float ambientStrength = 0.35F;

        /** When false, the LUT bakes neutral white so shadows can be judged without colour grading. */
        public boolean colorsEnabled = true;

        void sanitize() {
            if (!this.initialized) {
                var defaults = new SkyShadowSettings();
                this.enabled = defaults.enabled;
                this.nearResolution = defaults.nearResolution;
                this.nearRadius = defaults.nearRadius;
                this.nearUpdateIntervalSeconds = defaults.nearUpdateIntervalSeconds;
                this.farResolution = defaults.farResolution;
                this.farRadius = defaults.farRadius;
                this.farUpdateMs = defaults.farUpdateMs;
                this.filterSamples = defaults.filterSamples;
                this.rayLength = defaults.rayLength;
                this.baseBias = defaults.baseBias;
                this.slopeBias = defaults.slopeBias;
                this.sunStrength = defaults.sunStrength;
                this.moonStrength = defaults.moonStrength;
                this.ambientStrength = defaults.ambientStrength;
                this.colorsEnabled = defaults.colorsEnabled;
                this.initialized = true;
            }

            this.nearResolution = clamp(this.nearResolution, 256, 8192);
            this.farResolution = clamp(this.farResolution, 256, 8192);

            this.nearRadius = clamp(this.nearRadius, 8.0F, 512.0F);
            this.farRadius = clamp(this.farRadius, this.nearRadius + 16.0F, 2048.0F);

            this.nearUpdateIntervalSeconds = clamp(this.nearUpdateIntervalSeconds, 0.1F, 10.0F);
            this.farUpdateMs = clamp(this.farUpdateMs, 100, 10000);

            // Binary: the shader has exactly a 1-tap and a 4-tap path.
            this.filterSamples = this.filterSamples >= 4 ? 4 : 1;

            this.rayLength = clamp(this.rayLength, 16.0F, 4096.0F);
            this.baseBias = clamp(this.baseBias, 0.0F, 0.1F);
            this.slopeBias = clamp(this.slopeBias, 0.0F, 8.0F);

            this.sunStrength = clamp(this.sunStrength, 0.0F, 4.0F);
            this.moonStrength = clamp(this.moonStrength, 0.0F, 4.0F);
            this.ambientStrength = clamp(this.ambientStrength, 0.0F, 4.0F);
        }

        /**
         * Cheap change detector for the LUT. Hashing only the values that can alter a texel, so an
         * unrelated slider change does not force a 57 KB re-upload.
         */
        public int lutHash() {
            int h = this.colorsEnabled ? 1 : 0;
            h = h * 31 + Float.floatToIntBits(this.sunStrength);
            h = h * 31 + Float.floatToIntBits(this.moonStrength);
            h = h * 31 + Float.floatToIntBits(this.ambientStrength);
            h = h * 31 + Float.floatToIntBits(this.baseBias);
            return h;
        }

        private static float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    public enum TonemapMode {
        REINHARD,
        ACES,
        FILMIC
    }

    public static class DebugSettings {
        public boolean terrainSortingEnabled = true;
    }

    public static class NotificationSettings {
        public boolean hasClearedDonationButton = false;
        public boolean hasSeenDonationPrompt = false;
        public boolean hasEditedFullscreenOption = false;
    }

    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static SodiumOptions loadFromDisk() {
        Path path = getConfigPath();
        SodiumOptions config;

        if (Files.exists(path)) {
            try (FileReader reader = new FileReader(path.toFile())) {
                config = GSON.fromJson(reader, SodiumOptions.class);
            } catch (IOException e) {
                throw new RuntimeException("Could not parse config", e);
            }

            // Gson maps an empty or non-object file to null rather than throwing.
            if (config == null) {
                config = new SodiumOptions();
            }
        } else {
            config = new SodiumOptions();
        }

        config.sanitize();

        try {
            writeToDisk(config);
        } catch (IOException e) {
            throw new RuntimeException("Couldn't update config file", e);
        }

        return config;
    }

    /**
     * Repairs values that could not have come from the settings screen. Gson leaves absent fields at the Java type
     * default, so a config file written before a field existed deserializes it as 0 rather than as the intended
     * default; ranges are clamped here so the renderer never sees a value no slider can produce.
     */
    private void sanitize() {
        this.fx.sanitize();
        this.skyShadows.sanitize();
    }

    private static Path getConfigPath() {
        return PlatformRuntimeInformation.getInstance().getConfigDirectory()
                .resolve(DEFAULT_FILE_NAME);
    }

    public static void writeToDisk(SodiumOptions config) throws IOException {
        if (config.isReadOnly()) {
            // throws an IOException so that it is caught correctly when trying to save the config when it's locked
            throw new IOException("Config file is read-only");
        }

        Path path = getConfigPath();
        Path dir = path.getParent();

        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        } else if (!Files.isDirectory(dir)) {
            throw new IOException("Not a directory: " + dir);
        }

        FileUtil.writeTextRobustly(GSON.toJson(config), path);
    }

    public boolean isReadOnly() {
        return this.readOnly;
    }

    public void setReadOnly() {
        this.readOnly = true;
    }
}

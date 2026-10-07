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

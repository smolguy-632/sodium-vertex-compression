package net.caffeinemc.mods.sodium.client.gui;

import com.mojang.blaze3d.platform.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.*;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.compatibility.workarounds.Workarounds;
import net.caffeinemc.mods.sodium.client.gui.options.FullscreenMode;
import net.caffeinemc.mods.sodium.client.gui.options.Toggle;
import net.caffeinemc.mods.sodium.client.gui.options.control.ControlValueFormatterImpls;
import net.caffeinemc.mods.sodium.client.render.chunk.DeferMode;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.QuadSplittingMode;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.VertexPositionLayout;
import net.minecraft.client.*;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Optional;

// TODO: get initialValue from the vanilla options (it's private)
public class SodiumConfigBuilder implements ConfigEntryPoint {
    private static final Identifier SODIUM_ICON = Identifier.fromNamespaceAndPath("sodium", "textures/gui/config-icon.png");
    private static final SodiumOptions DEFAULTS = SodiumOptions.defaults();

    private static final Identifier POSITION_BITS_X = Identifier.parse("sodium:performance.position_bits_x");
    private static final Identifier POSITION_BITS_Y = Identifier.parse("sodium:performance.position_bits_y");
    private static final Identifier POSITION_BITS_Z = Identifier.parse("sodium:performance.position_bits_z");

    private final Options vanillaOpts;
    private final StorageEventHandler vanillaStorage;
    private final SodiumOptions sodiumOpts;
    private final StorageEventHandler sodiumStorage;

    private final @Nullable Window window;

    public SodiumConfigBuilder() {
        var minecraft = Minecraft.getInstance();
        this.window = minecraft.getWindow();

        this.vanillaOpts = minecraft.options;
        this.vanillaStorage = this.vanillaOpts == null ? null : () -> {
            this.vanillaOpts.save();

            SodiumClientMod.logger().info("Flushed changes to Minecraft configuration");
        };

        this.sodiumOpts = SodiumClientMod.options();
        this.sodiumStorage = () -> {
            try {
                SodiumOptions.writeToDisk(this.sodiumOpts);
            } catch (IOException e) {
                throw new RuntimeException("Couldn't save configuration changes", e);
            }

            SodiumClientMod.logger().info("Flushed changes to Sodium configuration");
        };
    }

    private Monitor getMonitor() {
        if (this.window == null) {
            return null;
        }
        return this.window.findBestMonitor();
    }

    public static void registerIcon(TextureManager textureManager) {
        textureManager.registerAndLoad(SODIUM_ICON, new SodiumLogo());
    }

    static class SodiumLogo extends ReloadableTexture {
        public SodiumLogo() {
            super(SODIUM_ICON);
        }

        @Override
        public TextureContents loadContents(ResourceManager resourceManager) throws IOException {
            try (InputStream inputStream = SodiumConfigBuilder.class.getResourceAsStream("/config-icon.png")) {
                return new TextureContents(NativeImage.read(inputStream), new TextureMetadataSection(false, false, MipmapStrategy.AUTO, 0.1f));
            }
        }
    }

    @Override
    public void registerConfigEarly(ConfigBuilder builder) {
        new SodiumConfigBuilder().buildEarlyConfig(builder);
    }

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        new SodiumConfigBuilder().buildFullConfig(builder);
    }

    private static ModOptionsBuilder createModOptionsBuilder(ConfigBuilder builder) {
        return builder.registerOwnModOptions()
                .setName("Sodium")
                .setIcon(SODIUM_ICON)
                .formatVersion(version -> {
                    var result = version.splitWithDelimiters("\\+", 2);
                    return result[0];
                });
    }

    private void buildEarlyConfig(ConfigBuilder builder) {
        createModOptionsBuilder(builder).addPage(
                builder.createOptionPage()
                        .setName(Component.translatable("sodium.options.pages.performance"))
                        .addOptionGroup(
                                builder.createOptionGroup()
                                        .addOption(this.buildNoErrorContextOption(builder))));
    }

    private void buildFullConfig(ConfigBuilder builder) {
        createModOptionsBuilder(builder)
                .setColorTheme(builder.createColorTheme().setFullThemeRGB(
                        Colors.THEME, Colors.THEME_LIGHTER, Colors.THEME_DARKER))
                .addPage(this.buildGeneralPage(builder))
                .addPage(this.buildQualityPage(builder))
.addPage(this.buildPerformancePage(builder))
        .addPage(this.buildEffectsPage(builder))
        .addPage(this.buildCelestialShadowsPage(builder));
    }

    private OptionPageBuilder buildEffectsPage(ConfigBuilder builder) {
        var effectsPage = builder.createOptionPage().setName(Component.translatable("sodium.options.pages.effects"));

        effectsPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:effects.bloom"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.bloom.name"))
                                .setTooltip(Component.translatable("sodium.options.bloom.tooltip"))
                                .setDefaultValue(DEFAULTS.fx.bloomEnabled)
                                .setBinding(value -> this.sodiumOpts.fx.bloomEnabled = value, () -> this.sodiumOpts.fx.bloomEnabled)
                                .setImpact(OptionImpact.MEDIUM)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:effects.bloom_intensity"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.bloom_intensity.name"))
                                .setTooltip(Component.translatable("sodium.options.bloom_intensity.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.percentage())
                                .setRange(0, 200, 1)
                                .setDefaultValue(DEFAULTS.fx.bloomIntensity)
                                .setBinding(value -> this.sodiumOpts.fx.bloomIntensity = value, () -> this.sodiumOpts.fx.bloomIntensity)
                                .setEnabledProvider(state -> this.sodiumOpts.fx.bloomEnabled)
                                .setImpact(OptionImpact.LOW)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:effects.bloom_threshold"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.bloom_threshold.name"))
                                .setTooltip(Component.translatable("sodium.options.bloom_threshold.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.percentage())
                                .setRange(0, 100, 1)
                                .setDefaultValue(DEFAULTS.fx.bloomThreshold)
                                .setBinding(value -> this.sodiumOpts.fx.bloomThreshold = value, () -> this.sodiumOpts.fx.bloomThreshold)
                                .setEnabledProvider(state -> this.sodiumOpts.fx.bloomEnabled)
                                .setImpact(OptionImpact.LOW)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:effects.bloom_radius"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.bloom_radius.name"))
                                .setTooltip(Component.translatable("sodium.options.bloom_radius.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.number())
                                .setRange(1, 8, 1)
                                .setDefaultValue(DEFAULTS.fx.bloomRadius)
                                .setBinding(value -> this.sodiumOpts.fx.bloomRadius = value, () -> this.sodiumOpts.fx.bloomRadius)
                                .setEnabledProvider(state -> this.sodiumOpts.fx.bloomEnabled)
                                .setImpact(OptionImpact.LOW)
                )
        );

        effectsPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:effects.tonemap"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.tonemap.name"))
                                .setTooltip(Component.translatable("sodium.options.tonemap.tooltip"))
                                .setDefaultValue(DEFAULTS.fx.tonemapEnabled)
                                .setBinding(value -> this.sodiumOpts.fx.tonemapEnabled = value, () -> this.sodiumOpts.fx.tonemapEnabled)
                                .setImpact(OptionImpact.MEDIUM)
                )
                .addOption(
                        builder.createEnumOption(Identifier.parse("sodium:effects.tonemap_mode"), SodiumOptions.TonemapMode.class)
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.tonemap_mode.name"))
                                .setTooltip(Component.translatable("sodium.options.tonemap_mode.tooltip"))
                                .setElementNameProvider(mode -> switch (mode) {
                                    case REINHARD -> Component.translatable("sodium.options.tonemap_mode.reinhard");
                                    case FILMIC -> Component.translatable("sodium.options.tonemap_mode.filmic");
                                    case ACES -> Component.translatable("sodium.options.tonemap_mode.aces");
                                })
                                .setDefaultValue(DEFAULTS.fx.tonemapMode)
                                .setBinding(value -> this.sodiumOpts.fx.tonemapMode = value, () -> this.sodiumOpts.fx.tonemapMode)
                                .setEnabledProvider(state -> this.sodiumOpts.fx.tonemapEnabled)
                                .setImpact(OptionImpact.LOW)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:effects.exposure"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.exposure.name"))
                                .setTooltip(Component.translatable("sodium.options.exposure.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.percentage())
                                .setRange(25, 400, 1)
                                .setDefaultValue(DEFAULTS.fx.exposure)
                                .setBinding(value -> this.sodiumOpts.fx.exposure = value, () -> this.sodiumOpts.fx.exposure)
                                .setEnabledProvider(state -> this.sodiumOpts.fx.tonemapEnabled)
                                .setImpact(OptionImpact.LOW)
                )
        );

        return effectsPage;
    }

    /**
     * NeoSkyCelestia directional shadows.
     *
     * <p>Kept on its own page rather than folded into Effects because the knobs are strongly
     * interdependent: resolution and radius trade texel density against coverage, and the near/far
     * relationship is enforced in code. Putting them together on the Effects page would invite
     * combinations that look plausible and render worse than either end of the range.
     */
    private OptionPageBuilder buildCelestialShadowsPage(ConfigBuilder builder) {
        var page = builder.createOptionPage()
                .setName(Component.translatable("sodium.options.pages.celestial_shadows"));

        page.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:celestial_shadows.enabled"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.sky_shadows.enabled.name"))
                                .setTooltip(Component.translatable("sodium.options.sky_shadows.enabled.tooltip"))
                                .setDefaultValue(DEFAULTS.skyShadows.enabled)
                                .setBinding(value -> this.sodiumOpts.skyShadows.enabled = value,
                                        () -> this.sodiumOpts.skyShadows.enabled)
                                // The caster pass is a second terrain draw, so this is the single most
                                // expensive option in Sodium's settings.
                                .setImpact(OptionImpact.HIGH)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:celestial_shadows.colors"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.sky_shadows.colors.name"))
                                .setTooltip(Component.translatable("sodium.options.sky_shadows.colors.tooltip"))
                                .setDefaultValue(DEFAULTS.skyShadows.colorsEnabled)
                                .setBinding(value -> this.sodiumOpts.skyShadows.colorsEnabled = value,
                                        () -> this.sodiumOpts.skyShadows.colorsEnabled)
                                .setEnabledProvider(state -> this.sodiumOpts.skyShadows.enabled)
                                .setImpact(OptionImpact.LOW)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:celestial_shadows.filter_samples"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.sky_shadows.filter_samples.name"))
                                .setTooltip(Component.translatable("sodium.options.sky_shadows.filter_samples.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.number())
                                // Only two settings exist: the shader has a 1-tap and a 4-tap path and
                                // nothing in between, so a continuous slider would be a lie.
                                .setRange(1, 4, 3)
                                .setDefaultValue(DEFAULTS.skyShadows.filterSamples)
                                .setBinding(value -> this.sodiumOpts.skyShadows.filterSamples = value,
                                        () -> this.sodiumOpts.skyShadows.filterSamples)
                                .setEnabledProvider(state -> this.sodiumOpts.skyShadows.enabled)
                                .setImpact(OptionImpact.MEDIUM)
                )
        );

        page.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:celestial_shadows.near_resolution"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.sky_shadows.near_resolution.name"))
                                .setTooltip(Component.translatable("sodium.options.sky_shadows.near_resolution.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.number())
                                .setRange(256, 8192, 256)
                                .setDefaultValue(DEFAULTS.skyShadows.nearResolution)
                                .setBinding(value -> this.sodiumOpts.skyShadows.nearResolution = value,
                                        () -> this.sodiumOpts.skyShadows.nearResolution)
                                .setEnabledProvider(state -> this.sodiumOpts.skyShadows.enabled)
                                .setImpact(OptionImpact.HIGH)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:celestial_shadows.near_radius"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.sky_shadows.near_radius.name"))
                                .setTooltip(Component.translatable("sodium.options.sky_shadows.near_radius.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.number())
                                .setRange(8, 512, 1)
                                .setDefaultValue((int) DEFAULTS.skyShadows.nearRadius)
                                .setBinding(value -> this.sodiumOpts.skyShadows.nearRadius = value,
                                        () -> (int) this.sodiumOpts.skyShadows.nearRadius)
                                .setEnabledProvider(state -> this.sodiumOpts.skyShadows.enabled)
                                .setImpact(OptionImpact.MEDIUM)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:celestial_shadows.far_resolution"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.sky_shadows.far_resolution.name"))
                                .setTooltip(Component.translatable("sodium.options.sky_shadows.far_resolution.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.number())
                                .setRange(256, 8192, 256)
                                .setDefaultValue(DEFAULTS.skyShadows.farResolution)
                                .setBinding(value -> this.sodiumOpts.skyShadows.farResolution = value,
                                        () -> this.sodiumOpts.skyShadows.farResolution)
                                .setEnabledProvider(state -> this.sodiumOpts.skyShadows.enabled)
                                .setImpact(OptionImpact.HIGH)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:celestial_shadows.far_radius"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.sky_shadows.far_radius.name"))
                                .setTooltip(Component.translatable("sodium.options.sky_shadows.far_radius.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.number())
                                .setRange(8, 2048, 1)
                                .setDefaultValue((int) DEFAULTS.skyShadows.farRadius)
                                .setBinding(value -> this.sodiumOpts.skyShadows.farRadius = value,
                                        () -> (int) this.sodiumOpts.skyShadows.farRadius)
                                .setEnabledProvider(state -> this.sodiumOpts.skyShadows.enabled)
                                .setImpact(OptionImpact.MEDIUM)
                )
        );

        page.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:celestial_shadows.base_bias"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.sky_shadows.base_bias.name"))
                                .setTooltip(Component.translatable("sodium.options.sky_shadows.base_bias.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.number())
                                // Stored as an integer permille of the cascade's depth range: the raw
                                // bias is a fraction of a normalised depth, far too small a number to
                                // put on a slider directly.
                                .setRange(0, 100, 1)
                                .setDefaultValue((int) (DEFAULTS.skyShadows.baseBias * 1000.0F))
                                .setBinding(value -> this.sodiumOpts.skyShadows.baseBias = value / 1000.0F,
                                        () -> (int) (this.sodiumOpts.skyShadows.baseBias * 1000.0F))
                                .setEnabledProvider(state -> this.sodiumOpts.skyShadows.enabled)
                                .setImpact(OptionImpact.LOW)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:celestial_shadows.ambient"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.sky_shadows.ambient.name"))
                                .setTooltip(Component.translatable("sodium.options.sky_shadows.ambient.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.percentage())
                                .setRange(0, 400, 5)
                                .setDefaultValue((int) (DEFAULTS.skyShadows.ambientStrength * 100.0F))
                                .setBinding(value -> this.sodiumOpts.skyShadows.ambientStrength = value / 100.0F,
                                        () -> (int) (this.sodiumOpts.skyShadows.ambientStrength * 100.0F))
                                .setEnabledProvider(state -> this.sodiumOpts.skyShadows.enabled)
                                .setImpact(OptionImpact.LOW)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:celestial_shadows.sun_strength"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.sky_shadows.sun_strength.name"))
                                .setTooltip(Component.translatable("sodium.options.sky_shadows.sun_strength.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.percentage())
                                .setRange(0, 400, 5)
                                .setDefaultValue((int) (DEFAULTS.skyShadows.sunStrength * 100.0F))
                                .setBinding(value -> this.sodiumOpts.skyShadows.sunStrength = value / 100.0F,
                                        () -> (int) (this.sodiumOpts.skyShadows.sunStrength * 100.0F))
                                .setEnabledProvider(state -> this.sodiumOpts.skyShadows.enabled)
                                .setImpact(OptionImpact.LOW)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:celestial_shadows.moon_strength"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.sky_shadows.moon_strength.name"))
                                .setTooltip(Component.translatable("sodium.options.sky_shadows.moon_strength.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.percentage())
                                .setRange(0, 400, 5)
                                .setDefaultValue((int) (DEFAULTS.skyShadows.moonStrength * 100.0F))
                                .setBinding(value -> this.sodiumOpts.skyShadows.moonStrength = value / 100.0F,
                                        () -> (int) (this.sodiumOpts.skyShadows.moonStrength * 100.0F))
                                .setEnabledProvider(state -> this.sodiumOpts.skyShadows.enabled)
                                .setImpact(OptionImpact.LOW)
                )
        );

        return page;
    }

    private OptionPageBuilder buildGeneralPage(ConfigBuilder builder) {
        var generalPage = builder.createOptionPage().setName(Component.translatable("sodium.options.pages.general"));
        generalPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        // TODO: make RD option respect Vanilla's >16 RD only allowed if memory >1GB constraint
                        builder.createIntegerOption(Identifier.parse("sodium:general.render_distance"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.renderDistance"))
                                .setTooltip(Component.translatable("sodium.options.view_distance.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.translateVariable("options.chunks"))
                                .setRange(2, 32, 1)
                                .setDefaultValue(12)
                                .setBinding(this.vanillaOpts.renderDistance()::set, this.vanillaOpts.renderDistance()::get)
                                .setImpact(OptionImpact.HIGH)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:general.simulation_distance"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.simulationDistance"))
                                .setTooltip(Component.translatable("sodium.options.simulation_distance.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.translateVariable("options.chunks"))
                                .setRange(5, 32, 1)
                                .setDefaultValue(12)
                                .setBinding(this.vanillaOpts.simulationDistance()::set, this.vanillaOpts.simulationDistance()::get)
                                .setImpact(OptionImpact.HIGH)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:general.gamma"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.gamma"))
                                .setTooltip(Component.translatable("sodium.options.brightness.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.brightness())
                                .setRange(0, 100, 1)
                                .setDefaultValue(50)
                                .setBinding(value -> this.vanillaOpts.gamma().set(value * 0.01D), () -> (int) (this.vanillaOpts.gamma().get() / 0.01D))
                )
        );
        generalPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:general.gui_scale"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.guiScale"))
                                .setTooltip(Component.translatable("sodium.options.gui_scale.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.guiScale())
                                .setValidatorProvider((state) -> {
                                    var savedValue = state.readIntOption(Identifier.parse("sodium:general.gui_scale"));
                                    var realMax = this.window.calculateScale(0, Minecraft.getInstance().isEnforceUnicode());
                                    var presentationMax = Math.max(savedValue, realMax);
                                    return new GUIScaleRange(presentationMax);
                                }, ConfigState.UPDATE_ON_REBUILD, ConfigState.UPDATE_ON_APPLY)
                                .setDefaultValue(0)
                                .setBinding(this.vanillaOpts.guiScale()::set, this.vanillaOpts.guiScale()::get)
                )
                .addOption(
                        builder.createEnumOption(Identifier.parse("sodium:general.fullscreen_mode"), FullscreenMode.class)
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("sodium.options.fullscreen_mode.name"))
                                .setTooltip(Component.translatable("sodium.options.fullscreen_mode.tooltip"))
                                .setElementNameProvider(mode -> switch (mode) {
                                    case OFF -> Component.translatable("sodium.options.fullscreen_mode.off");
                                    case EXCLUSIVE ->
                                            Component.translatable("sodium.options.fullscreen_mode.exclusive");
                                    case BORDERLESS ->
                                            Component.translatable("sodium.options.fullscreen_mode.borderless");
                                })
                                .setDefaultValue(FullscreenMode.OFF)
                                .setImpact(OptionImpact.HIGH)
                                .setBinding(
                                        // modifies fullscreen and exclusive fullscreen together since they are interdependent in Vanilla's implementation
                                        value -> {
                                            switch (value) {
                                                case OFF -> this.vanillaOpts.fullscreen().set(false);
                                                case EXCLUSIVE -> {
                                                    this.vanillaOpts.fullscreen().set(true);
                                                    this.vanillaOpts.exclusiveFullscreen().set(true);
                                                }
                                                case BORDERLESS -> {
                                                    this.vanillaOpts.fullscreen().set(true);
                                                    this.vanillaOpts.exclusiveFullscreen().set(false);
                                                }
                                            }
                                        },
                                        () -> {
                                            boolean fullscreen = this.vanillaOpts.fullscreen().get();
                                            boolean exclusive = this.vanillaOpts.exclusiveFullscreen().get();
                                            if (fullscreen && exclusive) {
                                                return FullscreenMode.EXCLUSIVE;
                                            } else if (fullscreen) {
                                                return FullscreenMode.BORDERLESS;
                                            } else {
                                                return FullscreenMode.OFF;
                                            }
                                        })
                                .setApplyHook((_) -> {
                                })
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:general.fullscreen_resolution"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.fullscreen.resolution"))
                                .setTooltip(Component.translatable("sodium.options.fullscreen_resolution.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.resolution())
                                // the max value of 1 when the monitor is not available prevents an exception from being thrown
                                .setValidator(new FullscreenResolutionRange())
                                .setDefaultValue(0)
                                .setBinding(value -> {
                                    var monitor = this.getMonitor();
                                    if (monitor != null) {
                                        this.window.setPreferredFullscreenVideoMode(0 == value ? Optional.empty() : Optional.of(monitor.mode(value - 1)));
                                    }
                                }, () -> {
                                    var monitor = this.getMonitor();
                                    if (monitor == null) {
                                        return 0;
                                    } else {
                                        Optional<VideoMode> optional = this.window.getPreferredFullscreenVideoMode();
                                        return optional.map((videoMode) -> monitor.indexOfMode(videoMode) + 1).orElse(0);
                                    }
                                })
                                .setEnabledProvider(
                                        (state) -> {
                                            var monitor = this.getMonitor();
                                            if (monitor == null || monitor.modeCount() <= 0) {
                                                return false;
                                            }
                                            var fullscreenMode = state.readEnumOption(Identifier.parse("sodium:general.fullscreen_mode"), FullscreenMode.class);
                                            return fullscreenMode == FullscreenMode.EXCLUSIVE;
                                        },
                                        Identifier.parse("sodium:general.fullscreen_mode"))
                                .setFlags(OptionFlag.REQUIRES_VIDEOMODE_RELOAD)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:general.vsync"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.vsync"))
                                .setTooltip(Component.translatable("sodium.options.v_sync.tooltip"))
                                .setDefaultValue(true)
                                .setBinding(this.vanillaOpts.enableVsync()::set, this.vanillaOpts.enableVsync()::get)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:general.framerate_limit"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.framerateLimit"))
                                .setTooltip(Component.translatable("sodium.options.fps_limit.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.fpsLimit())
                                .setRange(10, 260, 10)
                                .setDefaultValue(60)
                                .setBinding(this.vanillaOpts.framerateLimit()::set, this.vanillaOpts.framerateLimit()::get)
                )
        );
        generalPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createEnumOption(Identifier.parse("sodium:general.attack_indicator"), AttackIndicatorStatus.class)
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.attackIndicator"))
                                .setTooltip(Component.translatable("sodium.options.attack_indicator.tooltip"))
                                .setDefaultValue(AttackIndicatorStatus.CROSSHAIR)
                                .setElementNameProvider(AttackIndicatorStatus::caption)
                                .setBinding(this.vanillaOpts.attackIndicator()::set, this.vanillaOpts.attackIndicator()::get)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:general.autosave_indicator"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.autosaveIndicator"))
                                .setTooltip(Component.translatable("sodium.options.autosave_indicator.tooltip"))
                                .setDefaultValue(true)
                                .setBinding(this.vanillaOpts.showAutosaveIndicator()::set, this.vanillaOpts.showAutosaveIndicator()::get)
                )
        );

        var platformGroup = builder.createOptionGroup().addOption(builder.createEnumOption(Identifier.fromNamespaceAndPath("sodium", "general.graphics_api"),
                        PreferredGraphicsApi.class)
                .setStorageHandler(this.vanillaStorage)
                .setName(Component.translatable("options.graphicsApi"))
                .setTooltip(i -> {
                    if (i == PreferredGraphicsApi.VULKAN) {
                        return Component.translatable("options.graphicsApi.tooltip.vulkan");
                    } else {
                        return Component.translatable("options.graphicsApi.tooltip");
                    }
                })
                .setElementNameProvider(EnumOptionBuilder.nameProviderFrom(
                        Component.translatable("options.graphicsApi.default"),
                        Component.translatable("options.graphicsApi.opengl"),
                        Component.literal("Prefer Vulkan")))
                .setDefaultValue(PreferredGraphicsApi.DEFAULT)
                .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                .setBinding((value) -> this.vanillaOpts.preferredGraphicsBackend().set(value), () -> this.vanillaOpts.preferredGraphicsBackend().get()));

        if (MacosUtil.IS_MACOS) {
            platformGroup.addOption(
                    builder.createBooleanOption(Identifier.parse("sodium:general.mac_fullscreen_menu_visibility"))
                            .setStorageHandler(this.vanillaStorage)
                            .setName(Component.translatable("options.macFullscreenMenuVisibility"))
                            .setTooltip(Component.translatable("options.macFullscreenMenuVisibility.tooltip"))
                            .setDefaultValue(false)
                            .setBinding(this.vanillaOpts.macFullscreenMenuVisibility()::set, this.vanillaOpts.macFullscreenMenuVisibility()::get)
            );
        }
        generalPage.addOptionGroup(platformGroup);

        return generalPage;
    }

    private OptionPageBuilder buildQualityPage(ConfigBuilder builder) {
        var qualityPage = builder.createOptionPage().setName(Component.translatable("sodium.options.pages.quality"));

        qualityPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:quality.graphics"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.improvedTransparency"))
                                .setTooltip(Component.translatable("options.improvedTransparency.oit.tooltip"))
                                .setDefaultValue(false)
                                .setBinding(this.vanillaOpts.improvedTransparency()::set, this.vanillaOpts.improvedTransparency()::get)
                                .setImpact(OptionImpact.HIGH)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
        );

        qualityPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createEnumOption(Identifier.parse("sodium:quality.clouds"), CloudStatus.class)
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.renderClouds"))
                                .setTooltip(Component.translatable("sodium.options.clouds_quality.tooltip"))
                                .setElementNameProvider(EnumOptionBuilder.nameProviderFrom(
                                        Component.translatable("options.off"),
                                        Component.translatable("options.clouds.fast"),
                                        Component.translatable("options.clouds.fancy")))
                                .setDefaultValue(CloudStatus.FANCY)
                                .setBinding((value) -> {
                                    this.vanillaOpts.cloudStatus().set(value);
                                }, () -> this.vanillaOpts.cloudStatus().get())
                                .setImpact(OptionImpact.LOW)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:quality.render_cloud_distance"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.renderCloudsDistance"))
                                .setTooltip(Component.translatable("sodium.options.clouds_distance.tooltip"))
                                .setRange(2, 128, 2)
                                .setDefaultValue(128)
                                .setBinding((value) -> {
                                    this.vanillaOpts.cloudRange().set(value);

                                    Minecraft.getInstance().levelRenderer.cloudRenderer().markForRebuild();
                                }, () -> this.vanillaOpts.cloudRange().get())
                                .setImpact(OptionImpact.LOW)
                                .setValueFormatter(ControlValueFormatterImpls.translateVariable("options.chunks"))
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:quality.weather"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.weatherRadius"))
                                .setTooltip(Component.translatable("options.weatherRadius.tooltip"))
                                .setDefaultValue(10)
                                .setRange(new Range(3, 10, 1))
                                .setValueFormatter(ControlValueFormatterImpls.number())
                                .setBinding(this.vanillaOpts.weatherRadius()::set, this.vanillaOpts.weatherRadius()::get)
                                .setImpact(OptionImpact.LOW)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:quality.leaves"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.cutoutLeaves"))
                                .setTooltip(Component.translatable("options.cutoutLeaves.tooltip"))
                                .setDefaultValue(true)
                                .setBinding(this.vanillaOpts.cutoutLeaves()::set, this.vanillaOpts.cutoutLeaves()::get)
                                .setImpact(OptionImpact.MEDIUM)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
                .addOption(
                        builder.createEnumOption(Identifier.parse("sodium:quality.particles"), ParticleStatus.class)
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.particles"))
                                .setTooltip(Component.translatable("sodium.options.particle_quality.tooltip"))
                                .setElementNameProvider(EnumOptionBuilder.nameProviderFrom(
                                        Component.translatable("options.particles.all"),
                                        Component.translatable("options.particles.decreased"),
                                        Component.translatable("options.particles.minimal")
                                ))
                                .setDefaultValue(ParticleStatus.ALL)
                                .setBinding(this.vanillaOpts.particles()::set, this.vanillaOpts.particles()::get)
                                .setImpact(OptionImpact.MEDIUM)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:quality.ao"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.ao"))
                                .setTooltip(Component.translatable("sodium.options.smooth_lighting.tooltip"))
                                .setDefaultValue(true)
                                .setBinding(this.vanillaOpts.ambientOcclusion()::set, this.vanillaOpts.ambientOcclusion()::get)
                                .setImpact(OptionImpact.LOW)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:quality.biome_blend"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.biomeBlendRadius"))
                                .setValueFormatter(ControlValueFormatterImpls.biomeBlend())
                                .setTooltip(Component.translatable("sodium.options.biome_blend.tooltip"))
                                .setRange(0, 7, 1)
                                .setDefaultValue(2)
                                .setBinding(this.vanillaOpts.biomeBlendRadius()::set, this.vanillaOpts.biomeBlendRadius()::get)
                                .setImpact(OptionImpact.LOW)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:quality.entity_distance"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.entityDistanceScaling"))
                                .setValueFormatter(ControlValueFormatterImpls.percentage())
                                .setTooltip(Component.translatable("sodium.options.entity_distance.tooltip"))
                                .setRange(50, 500, 25)
                                .setDefaultValue(100)
                                .setBinding((value) -> this.vanillaOpts.entityDistanceScaling().set(value / 100.0), () -> Math.round(this.vanillaOpts.entityDistanceScaling().get().floatValue() * 100.0F))
                                .setImpact(OptionImpact.HIGH)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:quality.entity_shadows"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.entityShadows"))
                                .setTooltip(Component.translatable("sodium.options.entity_shadows.tooltip"))
                                .setDefaultValue(true)
                                .setBinding(this.vanillaOpts.entityShadows()::set, this.vanillaOpts.entityShadows()::get)
                                .setImpact(OptionImpact.MEDIUM)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:quality.vignette"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.vignette"))
                                .setTooltip(Component.translatable("options.vignette.tooltip"))
                                .setDefaultValue(true)
                                .setBinding(this.vanillaOpts.vignette()::set, this.vanillaOpts.vignette()::get)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:quality.fade_time"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.chunkFade"))
                                .setTooltip(Component.translatable("options.chunkFade.tooltip"))
                                .setDefaultValue(750)
                                .setValueFormatter(ControlValueFormatterImpls.chunkFade())
                                .setRange(new Range(0, 2000, 50))
                                .setBinding(fade -> this.vanillaOpts.chunkSectionFadeInTime().set((double) fade / 1000.0), () -> (int) (this.vanillaOpts.chunkSectionFadeInTime().get() * 1000.0))
                )
        );

        qualityPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:quality.mipmap_levels"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.mipmapLevels"))
                                .setValueFormatter(ControlValueFormatterImpls.multiplier())
                                .setTooltip(Component.translatable("sodium.options.mipmap_levels.tooltip"))
                                .setRange(0, 4, 1)
                                .setDefaultValue(4)
                                .setBinding(this.vanillaOpts.mipmapLevels()::set, this.vanillaOpts.mipmapLevels()::get)
                                .setImpact(OptionImpact.MEDIUM)
                                .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                )
        );

        qualityPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createEnumOption(Identifier.parse("sodium:quality.filtering_mode"), TextureFilteringMethod.class)
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.textureFiltering"))
                                .setTooltip(i -> Component.translatable("options.textureFiltering." + i.name().toLowerCase(Locale.ROOT) + ".tooltip"))
                                .setElementNameProvider(name -> {
                                    return Component.translatable("options.textureFiltering." + name.name().toLowerCase(Locale.ROOT));
                                })
                                .setDefaultValue(TextureFilteringMethod.RGSS)
                                .setBinding(this.vanillaOpts.textureFiltering()::set, this.vanillaOpts.textureFiltering()::get)
                                .setImpact(OptionImpact.MEDIUM)
                                .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:quality.anisotropy_bit"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.maxAnisotropy"))
                                .setRange(new Range(0, 3, 1))
                                .setTooltip(Component.translatable("options.maxAnisotropy.tooltip"))
                                .setDefaultValue(0)
                                .setValueFormatter(ControlValueFormatterImpls.anisotropyBit())
                                .setBinding(this.vanillaOpts.maxAnisotropyBit()::set, this.vanillaOpts.maxAnisotropyBit()::get)
                                .setImpact(OptionImpact.MEDIUM)
                                .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                .setEnabledProvider(i -> {
                                    return i.readEnumOption(Identifier.parse("sodium:quality.filtering_mode"), TextureFilteringMethod.class) == TextureFilteringMethod.ANISOTROPIC;
                                }, Identifier.parse("sodium:quality.filtering_mode"))
                )
                .addOption(
                        builder.createEnumOption(Identifier.parse("sodium:quality.pixel_filtering_mode"), FilterMode.class)
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.pixel_filtering_mode.name"))
                                .setTooltip(Component.translatable("sodium.options.pixel_filtering_mode.tooltip"))
                                .setElementNameProvider(filterMode ->
                                        Component.translatable("sodium.options.pixel_filtering_mode." + filterMode.name().toLowerCase(Locale.ROOT))
                                )
                                .setDefaultValue(FilterMode.NEAREST)
                                .setBinding(filterMode -> {
                                    this.sodiumOpts.quality.pixelFilteringMode = filterMode;
                                    Minecraft.getInstance().levelExtractor.resetSampler();
                                }, () -> this.sodiumOpts.quality.pixelFilteringMode)
                                .setImpact(OptionImpact.MEDIUM)
                )
        );

        qualityPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createEnumOption(Identifier.parse("sodium:quality.hidden_fluid_culling"), Toggle.class)
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.hidden_fluid_culling.name"))
                                .setTooltip(Component.translatable("sodium.options.hidden_fluid_culling.tooltip"))
                                .setElementNameProvider(EnumOptionBuilder.nameProviderFrom(
                                        Component.translatable("sodium.options.hidden_fluid_culling.default"),
                                        Component.translatable("sodium.options.hidden_fluid_culling.optimized")))
                                .setImpact(OptionImpact.MEDIUM)
                                .setDefaultValue(Toggle.fromBoolean(DEFAULTS.quality.hiddenFluidCulling))
                                .setBinding(value -> this.sodiumOpts.quality.hiddenFluidCulling = value.toBoolean(), () -> Toggle.fromBoolean(this.sodiumOpts.quality.hiddenFluidCulling))
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
                .addOption(
                        builder.createEnumOption(Identifier.parse("sodium:quality.improved_fluid_shaping"), Toggle.class)
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.improved_fluid_shaping.name"))
                                .setTooltip(Component.translatable("sodium.options.improved_fluid_shaping.tooltip"))
                                .setElementNameProvider(EnumOptionBuilder.nameProviderFrom(
                                        Component.translatable("sodium.options.improved_fluid_shaping.default"),
                                        Component.translatable("sodium.options.improved_fluid_shaping.alternative")))
                                .setDefaultValue(Toggle.fromBoolean(DEFAULTS.quality.improvedFluidShaping))
                                .setBinding(value -> this.sodiumOpts.quality.improvedFluidShaping = value.toBoolean(), () -> Toggle.fromBoolean(this.sodiumOpts.quality.improvedFluidShaping))
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
                .addOption(
                        builder.createEnumOption(Identifier.parse("sodium:quality.closest_point_entity_sort"), Toggle.class)
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.closest_point_entity_sort.name"))
                                .setTooltip(Component.translatable("sodium.options.closest_point_entity_sort.tooltip"))
                                .setElementNameProvider(EnumOptionBuilder.nameProviderFrom(
                                        Component.translatable("sodium.options.closest_point_entity_sort.default"),
                                        Component.translatable("sodium.options.closest_point_entity_sort.enhanced")))
                                .setImpact(OptionImpact.MEDIUM)
                                .setDefaultValue(Toggle.fromBoolean(DEFAULTS.quality.useClosestPointEntitySort))
                                .setBinding(value -> this.sodiumOpts.quality.useClosestPointEntitySort = value.toBoolean(), () -> Toggle.fromBoolean(this.sodiumOpts.quality.useClosestPointEntitySort))
                )
        );
        return qualityPage;
    }

    private OptionPageBuilder buildPerformancePage(ConfigBuilder builder) {
        var performancePage = builder.createOptionPage().setName(Component.translatable("sodium.options.pages.performance"));

        performancePage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:performance.chunk_update_threads"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.chunk_update_threads.name"))
                                .setValueFormatter(ControlValueFormatterImpls.quantityOrDisabled(
                                        (v) -> Component.translatable("sodium.options.chunk_update_threads.value", v),
                                        Component.translatable("sodium.options.default")
                                ))
                                .setTooltip(Component.translatable("sodium.options.chunk_update_threads.tooltip"))
                                .setRange(0, Runtime.getRuntime().availableProcessors(), 1)
                                .setDefaultValue(DEFAULTS.performance.chunkBuilderThreads)
                                .setBinding(value -> this.sodiumOpts.performance.chunkBuilderThreads = value, () -> this.sodiumOpts.performance.chunkBuilderThreads)
                                .setImpact(OptionImpact.HIGH)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
                .addOption(
                        builder.createEnumOption(Identifier.parse("sodium:performance.always_defer_chunk_updates"), DeferMode.class)
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.defer_chunk_updates.name"))
                                .setTooltip(Component.translatable("sodium.options.defer_chunk_updates.tooltip"))
                                .setDefaultValue(DEFAULTS.performance.chunkBuildDeferMode)
                                .setBinding(value -> this.sodiumOpts.performance.chunkBuildDeferMode = value, () -> this.sodiumOpts.performance.chunkBuildDeferMode)
                                .setImpact(OptionImpact.HIGH)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_UPDATE)
                )
        );

        performancePage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:performance.use_block_face_culling"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.use_block_face_culling.name"))
                                .setTooltip(Component.translatable("sodium.options.use_block_face_culling.tooltip"))
                                .setDefaultValue(DEFAULTS.performance.useBlockFaceCulling)
                                .setBinding(value -> this.sodiumOpts.performance.useBlockFaceCulling = value, () -> this.sodiumOpts.performance.useBlockFaceCulling)
                                .setImpact(OptionImpact.MEDIUM)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:performance.use_fog_occlusion"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.use_fog_occlusion.name"))
                                .setTooltip(Component.translatable("sodium.options.use_fog_occlusion.tooltip"))
                                .setDefaultValue(DEFAULTS.performance.useFogOcclusion)
                                .setBinding(value -> this.sodiumOpts.performance.useFogOcclusion = value, () -> this.sodiumOpts.performance.useFogOcclusion)
                                .setImpact(OptionImpact.MEDIUM)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_UPDATE)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:performance.use_entity_culling"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.use_entity_culling.name"))
                                .setTooltip(Component.translatable("sodium.options.use_entity_culling.tooltip"))
                                .setDefaultValue(DEFAULTS.performance.useEntityCulling)
                                .setBinding(value -> this.sodiumOpts.performance.useEntityCulling = value, () -> this.sodiumOpts.performance.useEntityCulling)
                                .setImpact(OptionImpact.MEDIUM)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:performance.animate_only_visible_textures"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.animate_only_visible_textures.name"))
                                .setTooltip(Component.translatable("sodium.options.animate_only_visible_textures.tooltip"))
                                .setDefaultValue(DEFAULTS.performance.animateOnlyVisibleTextures)
                                .setBinding(value -> this.sodiumOpts.performance.animateOnlyVisibleTextures = value, () -> this.sodiumOpts.performance.animateOnlyVisibleTextures)
                                .setImpact(OptionImpact.HIGH)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_UPDATE)
                )
                .addOption(
                        this.buildNoErrorContextOption(builder)
                )
                .addOption(
                        builder.createEnumOption(Identifier.parse("sodium:performance.inactivity_fps_limit"), InactivityFpsLimit.class)
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.inactivityFpsLimit"))
                                .setElementNameProvider(InactivityFpsLimit::caption)
                                .setTooltip((state) -> state == InactivityFpsLimit.AFK ?
                                        Component.translatable("options.inactivityFpsLimit.afk.tooltip") :
                                        Component.translatable("options.inactivityFpsLimit.minimized.tooltip"))
                                .setDefaultValue(InactivityFpsLimit.AFK)
                                .setBinding(this.vanillaOpts.inactivityFpsLimit()::set, this.vanillaOpts.inactivityFpsLimit()::get)
                )
        );

        performancePage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createEnumOption(Identifier.parse("sodium:performance.quad_splitting"), QuadSplittingMode.class)
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.quad_splitting.name"))
                                .setTooltip(Component.translatable("sodium.options.quad_splitting.tooltip"))
                                .setImpact(OptionImpact.MEDIUM)
                                .setDefaultValue(DEFAULTS.performance.quadSplittingMode)
                                .setBinding(value -> this.sodiumOpts.performance.quadSplittingMode = value, () -> this.sodiumOpts.performance.quadSplittingMode)
                                .setEnabled(SodiumClientMod.options().debug.terrainSortingEnabled)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
        );

        // Three independent sliders rather than a fixed set of presets. They still pack into the same
        // single uint32 and the same 16-byte stride, so this trades position accuracy for nothing else.
        // Each axis spends one bit more than its slider says (see VertexPositionLayout), so the three
        // sliders may only total MAX_TOTAL_PRECISION_BITS; positionLayout() reduces whichever axis has to
        // give way if they do not.
        performancePage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createIntegerOption(POSITION_BITS_X)
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.position_bits_x.name"))
                                .setTooltip(Component.translatable("sodium.options.position_bits_x.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.translateVariable("sodium.options.position_bits.value"))
                                .setRange(VertexPositionLayout.MIN_AXIS_BITS, VertexPositionLayout.MAX_AXIS_BITS, 1)
                                .setDefaultValue(DEFAULTS.performance.positionBitsX)
                                .setBinding(value -> this.sodiumOpts.performance.positionBitsX = value, () -> this.sodiumOpts.performance.positionBitsX)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
                .addOption(
                        builder.createIntegerOption(POSITION_BITS_Y)
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.position_bits_y.name"))
                                .setTooltip(Component.translatable("sodium.options.position_bits_y.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.translateVariable("sodium.options.position_bits.value"))
                                .setRange(VertexPositionLayout.MIN_AXIS_BITS, VertexPositionLayout.MAX_AXIS_BITS, 1)
                                .setDefaultValue(DEFAULTS.performance.positionBitsY)
                                .setBinding(value -> this.sodiumOpts.performance.positionBitsY = value, () -> this.sodiumOpts.performance.positionBitsY)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
                .addOption(
                        builder.createIntegerOption(POSITION_BITS_Z)
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.position_bits_z.name"))
                                .setTooltip(Component.translatable("sodium.options.position_bits_z.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.translateVariable("sodium.options.position_bits.value"))
                                .setRange(VertexPositionLayout.MIN_AXIS_BITS, VertexPositionLayout.MAX_AXIS_BITS, 1)
                                .setDefaultValue(DEFAULTS.performance.positionBitsZ)
                                .setBinding(value -> this.sodiumOpts.performance.positionBitsZ = value, () -> this.sodiumOpts.performance.positionBitsZ)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
        );

        return performancePage;
    }

    private OptionBuilder buildNoErrorContextOption(ConfigBuilder builder) {
        return builder.createBooleanOption(Identifier.parse("sodium:performance.use_no_error_context"))
                .setStorageHandler(this.sodiumStorage)
                .setName(Component.translatable("sodium.options.use_no_error_context.name"))
                .setTooltip(Component.translatable("sodium.options.use_no_error_context.tooltip"))
                .setDefaultValue(DEFAULTS.performance.useNoErrorGLContext)
                .setBinding(value -> this.sodiumOpts.performance.useNoErrorGLContext = value, () -> this.sodiumOpts.performance.useNoErrorGLContext)
                .setEnabledProvider((state) -> {
                    if (!RenderSystem.getDevice().getDeviceInfo().backendName().contains("OpenGL")) return false;
                    GLCapabilities capabilities = GL.getCapabilities();
                    return (capabilities.OpenGL46 || capabilities.GL_KHR_no_error)
                            && !Workarounds.isWorkaroundEnabled(Workarounds.Reference.NO_ERROR_CONTEXT_UNSUPPORTED);
                })
                .setImpact(OptionImpact.LOW)
                .setFlags(OptionFlag.REQUIRES_GAME_RESTART);
    }

}
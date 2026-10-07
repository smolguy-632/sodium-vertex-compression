package net.caffeinemc.mods.sodium.client.render.fx;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.gui.SodiumOptions;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Adds Sodium's screen-space FX passes to the level frame graph.
 *
 * <p>The chain runs after the main pass and writes its result back into the main target:
 *
 * <pre>
 *   main ─▶ prefilter ─▶ half ─▶ blur H ─▶ quarter A ─▶ blur V ─▶ quarter B ─┐
 *     ▲                                                                       │
 *     └───────────────────────── composite ◀─────────────────────────────────┘
 * </pre>
 *
 * <p>Bloom runs at quarter resolution because that is where it stops reading as a halo and
 * starts reading as a blur, and it is the resolution the cost is worth paying for.
 */
public final class PostFxChain {
    /** Three {@code vec4}s per pass: parameters, tap step, tonemap state. */
    private static final int PARAMS_BYTES = 48;

    private static final int SLOT_PREFILTER = 0;
    private static final int SLOT_BLUR_HORIZONTAL = 1;
    private static final int SLOT_BLUR_VERTICAL = 2;
    private static final int SLOT_COMPOSITE = 3;
    private static final int SLOT_COUNT = 4;

    @Nullable
    private static PostFxChain instance;

    private final MappableRingBuffer params;

    private PostFxChain() {
        // Has to be mappable so the CPU can refill it and a uniform buffer so the passes can
        // read it. Each of the three ring slots holds a whole frame's parameters, so a slot is
        // never rewritten while the GPU may still be reading from it.
        this.params = new MappableRingBuffer(
                () -> "Sodium FX Params",
                GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM,
                PARAMS_BYTES * SLOT_COUNT
        );
    }

    /**
     * Registers the FX passes on the frame graph. Does nothing when every effect is off, so a
     * stock install allocates no targets and runs no extra passes.
     */
    public static void addToFrame(FrameGraphBuilder frame, ResourceHandle<RenderTarget> main) {
        SodiumOptions.FxSettings settings = SodiumClientMod.options().fx;

        if (!settings.isAnyEffectActive()) {
            return;
        }

        RenderTarget scene = main.get();
        int width = scene.width;
        int height = scene.height;

        // A minimised window can leave the main target at zero, and every size derived from it
        // would then be invalid.
        if (width < 4 || height < 4) {
            return;
        }

        PostFxChain chain = getInstance();

        int halfWidth = Math.max(1, width / 2);
        int halfHeight = Math.max(1, height / 2);
        int quarterWidth = Math.max(1, width / 4);
        int quarterHeight = Math.max(1, height / 4);

        FramePass prefilter = frame.addPass("sodium_fx_bloom_prefilter");
        prefilter.reads(main);
        ResourceHandle<RenderTarget> bloomHalf = prefilter.createsInternal(
                "sodium_fx_bloom_half", bloomTarget(halfWidth, halfHeight));

        FramePass blurHorizontal = frame.addPass("sodium_fx_bloom_blur_h");
        blurHorizontal.reads(bloomHalf);
        ResourceHandle<RenderTarget> bloomQuarterA = blurHorizontal.createsInternal(
                "sodium_fx_bloom_quarter_a", bloomTarget(quarterWidth, quarterHeight));

        FramePass blurVertical = frame.addPass("sodium_fx_bloom_blur_v");
        blurVertical.reads(bloomQuarterA);
        ResourceHandle<RenderTarget> bloomQuarterB = blurVertical.createsInternal(
                "sodium_fx_bloom_quarter_b", bloomTarget(quarterWidth, quarterHeight));

        FramePass composite = frame.addPass("sodium_fx_composite");
        composite.reads(bloomQuarterB);
        composite.readsAndWrites(main);

        prefilter.executes(() -> chain.runPrefilter(
                main.get(), bloomHalf.get(), 1.0F / width, 1.0F / height, settings));

        // The two blur passes share one pipeline and one shader; the axis rides along in
        // u_Step.xy, so each pass hands its own uniform slot and its own collapsed step.
        blurHorizontal.executes(() -> chain.runBlur(
                SLOT_BLUR_HORIZONTAL, bloomHalf.get(), bloomQuarterA.get(),
                settings.bloomRadius / (float) halfWidth, 0.0F, settings));

        blurVertical.executes(() -> chain.runBlur(
                SLOT_BLUR_VERTICAL, bloomQuarterA.get(), bloomQuarterB.get(),
                0.0F, settings.bloomRadius / (float) quarterHeight, settings));

        // Always the last pass added, so rotating here advances the ring exactly once a frame.
        composite.executes(() -> chain.runComposite(main.get(), bloomQuarterB.get(), settings));
    }

    private static PostFxChain getInstance() {
        PostFxChain chain = instance;

        if (chain == null) {
            chain = instance = new PostFxChain();
        }

        return chain;
    }

    private static RenderTargetDescriptor bloomTarget(int width, int height) {
        return new RenderTargetDescriptor(
                width,
                height,
                // Cleared on allocation so a pass never samples undefined contents, even if
                // the graph is ever reordered to run one before the pass that fills it.
                new RenderTargetDescriptor.TextureProperties(
                        new Vector4f(0.0F, 0.0F, 0.0F, 0.0F), GpuFormat.RGBA16_FLOAT),
                null
        );
    }

    private void runPrefilter(RenderTarget input, RenderTarget output, float texelX, float texelY,
                              SodiumOptions.FxSettings settings) {
        this.writeParams(SLOT_PREFILTER, settings, texelX, texelY);

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

        try (RenderPass pass = this.openPass(encoder, FxPipelines.BLOOM_PREFILTER, output)) {
            pass.setUniform("InSampler", colorView(input), linearSampler());
            pass.setUniform("FxParams", this.params.currentBuffer());
            pass.draw(3, 1, 0, 0);
        }
    }

    private void runBlur(int slot, RenderTarget input, RenderTarget output, float stepX, float stepY,
                         SodiumOptions.FxSettings settings) {
        this.writeParams(slot, settings, stepX, stepY);

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

        try (RenderPass pass = this.openPass(encoder, FxPipelines.BLOOM_BLUR, output)) {
            pass.setUniform("InSampler", colorView(input), linearSampler());
            pass.setUniform("FxParams", this.params.currentBuffer());
            pass.draw(3, 1, 0, 0);
        }
    }

    private void runComposite(RenderTarget scene, RenderTarget bloom, SodiumOptions.FxSettings settings) {
        this.writeParams(SLOT_COMPOSITE, settings, 0.0F, 0.0F);

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

        try (RenderPass pass = this.openPass(encoder, FxPipelines.BLOOM_COMPOSITE, scene)) {
            pass.setUniform("InSampler", colorView(scene), linearSampler());
            pass.setUniform("BloomSampler", colorView(bloom), linearSampler());
            pass.setUniform("FxParams", this.params.currentBuffer());
            pass.draw(3, 1, 0, 0);
        }

        this.params.rotate();
    }

    private RenderPass openPass(CommandEncoder encoder, RenderPipeline pipeline, RenderTarget output) {
        RenderPass pass = encoder.createRenderPass(
                () -> pipeline.getLocation().toString(),
                colorView(output),
                Optional.empty(),
                null,
                OptionalDouble.empty()
        );

        pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));

        return pass;
    }

    private void writeParams(int slot, SodiumOptions.FxSettings settings, float stepX, float stepY) {
        try (GpuBufferSlice.MappedView view = this.params.currentBuffer().map(false, true)) {
            ByteBuffer data = view.data();
            data.position(slot * PARAMS_BYTES);

            Std140Builder builder = Std140Builder.intoBuffer(data);
            builder.putVec4(
                    settings.bloomThreshold / 100.0F,
                    settings.bloomIntensity / 100.0F,
                    settings.bloomRadius,
                    settings.exposure / 100.0F
            );
            builder.putVec4(stepX, stepY, 0.0F, 0.0F);
            builder.putVec4(
                    settings.tonemapEnabled ? 1.0F : 0.0F,
                    settings.tonemapMode.ordinal(),
                    0.0F,
                    0.0F
            );
        }
    }

    private static GpuSampler linearSampler() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }

    private static GpuTextureView colorView(RenderTarget target) {
        GpuTextureView view = target.getColorTextureView();

        if (view == null) {
            throw new IllegalStateException("Render target has no color texture");
        }

        return view;
    }
}
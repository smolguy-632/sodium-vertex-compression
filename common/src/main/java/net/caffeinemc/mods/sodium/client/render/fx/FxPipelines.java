package net.caffeinemc.mods.sodium.client.render.fx;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * Render pipelines backing the screen-space FX chain.
 *
 * <p>Every pipeline here draws a single fullscreen triangle and blends nothing; the shaders
 * do their own combining. That keeps the chain composable — adding an effect means adding a
 * pass that reads the previous result rather than reworking a shared pipeline.
 */
public final class FxPipelines {
    /**
     * One sampled input plus the shared parameter block, for passes that consume a single
     * texture (the bloom prefilter and both blur passes).
     */
    public static final BindGroupLayout SINGLE_INPUT = BindGroupLayout.builder()
            .withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("FxParams", UniformType.UNIFORM_BUFFER)
            .build();

    /** Scene and blurred bloom, for the pass that combines them. */
    public static final BindGroupLayout COMPOSITE_INPUT = BindGroupLayout.builder()
            .withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("BloomSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("FxParams", UniformType.UNIFORM_BUFFER)
            .build();

    public static final RenderPipeline BLOOM_PREFILTER =
            fullscreen("post/bloom_prefilter", SINGLE_INPUT, GpuFormat.RGBA16_FLOAT);

    public static final RenderPipeline BLOOM_BLUR =
            fullscreen("post/bloom_blur", SINGLE_INPUT, GpuFormat.RGBA16_FLOAT);

    // The composite writes back into the main target, so its format has to match the main
    // target's. Declaring any other format here is a framebuffer-incomplete error at runtime.
    public static final RenderPipeline BLOOM_COMPOSITE =
            fullscreen("post/bloom_composite", COMPOSITE_INPUT, GpuFormat.RGBA8_UNORM);

    private FxPipelines() {
    }

    private static RenderPipeline fullscreen(String shaderPath, BindGroupLayout layout, GpuFormat targetFormat) {
        return RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("sodium", shaderPath))
                .withBindGroupLayout(layout)
                // Vanilla's screenquad vertex shader expands gl_VertexIndex into a fullscreen
                // triangle and passes the UVs straight through, so these passes need no
                // vertex buffer and no projection matrix.
                .withVertexShader("core/screenquad")
                .withFragmentShader(Identifier.fromNamespaceAndPath("sodium", shaderPath))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withColorTargetState(new ColorTargetState(Optional.empty(), targetFormat, ColorTargetState.WRITE_ALL))
                .withCull(false)
                .build();
    }
}
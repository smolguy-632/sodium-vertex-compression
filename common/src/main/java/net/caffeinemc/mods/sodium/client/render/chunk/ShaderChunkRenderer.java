package net.caffeinemc.mods.sodium.client.render.chunk;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.VertexPositionLayout;
import net.caffeinemc.mods.sodium.client.render.shadow.NeoSkyShadowSystem;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.resources.Identifier;

import java.util.*;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.jspecify.annotations.Nullable;


public abstract class ShaderChunkRenderer implements ChunkRenderer {
    /**
     * Identifies a compiled pipeline.
     *
     * <p>The active position layout becomes a shader define, so it must be part of the key. Keying only by
     * pass would let a pipeline compiled for the previous layout keep serving frames after the player
     * changed the precision setting, decoding every vertex with the wrong bit widths.
     *
     * <p>{@code neoSkyShadows} is in the key for the same reason, and more sharply: when shadows are off
     * the pipeline omits {@link #NEO_SKY_GROUP} entirely and the shaders are compiled without
     * {@code NEO_SKY_SHADOWS}. A pipeline cached while shadows were off declares no group to bind, so
     * reusing it after the player enables shadows would leave the new group unbound; reusing a
     * shadows-on pipeline after disabling them leaves a declared group with nothing to satisfy it,
     * which RenderPearl rejects at draw time with "Missing uniform u_NeoSky".
     */
    private record ProgramKey(TerrainRenderPass pass, VertexPositionLayout layout, boolean neoSkyShadows) {
    }

    private static final Map<ProgramKey, RenderPipeline> programs = new Object2ObjectOpenHashMap<>();
    private static final Map<ProgramKey, OitPipelineSet> oitPrograms = new Object2ObjectOpenHashMap<>();
    public static final BindGroupLayout BIND_GROUP = BindGroupLayout.builder()
            .withUniform("u_BlockTex", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("u_Globals", UniformType.UNIFORM_BUFFER)
            .withUniform("u_SectionTimeInfo", UniformType.TEXEL_BUFFER, GpuFormat.R32_SINT).build();
    public static final BindGroupLayout LIGHT_GROUP = BindGroupLayout.builder()
            .withUniform("u_LightTex", UniformType.COMBINED_IMAGE_SAMPLER)
            .build();

    /**
     * NeoSkyCelestia celestial shadows.
     *
     * <p>A third group rather than more fields on {@link #BIND_GROUP}: the cascade uniforms are
     * frame-global while {@code u_Globals} is rewritten once per region, so putting them together
     * would mean re-uploading identical shadow state for every visible region.
     *
     * <p>All three samplers are NEAREST and clamp-to-edge by construction on the Java side — see
     * {@code NeoSkyShadowSystem} — because the software filter compares raw stored depths and a
     * linear filter would blend them.
     */
    public static final BindGroupLayout NEO_SKY_GROUP = BindGroupLayout.builder()
            .withUniform("u_NeoSky", UniformType.UNIFORM_BUFFER)
            .withUniform("u_NeoSkyNearDepth", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("u_NeoSkyFarDepth", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("u_NeoSkyLightLut", UniformType.COMBINED_IMAGE_SAMPLER)
            .build();

    protected final ChunkVertexType vertexType;
    protected final VertexFormat vertexFormat;

    protected RenderPipeline activeProgram;

    /**
     * Whether the pipeline compiled for the current pass expects the NeoSky bind group.
     *
     * <p>Captured once in {@link #begin} so that pipeline selection and uniform binding in
     * {@code DefaultChunkRenderer} can never disagree about it within a single draw sequence.
     */
    private boolean neoSkyShadows;

    public ShaderChunkRenderer(ChunkVertexType vertexType) {
        this.vertexType = vertexType;
        this.vertexFormat = vertexType.getVertexFormat();
    }

    protected RenderPipeline compileProgram(TerrainRenderPass pass, @Nullable OitStage stage) {
        var key = new ProgramKey(pass, activePositionLayout(), this.neoSkyShadows);

        if (stage == null) {
            RenderPipeline program = programs.get(key);

            if (program == null) {
                programs.put(key, program = this.createShader("blocks/block_layer_opaque", pass));
            }

            return program;
        } else {
            OitPipelineSet program = oitPrograms.get(key);

            if (program == null) {
                oitPrograms.put(key, program = this.createOITShader("blocks/block_layer_opaque", pass, stage));
            }

            return program.getPipeline(stage);
        }
    }

    private RenderPipeline createShader(String path, TerrainRenderPass pass) {
        List<String> constants = createShaderConstants(pass);

var builder = RenderPipeline.builder()
                .withBindGroupLayout(BIND_GROUP)
                .withBindGroupLayout(LIGHT_GROUP)
                .withPushConstantSize(DefaultChunkRenderer.PUSH_CONSTANT_RANGE)
                .withLocation(Identifier.fromNamespaceAndPath("sodium", pass.getPipeline().getLocation().getPath()))
                .withCull(true)
                .withVertexShader(Identifier.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque"))
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withVertexBinding(0, this.vertexFormat);

        if (pass.isTranslucent()) {
            builder.withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT), GpuFormat.RGBA8_UNORM, 0xFFFFFFFF));
        } else {
            builder.withColorTargetState(ColorTargetState.DEFAULT);
        }

        for (String s : constants) {
            builder.withShaderDefine(s);
        }

        // The bit widths chunk_vertex.glsl decodes with, as *valued* defines: the three position sliders
        // make the number of possible layouts far too large for one #if branch each, and only the widths
        // actually in use ever get compiled. They must match the layout CompactChunkVertex encoded with.
        var layout = activePositionLayout();

        builder.withShaderDefine("SODIUM_POSITION_X_BITS", layout.getXBits());
        builder.withShaderDefine("SODIUM_POSITION_Y_BITS", layout.getYBits());
        builder.withShaderDefine("SODIUM_POSITION_Z_BITS", layout.getZBits());

        if (pass.isTranslucent()) {
            builder.withShaderDefine("ALPHA_CUTOUT", 0.01f);
        } else if (pass.supportsFragmentDiscard()) {
            builder.withShaderDefine("ALPHA_CUTOUT", 0.5f);
        }

        // Paired with withBindGroupLayout(NEO_SKY_GROUP): the define and the group must appear
        // together or not at all, or the pipeline layout and the compiled shader disagree about
        // which uniforms exist.
        if (this.neoSkyShadows) {
            builder.withBindGroupLayout(NEO_SKY_GROUP);
            builder.withShaderDefine("NEO_SKY_SHADOWS");
        }

        return builder.build();
    }

    private OitPipelineSet createOITShader(String path, TerrainRenderPass pass, OitStage stage) {
        List<String> constants = createShaderConstants(pass);

var builder = RenderPipeline.builder()
                .withBindGroupLayout(BIND_GROUP)
                .withBindGroupLayout(LIGHT_GROUP)
                .withLocation(Identifier.fromNamespaceAndPath("sodium", pass.getPipeline().getLocation().getPath()))
                .withCull(true)
                .withVertexShader(Identifier.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque"))
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withVertexBinding(0, this.vertexFormat);

        if (this.neoSkyShadows) {
            builder.withBindGroupLayout(NEO_SKY_GROUP);
        }

        for (String s : constants) {
            builder.withShaderDefine(s);
        }

        // The bit widths chunk_vertex.glsl decodes with, as *valued* defines: the three position sliders
        // make the number of possible layouts far too large for one #if branch each, and only the widths
        // actually in use ever get compiled. They must match the layout CompactChunkVertex encoded with.
        var layout = activePositionLayout();

        builder.withShaderDefine("SODIUM_POSITION_X_BITS", layout.getXBits());
        builder.withShaderDefine("SODIUM_POSITION_Y_BITS", layout.getYBits());
        builder.withShaderDefine("SODIUM_POSITION_Z_BITS", layout.getZBits());

        if (pass.isTranslucent()) {
            builder.withShaderDefine("ALPHA_CUTOUT", 0.01f);
        } else if (pass.supportsFragmentDiscard()) {
            builder.withShaderDefine("ALPHA_CUTOUT", 0.5f);
        }

        // See createShader: the define and the bind group must be added together.
        if (this.neoSkyShadows) {
            builder.withShaderDefine("NEO_SKY_SHADOWS");
        }

        return OitPipelineSet.builder(
                "sodium_terrain", builder).withAccumulateModifier(i -> i.withBindGroupLayout(LIGHT_GROUP)).build();
    }

    private static List<String> createShaderConstants(TerrainRenderPass pass) {
        List<String> defines = new ArrayList<>();

        defines.add("USE_VERTEX_COMPRESSION");
        defines.add("USE_FOG");

        return defines;
    }

    private static VertexPositionLayout activePositionLayout() {
        return SodiumClientMod.options().performance.positionLayout();
    }

    protected void begin(TerrainRenderPass pass, FogParameters parameters, GpuSampler terrainSampler, @Nullable OitStage stage) {
        // Read once here so the pipeline layout chosen below and the uniform binding done by
        // DefaultChunkRenderer afterwards are guaranteed to describe the same set of resources.
        this.neoSkyShadows = NeoSkyShadowSystem.isActive();

        this.activeProgram = this.compileProgram(pass, stage);
    }

    /**
     * Whether the pipeline currently bound for this pass was compiled with the NeoSky bind group.
     *
     * <p>Binders must gate on this rather than on {@link NeoSkyShadowSystem#isActive()} directly: it is
     * the same value the layout was built from.
     */
    protected final boolean isNeoSkyShadowPipeline() {
        return this.neoSkyShadows;
    }

    protected void end(TerrainRenderPass pass) {
        this.activeProgram = null;
    }

    @Override
    public void delete() {
    }

}

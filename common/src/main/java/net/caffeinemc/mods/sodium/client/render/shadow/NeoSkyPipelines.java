package net.caffeinemc.mods.sodium.client.render.shadow;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.VertexPositionLayout;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The depth-only pipeline used to render cascade casters.
 *
 * <p>The caster pass replays Sodium's existing {@code MultiDrawBatch} commands, which are just
 * {@code (elementCount, baseVertex, elementOffset)} triples over the region GPU arenas. That means this
 * pipeline only has to agree with the terrain pipeline on what the batch data depends on — vertex
 * format, primitive topology and push-constant size — and may differ on everything else.
 *
 * <p>It declares no colour target at all. A pipeline with no colour attachments writes depth only,
 * which is exactly the caster pass's job and saves allocating a colour buffer nothing would read.
 *
 * <p>Cached per position layout for the same reason {@link ShaderChunkRenderer} caches its programs:
 * the three bit widths are baked in as valued {@code #define}s, so a pipeline compiled against a
 * previous layout would decode every vertex at the wrong precision and the cascade would come out
 * empty.
 */
public final class NeoSkyPipelines {
    /**
     * Deliberately narrower than {@code ShaderChunkRenderer.BIND_GROUP}.
     *
     * <p>{@code u_Globals} is kept because the caster vertex shader reuses Sodium's packed-position
     * decoding and its push constants. The matrix inside it is irrelevant here — the caster pass
     * supplies the light-space matrix through {@code u_NeoSkyCaster} — so the camera matrices the
     * terrain pass expects are simply never read.
     *
     * <p>{@code u_BlockTex}, {@code u_SectionTimeInfo} and {@code u_LightTex} are absent on purpose.
     * The caster fragment shader discards everything and writes no colour, so sampling them would
     * cost bandwidth for a result that is thrown away.
     */
    public static final BindGroupLayout CASTER_BIND_GROUP = BindGroupLayout.builder()
            .withUniform("u_Globals", UniformType.UNIFORM_BUFFER)
            .build();

    /**
     * The light-space matrix for the cascade currently being rendered.
     *
     * <p>A separate small block rather than a field in {@code u_NeoSky}: the receiver-side block
     * carries two cascades and the lighting scalars, while the caster pass needs exactly one matrix
     * and is the only place that reads this one.
     */
    public static final BindGroupLayout CASTER_MATRIX_GROUP = BindGroupLayout.builder()
            .withUniform("u_NeoSkyCaster", UniformType.UNIFORM_BUFFER)
            .build();

    private static final Map<VertexPositionLayout, RenderPipeline> CASTER_PIPELINES = new ConcurrentHashMap<>();

    private NeoSkyPipelines() {
    }

    /** Returns the caster pipeline compiled for the currently configured position layout. */
    public static RenderPipeline caster() {
        return CASTER_PIPELINES.computeIfAbsent(
                SodiumClientMod.options().performance.positionLayout(),
                NeoSkyPipelines::createCaster);
    }

    private static RenderPipeline createCaster(VertexPositionLayout layout) {
        return RenderPipeline.builder()
                .withBindGroupLayout(CASTER_BIND_GROUP)
                .withBindGroupLayout(CASTER_MATRIX_GROUP)
                // Must match the terrain pass exactly: the batch commands and push constants were
                // recorded against this range, and a different size would misalign every region.
                .withPushConstantSize(DefaultChunkRenderer.PUSH_CONSTANT_RANGE)
                .withLocation(Identifier.fromNamespaceAndPath("sodium", "blocks/neosky_shadow_depth"))
                .withVertexShader(Identifier.fromNamespaceAndPath("sodium", "blocks/neosky_shadow_depth"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("sodium", "blocks/neosky_shadow_depth"))
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                // Must match the terrain pass, or every vertex attribute offset is wrong.
                .withVertexBinding(0, ChunkMeshFormats.getCurrent().getVertexFormat())
                // chunk_vertex.glsl hard-errors without this, since its whole decode path is the
                // compact representation. It is what selects the packed decode over the fallback.
                .withShaderDefine("USE_VERTEX_COMPRESSION")
                // The bit widths chunk_vertex.glsl decodes with. These are valued defines rather
                // than a boolean because the three sliders admit far too many layouts for one
                // #if branch each, and only the one in use is ever compiled.
                .withShaderDefine("SODIUM_POSITION_X_BITS", layout.getXBits())
                .withShaderDefine("SODIUM_POSITION_Y_BITS", layout.getYBits())
                .withShaderDefine("SODIUM_POSITION_Z_BITS", layout.getZBits())
                // No withColorTargetState call: this pipeline writes depth and nothing else.
                .build();
    }

    /**
     * Clears cached pipelines.
     *
     * <p>Only needed on a position-layout change, which is rare enough that the map is normally left
     * alone; a stale entry would silently decode every vertex with the wrong bit widths.
     */
    public static void invalidate() {
        CASTER_PIPELINES.clear();
    }
}
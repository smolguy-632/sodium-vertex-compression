package net.caffeinemc.mods.sodium.client.render.shadow;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Renders one cascade's casters into its depth target by replaying Sodium's existing draw batches.
 *
 * <p>This is the whole trick behind making the feature affordable: nothing is re-meshed, no CPU-side
 * geometry is touched, and no per-chunk visibility walk happens. Each region already holds a
 * {@code MultiDrawBatch} of {@code (elementCount, baseVertex, elementOffset)} triples over its GPU
 * arena, recorded during the terrain pass. The caster pass binds a different pipeline to the same
 * batches and replays them, which means the shadow cost is roughly one extra draw per region rather
 * than a second terrain pipeline through the mesher.
 *
 * <p>Only {@code SOLID} and {@code CUTOUT} contribute. Translucent geometry is excluded because a
 * depth-only pass cannot reproduce alpha blending, so it would cast solid shadows through glass.
 */
public final class NeoSkyShadowBridge {
    /**
     * Reused across cascades and frames.
     *
     * <p>Multi-draw backends may need device-side scratch to record the indirect commands, so the
     * context is created once and re-pointed rather than recreated per pass.
     */
    private final DrawContext drawContext = DrawContext.create();

    /**
     * Renders casters for one cascade.
     *
     * <p>Call only when {@link NeoSkyCascade#shouldBuild} returns true. Rebuilding every frame would
     * double the terrain draw cost for no visible benefit: the shadow map is sampled with a stale
     * matrix-tolerant filter, and the cascade's own interval decides how quickly the world can
     * actually change underneath it.
     */
    public void render(NeoSkyCascade cascade,
                       ChunkRenderListIterable renderLists,
                       CameraTransform camera,
                       boolean indexedRenderingEnabled) {
        NeoSkyDepthTarget target = cascade.target();

        // Nothing to sample and nothing to draw into until the target has been sized.
        if (!target.isAllocated() || !cascade.matrixValid()) {
            return;
        }

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

        // Cleared to the far plane rather than 0: a cascade with stale depth in it would shadow the
        // entire world from the moment the light moved, which is far more visible than a one-frame-old
        // shadow would have been.
        encoder.clearDepthTexture(target.texture(), 1.0D);

        RenderPipeline pipeline = NeoSkyPipelines.caster();

        try (RenderPass pass = this.openPass(encoder, pipeline, target)) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            this.drawContext.setContext(pass, pipeline);

            this.drawCasters(pass, renderLists, camera, indexedRenderingEnabled);

            this.drawContext.endDraw();
        }

        this.drawContext.rotate();
    }

    /**
     * Replays the solid and cutout batches.
     *
     * <p>Each layer gets a <em>fresh</em> iterator. The terrain render list is one sequence per
     * frame and {@code iterator()} hands out a stateful view over it, so reusing the same iterator
     * for both layers would silently render cutout geometry as nothing at all.
     *
     * <p>The order is reverse, matching the terrain pass. Reversal matters for nothing in this pass on
     * its own — a depth-only pass has no order dependence — but keeping it avoids perturbing shared
     * per-region state that the batch recording path relies on.
     */
    private void drawCasters(RenderPass pass,
                             ChunkRenderListIterable renderLists,
                             CameraTransform camera,
                             boolean indexedRenderingEnabled) {
        this.drawPassCasters(pass, renderLists.iterator(true), camera,
                DefaultTerrainRenderPasses.SOLID, indexedRenderingEnabled);

        this.drawPassCasters(pass, renderLists.iterator(true), camera,
                DefaultTerrainRenderPasses.CUTOUT, indexedRenderingEnabled);
    }

    private void drawPassCasters(RenderPass pass,
                                 Iterator<ChunkRenderList> iterator,
                                 CameraTransform camera,
                                 TerrainRenderPass renderPass,
                                 boolean indexedRenderingEnabled) {
        while (iterator.hasNext()) {
            ChunkRenderList renderList = iterator.next();
            RenderRegion region = renderList.getRegion();

            // A null storage means the region has nothing for this layer. Not an error: the mesh
            // simply never produced geometry for it.
            if (region.getStorage(renderPass) == null) {
                continue;
            }

            var resources = region.getResources();
            if (resources == null) {
                continue;
            }

            MultiDrawBatch batch = region.getCachedBatch(renderPass);
            if (batch.isEmpty()) {
                continue;
            }

            // Caster geometry never uses indexed tessellation: that path only exists for the
            // translucent pass, and both layers drawn here feed a plain MultiDrawBatch.
            pass.setVertexBuffer(0, resources.getGeometryBuffer().slice());

            this.pushRegionConstants(pass, region, camera);
            batch.draw(this.drawContext);
        }
    }

    /**
     * Writes the 20-byte push-constant block.
     *
     * <p>Byte-for-byte the same layout the terrain pass uses: camera-relative section origin in the
     * first three floats, then section age and region id. The caster vertex shader only needs the
     * first three, but the size has to match the terrain pipeline's declared range, so the other two
     * are written rather than left as whatever was in the stack.
     */
    private void pushRegionConstants(RenderPass pass, RenderRegion region, CameraTransform camera) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer memory = stack.malloc(DefaultChunkRenderer.PUSH_CONSTANT_RANGE);
            long addr = MemoryUtil.memAddress(memory);

            float x = getCameraTranslation(region.getOriginX(), camera.intX, camera.fracX);
            float y = getCameraTranslation(region.getOriginY(), camera.intY, camera.fracY);
            float z = getCameraTranslation(region.getOriginZ(), camera.intZ, camera.fracZ);

            MemoryUtil.memPutFloat(addr, x);
            MemoryUtil.memPutFloat(addr + 4, y);
            MemoryUtil.memPutFloat(addr + 8, z);
            MemoryUtil.memPutInt(addr + 12, Math.toIntExact(System.currentTimeMillis() - region.getCreationTime()));
            MemoryUtil.memPutInt(addr + 16, region.getId());

            pass.pushConstants(memory);
        }
    }

    /**
     * A region origin expressed relative to the camera, as the terrain pass does.
     *
     * <p>Kept identical to {@code DefaultChunkRenderer}'s version so the caster and terrain draws
     * agree on where section (0,0,0) of a region actually is.
     */
    private static float getCameraTranslation(int origin, int cameraInt, float cameraFrac) {
        return (float) (origin - cameraInt) - cameraFrac;
    }

    /**
     * Opens a depth-only pass: no colour attachment, depth cleared to the far plane.
     *
     * <p>Creates a descriptor with only a depth attachment, matching vanilla's pattern for
     * depth-only render passes (e.g. LevelRenderer.WaterMask).
     */
    private RenderPass openPass(CommandEncoder encoder, RenderPipeline pipeline, NeoSkyDepthTarget target) {
        RenderPassDescriptor descriptor = RenderPassDescriptor.builder(
                () -> pipeline.getLocation().toString()
            ).withDepthAttachment(target.view(), OptionalDouble.of(1.0D))
            .build();
        return encoder.createRenderPass(descriptor);
    }

    public void delete() {
        this.drawContext.delete();
    }
}
package net.caffeinemc.mods.sodium.client.render.shadow;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;

/**
 * The {@code u_NeoSky} uniform block: the two cascade matrices and the lighting scalars the terrain
 * shader needs.
 *
 * <p>A dedicated block rather than extra fields on {@code u_Globals}, for two reasons:
 *
 * <ul>
 *   <li>{@code u_Globals} is written per region through a hand-maintained {@link Std140Builder}
 *       write order. Adding six more matrices would put its offsets at the mercy of a second feature
 *       that BlockLightTest has already reshuffled twice.
 *   <li>Shadow state changes every frame and is identical for every region. Making it per-region
 *       would mean writing it redundantly once per region per frame.
 * </ul>
 *
 * <p>Backed by a ring buffer of three slots, mirroring {@code PostFxChain}. A slot is rotated once
 * per frame and never rewritten while the GPU may still be reading it, which is what stops the CPU
 * writing under a draw call that has not retired.
 */
public final class NeoSkyShadowUniforms {
    /**
     * Two 4x4 matrices (128 B) plus five vec4s (80 B) = 208 B, which std140 rounds to 208 because
     * every field is already 16-byte aligned. Must stay in step with the declaration in
     * {@code neosky_shadow.glsl}.
     */
    public static final int BLOCK_BYTES = 208;

    private static final int SLOT_COUNT = 3;

    private final MappableRingBuffer buffer;

    private int slot;
    private long frame;

    public NeoSkyShadowUniforms() {
        this.buffer = new MappableRingBuffer(
                () -> "Sodium NeoSky Shadow Uniforms",
                GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM,
                BLOCK_BYTES * SLOT_COUNT);
    }

    public GpuBuffer currentBuffer() {
        return this.buffer.currentBuffer();
    }

    /**
     * Writes this frame's state into the next ring slot.
     *
     * <p>Only the near matrix is sent. The far matrix is written to a second binding in the same
     * block, because both cascades are sampled by the same fragment and uploading them as one block
     * costs a single binding instead of two.
     */
    public void write(NeoSkyCascade near, NeoSkyCascade far, NeoSkyCelestialPath celestial,
                      NeoSkyLighting lighting, int filterSamples) {
        try (GpuBufferSlice.MappedView view = this.buffer.currentBuffer().map(false, true)) {
            ByteBuffer data = view.data();
            data.position(this.slot * BLOCK_BYTES);

            Std140Builder builder = Std140Builder.intoBuffer(data);

            builder.putMat4f(MATRIX.set(near.lightMatrix()));
            builder.putMat4f(MATRIX.set(far.lightMatrix()));

            // near: radius, texelSize, baseBias, slopeBias
            builder.putVec4(near.radius(), near.texelSize(), near.baseBias(), near.slopeBias());
            // far: radius, texelSize, baseBias, slopeBias
            builder.putVec4(far.radius(), far.texelSize(), far.baseBias(), far.slopeBias());

            // xyz active light direction, w = 1 when the moon is up
            builder.putVec4(celestial.activeDirection().x(), celestial.activeDirection().y(),
                    celestial.activeDirection().z(), celestial.usingMoon() ? 1.0F : 0.0F);

            // rgb direct colour, a = active direct strength
            float[] direct = lighting.directColor();
            builder.putVec4(direct[0], direct[1], direct[2],
                    lighting.activeDirectStrength(celestial.usingMoon()));

            // rgb ambient colour, a = ambient strength
            float[] ambient = lighting.ambientColor();
            builder.putVec4(ambient[0], ambient[1], ambient[2], lighting.ambientStrength());

            // blendStart, farRadius, filterSamples, generation
            builder.putVec4(
                    near.radius() * NeoSkyFrameState.CASCADE_BLEND_START,
                    far.radius(),
                    filterSamples,
                    near.generation());

            builder.get();
        }

        this.buffer.rotate();
        this.slot = (this.slot + 1) % SLOT_COUNT;
        this.frame++;
    }

    /** Number of frames written so far. Used to detect that the block has never been populated. */
    public long frame() {
        return this.frame;
    }

    private static final Matrix4f MATRIX = new Matrix4f();

    public void close() {
        this.buffer.close();
    }
}
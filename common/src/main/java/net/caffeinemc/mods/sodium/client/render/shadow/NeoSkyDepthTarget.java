package net.caffeinemc.mods.sodium.client.render.shadow;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;

/**
 * A depth-only render target for one shadow cascade.
 *
 * <p>The replacement for Luxium's {@code NeoSkyDepthFramebuffer}, which wrapped a raw GL FBO. Under
 * RenderPearl there is no FBO to own: the device allocates a depth texture and each render pass
 * declares it as its attachment. All this needs to own is the texture, its view, and the size, so
 * that a resolution change reallocates instead of silently sampling a stale-sized grid.
 *
 * <p>There is deliberately no colour attachment. A cascade is sampled with {@code texelFetch} and
 * read as a single depth value, so allocating colour would waste bandwidth for nothing.
 */
public final class NeoSkyDepthTarget {
    private final String label;

    private int width;
    private int height;

    private @Nullable GpuTexture texture;
    private @Nullable GpuTextureView view;

    public NeoSkyDepthTarget(String label) {
        this.label = label;
    }

    /**
     * Ensures the backing texture matches the requested size.
     *
     * <p>Returns true when the texture was reallocated, which is exactly when the cascade's
     * {@code texelSize} changes and every cached shadow lookup must be invalidated.
     */
    public boolean resize(int width, int height) {
        if (this.texture != null && this.width == width && this.height == height) {
            return false;
        }

        close();

        // Clamped well above zero: a zero-sized attachment is a framebuffer-incomplete error, and a
        // minimised window can briefly hand us a zero-sized main target to derive this from.
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);

        this.texture = RenderSystem.getDevice().createTexture(
                this.label,
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST,
                GpuFormat.D32_FLOAT, this.width, this.height, 1, 1);

        this.view = RenderSystem.getDevice().createTextureView(this.texture);
        return true;
    }

    /** The depth texture view to bind as the pass's depth attachment, or null before first resize. */
    public @Nullable GpuTextureView view() {
        return this.view;
    }

    /** The depth texture to clear, or null before first resize. */
    public @Nullable GpuTexture texture() {
        return this.texture;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public boolean isAllocated() {
        return this.texture != null;
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

        this.width = 0;
        this.height = 0;
    }
}
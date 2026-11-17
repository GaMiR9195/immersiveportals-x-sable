package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.HashMap;
import java.util.Iterator;
import java.util.UUID;

/**
 * The textures of the portal surfaces a view draws ({@link IrisPortalSurface}), kept per view.
 *
 * <p>Each portal has its own texture holding last frame's whole image of its view, so the surface
 * never samples anything but that portal's content. One blit per composited portal.
 */
final class IrisPortalBackdrop implements AutoCloseable {
    private static final Object KEY = new Object();
    private static final int EVICT_AFTER_FRAMES = 60;

    private final HashMap<UUID, PortalTexture> portals = new HashMap<>();

    static IrisPortalBackdrop of(IrisViewContexts.Context view) {
        return view.attachment(KEY, IrisPortalBackdrop::new);
    }

    static @Nullable IrisPortalBackdrop peek(IrisViewContexts.Context view) {
        return view.peekAttachment(KEY);
    }

    /** The texture slot holding last frame's content of {@code portal}, or -1. */
    int slotOf(Portal portal) {
        PortalTexture texture = portals.get(portal.getUUID());
        if (texture == null) return -1;
        return texture.storedFrame == IrisViewContexts.frame() - 1 ? texture.slot : -1;
    }

    int textureIdOfSlot(int slot) {
        for (PortalTexture texture : portals.values()) {
            if (texture.slot == slot) return texture.color;
        }
        return 0;
    }

    void evictUnused() {
        int frame = IrisViewContexts.frame();
        Iterator<PortalTexture> it = portals.values().iterator();
        while (it.hasNext()) {
            PortalTexture texture = it.next();
            if (frame - texture.storedFrame > EVICT_AFTER_FRAMES) {
                texture.close();
                it.remove();
            }
        }
    }

    /** Keeps the content of {@code portal}; {@code content} holds the whole image of its view. */
    void store(Portal portal, RenderTarget content) {
        PortalTexture texture = portals.get(portal.getUUID());
        if (texture == null || texture.width != content.viewWidth || texture.height != content.viewHeight) {
            int slot = texture == null ? freeSlot() : texture.slot;
            if (texture != null) texture.close();
            texture = new PortalTexture(slot, content.viewWidth, content.viewHeight);
            portals.put(portal.getUUID(), texture);
        }
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, content.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, texture.fbo);
        GL30.glBlitFramebuffer(
            0, 0, texture.width, texture.height, 0, 0, texture.width, texture.height,
            GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST
        );
        texture.storedFrame = IrisViewContexts.frame();
    }

    private int freeSlot() {
        int slot = 0;
        while (true) {
            int candidate = slot;
            if (portals.values().stream().noneMatch(t -> t.slot == candidate)) return slot;
            slot++;
        }
    }

    @Override
    public void close() {
        portals.values().forEach(PortalTexture::close);
        portals.clear();
    }

    private static final class PortalTexture implements AutoCloseable {
        final int slot;
        final int width;
        final int height;
        final int fbo;
        final int color;
        int storedFrame = Integer.MIN_VALUE / 2;

        PortalTexture(int slot, int width, int height) {
            this.slot = slot;
            this.width = width;
            this.height = height;
            color = GlStateManager._genTexture();
            GlStateManager._bindTexture(color);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL30.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL30.GL_CLAMP_TO_EDGE);
            GlStateManager._texImage2D(
                GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null
            );
            GlStateManager._bindTexture(0);
            fbo = GlStateManager.glGenFramebuffers();
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
        }

        @Override
        public void close() {
            GlStateManager._glDeleteFramebuffers(fbo);
            GlStateManager._deleteTexture(color);
        }
    }
}

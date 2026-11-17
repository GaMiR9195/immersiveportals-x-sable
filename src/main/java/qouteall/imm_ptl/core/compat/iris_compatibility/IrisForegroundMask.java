package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import ipl.sable.render.clip.IplClipPlanes;
import net.irisshaders.iris.targets.RenderTargets;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.NameTagFootprints;

import java.util.List;

/**
 * Pixels that stay in front of portal content: the first-person hand and name tags.
 *
 * <p>Portal content replaces a view's pixels where the stencil holds the portal's layer. Kept
 * pixels get {@link #KEEP_BIT} so no portal layer matches them, while their low bits still name
 * the view's layer. Iris draws the hand inside the level render with its depth squeezed into
 * [0.4375, 0.5625], so a portal plane a few centimeters away would cover it; name tags write no
 * depth where they are see-through.
 */
public final class IrisForegroundMask {
    public static final int KEEP_BIT = 0x80;
    public static final int LAYER_MASK = 0x7F;

    private static int capturedFrame = -1;
    private static int noHandDepthTexture;

    private IrisForegroundMask() {}

    public static void onNoHandDepthCaptured(RenderTargets targets) {
        if (!IrisViewContexts.isMainViewActive()) return;
        capturedFrame = IrisViewContexts.frame();
        noHandDepthTexture = targets.getDepthTextureNoHand().getTextureId();
    }

    /** Marks the hand of the player's view, whose level render is in {@code source}. */
    public static void markHand(RenderTarget source, RenderTarget dest) {
        ShaderInstance shader = MyRenderHelper.handDepthMaskShader;
        if (capturedFrame != IrisViewContexts.frame() || shader == null) return;

        begin(dest, 0);
        RenderSystem.disableDepthTest();
        shader.setSampler("DepthSampler", source.getDepthTextureId());
        shader.setSampler("NoHandDepthSampler", noHandDepthTexture);
        shader.apply();
        BufferBuilder builder = RenderSystem.renderThreadTesselator()
            .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        builder.addVertex(0, 0, 0);
        builder.addVertex(1, 0, 0);
        builder.addVertex(1, 1, 0);
        builder.addVertex(0, 1, 0);
        BufferUploader.draw(builder.buildOrThrow());
        shader.clear();
        end();
    }

    /** Marks the name tags in front of what {@code dest}'s depth holds. */
    public static void markNameTags(
        RenderTarget dest, int layer, List<NameTagFootprints.Footprint> nameTags,
        Matrix4f modelView, Matrix4f projection
    ) {
        ShaderInstance shader = GameRenderer.getPositionShader();
        if (nameTags.isEmpty() || shader == null) return;

        begin(dest, layer);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.disableCull();
        shader.MODEL_VIEW_MATRIX.set(modelView);
        shader.PROJECTION_MATRIX.set(projection);
        shader.apply();
        BufferBuilder builder = RenderSystem.renderThreadTesselator()
            .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        Vector3f p = new Vector3f();
        for (NameTagFootprints.Footprint tag : nameTags) {
            addVertex(builder, tag.pose(), p.set(tag.x0(), tag.y0(), 0));
            addVertex(builder, tag.pose(), p.set(tag.x0(), tag.y1(), 0));
            addVertex(builder, tag.pose(), p.set(tag.x1(), tag.y1(), 0));
            addVertex(builder, tag.pose(), p.set(tag.x1(), tag.y0(), 0));
        }
        MeshData mesh = builder.build();
        if (mesh != null) BufferUploader.draw(mesh);
        shader.clear();
        RenderSystem.enableCull();
        end();
    }

    private static void addVertex(BufferBuilder builder, Matrix4f pose, Vector3f p) {
        pose.transformPosition(p);
        builder.addVertex(p.x, p.y, p.z);
    }

    /** Only pixels of the layer's view are marked; they keep their layer in the low bits. */
    private static void begin(RenderTarget dest, int layer) {
        dest.bindWrite(false);
        IplClipPlanes.suppress();
        RenderSystem.depthMask(false);
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.disableBlend();
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilMask(0xFF);
        GL11.glStencilFunc(GL11.GL_EQUAL, KEEP_BIT | layer, LAYER_MASK);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
    }

    private static void end() {
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        IplClipPlanes.unsuppress();
    }
}

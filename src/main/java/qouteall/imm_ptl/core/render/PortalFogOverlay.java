package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import ipl.sable.render.clip.IplClipPlanes;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * The fog of a view laid over the content of its portals.
 *
 * <p>Portal content is drawn by a camera on the other side, with that world's fog and no fluid
 * around it, so the fog of the view the portal is seen from (distance, water, lava, powder snow,
 * blindness, darkness) never reaches it and the portal shows through fog. The view's fog is
 * blended over the portal area by the distance from the view's camera to the portal plane.
 */
public final class PortalFogOverlay {
    /** Fog start beyond this means the view has no fog (see MyRenderHelper#transformFogDistance). */
    private static final float NO_FOG_START = 1.0e5f;
    /** Vanilla's water fog end once the eyes have adapted (FogRenderer#setupFog). */
    private static final float ADAPTED_WATER_FOG_END = 96;
    private static final float CLOSER_WATER_FOG_FACTOR = 0.85f;

    public record Fog(float start, float end, float[] color, int shape) {
        public static final Fog NONE = new Fog(Float.MAX_VALUE, Float.MAX_VALUE, new float[4], 0);

        boolean isVisible() {
            return start < NO_FOG_START && color[3] > 0;
        }
    }

    private static Fog shaderPackViewFog = Fog.NONE;

    private PortalFogOverlay() {}

    /** The fog currently set up for the view being rendered. Reads only. */
    public static Fog current() {
        return new Fog(
            RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(),
            RenderSystem.getShaderFogColor().clone(), RenderSystem.getShaderFogShape().getIndex()
        );
    }

    /**
     * Called inside every level render once its fog is set up. With shader packs portals are
     * composited after the level render, which resets the fog, so the view's fog is kept here.
     */
    public static void onLevelFogSetUp() {
        if (IrisInterface.invoker.isShaders()) shaderPackViewFog = withAdaptedWaterFog(current());
    }

    /** The fog of the latest level render with a shader pack. */
    public static Fog shaderPackViewFog() {
        return shaderPackViewFog;
    }

    /**
     * Shader packs keep their water fog steady, while vanilla thins it out over the time spent
     * underwater (LocalPlayer#getWaterVision). Portals take the fully adapted water fog so they do
     * not fade in and out against the water around them.
     */
    private static Fog withAdaptedWaterFog(Fog fog) {
        Minecraft client = Minecraft.getInstance();
        Camera camera = client.gameRenderer.getMainCamera();
        if (camera.getFluidInCamera() != FogType.WATER || !(camera.getEntity() instanceof LocalPlayer player)) {
            return fog;
        }
        float end = ADAPTED_WATER_FOG_END;
        if (player.level().getBiome(player.blockPosition()).is(BiomeTags.HAS_CLOSER_WATER_FOG)) {
            end *= CLOSER_WATER_FOG_FACTOR;
        }
        int shape = fog.shape();
        float renderDistance = Math.max(client.gameRenderer.getRenderDistance(), 32);
        if (end > renderDistance) {
            end = renderDistance;
            shape = FogShape.CYLINDER.getIndex();
        }
        // Adaptation only scales the color up to its brightest channel.
        float[] color = fog.color().clone();
        float brightest = Math.max(color[0], Math.max(color[1], color[2]));
        if (brightest > 0) {
            for (int i = 0; i < 3; i++) color[i] /= brightest;
        }
        return new Fog(fog.start(), end, color, shape);
    }

    /**
     * Blends {@code fog} over the portal area in the bound framebuffer. The caller limits it to
     * the portal's pixels with the stencil; {@code depthTested} additionally keeps it behind what
     * is in front of the portal plane in the depth buffer.
     */
    public static void draw(Portal portal, Fog fog, Matrix4f modelView, Matrix4f projection, boolean depthTested) {
        ShaderInstance shader = MyRenderHelper.portalFogShader;
        if (shader == null || !fog.isVisible() || portal.isFuseView()) return;

        if (depthTested) {
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            CHelper.enableDepthClamp();
        }
        else {
            RenderSystem.disableDepthTest();
        }
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.colorMask(true, true, true, false);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
            GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE
        );
        IplClipPlanes.suppress();

        shader.MODEL_VIEW_MATRIX.set(modelView);
        shader.PROJECTION_MATRIX.set(projection);
        shader.safeGetUniform("FogStart").set(fog.start());
        shader.safeGetUniform("FogEnd").set(fog.end());
        shader.safeGetUniform("FogColor").set(fog.color()[0], fog.color()[1], fog.color()[2], fog.color()[3]);
        shader.safeGetUniform("FogShape").set(fog.shape());
        shader.apply();

        BufferBuilder builder = RenderSystem.renderThreadTesselator()
            .begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION);
        Vec3 origin = portal.getOriginPos().subtract(CHelper.getCurrentCameraPos());
        portal.renderViewAreaMesh(origin, (x0, y0, z0, x1, y1, z1, x2, y2, z2) -> {
            builder.addVertex((float) x0, (float) y0, (float) z0);
            builder.addVertex((float) x1, (float) y1, (float) z1);
            builder.addVertex((float) x2, (float) y2, (float) z2);
        });
        MeshData mesh = builder.build();
        if (mesh != null) BufferUploader.draw(mesh);
        shader.clear();

        IplClipPlanes.unsuppress();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        if (depthTested) CHelper.disableDepthClamp();
    }
}

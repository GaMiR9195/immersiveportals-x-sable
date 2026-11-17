package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.IPMcHelper;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The portal as a surface of the shaded world.
 *
 * <p>Shader packs post-process each view (bloom, TAA, motion blur, depth of field) before the
 * portal content is laid over it. Whatever the view drew behind the portal plane would bleed
 * into those effects around the portal and show through translucent things in front of it.
 * Every view therefore draws its portals as opaque surfaces textured with last frame's content
 * ({@link IrisPortalBackdrop}); the fresh content replaces them once the view is done.
 *
 * <p>In the shadow pass the portal casts the shadow chosen by {@link IPGlobal.PortalShadow}.
 */
public final class IrisPortalSurface {
    /** Closer than this the surface touches the near plane; the view's own image shows there. */
    private static final double MIN_SURFACE_DISTANCE = 0.3;
    /** Screen coordinates are interpolated per triangle; subdividing keeps the error sub-pixel. */
    private static final int SUBDIVISION_LEVELS = 3;
    private static final double MAX_SHADOW_RANGE = 256;
    /** Glass shadows are tiled by blocks; huge portals get bigger tiles. */
    private static final int MAX_TILES_PER_AXIS = 64;

    /** One texture location per backdrop slot; a slot resolves to a portal of the view being drawn. */
    private static final ArrayList<ResourceLocation> BACKDROP_SLOTS = new ArrayList<>();
    private static @Nullable MultiBufferSource.BufferSource shadowBuffers;

    private IrisPortalSurface() {}

    /** Draws the portal surface in the view being rendered, in the entity pass. */
    public static void renderSurface(Portal portal, MultiBufferSource buffers) {
        if (IrisInterface.invoker.isRenderingShadowMap() || portal.isFuseView()) return;
        IrisPortalBackdrop backdrop = IrisPortalBackdrop.peek(IrisViewContexts.current());
        if (backdrop == null) return;
        int slot = backdrop.slotOf(portal);
        if (slot < 0) return;
        Vec3 cameraPos = CHelper.getCurrentCameraPos();
        if (!portal.isInFrontOfPortal(cameraPos)
            || portal.getDistanceToNearestPointInPortal(cameraPos) < MIN_SURFACE_DISTANCE) {
            return;
        }

        Matrix4f viewProjection = new Matrix4f(RenderSystem.getProjectionMatrix())
            .mul(RenderSystem.getModelViewMatrix());
        Vec3 n = portal.getNormal();
        VertexConsumer consumer = buffers.getBuffer(RenderType.entitySolid(backdropLocation(slot)));
        Vector4f clip = new Vector4f();

        portal.renderViewAreaMesh(portal.getOriginPos().subtract(cameraPos), (x0, y0, z0, x1, y1, z1, x2, y2, z2) ->
            subdivide(
                new double[]{x0, y0, z0, x1, y1, z1, x2, y2, z2}, SUBDIVISION_LEVELS,
                t -> {
                    float[] uv = new float[6];
                    for (int i = 0; i < 3; i++) {
                        viewProjection.transform(clip.set((float) t[i * 3], (float) t[i * 3 + 1], (float) t[i * 3 + 2], 1));
                        if (clip.w <= 1.0e-4f) return;
                        uv[i * 2] = clip.x / clip.w * 0.5f + 0.5f;
                        uv[i * 2 + 1] = clip.y / clip.w * 0.5f + 0.5f;
                    }
                    // Entity render types draw quads: the last vertex is repeated.
                    for (int i : new int[]{0, 1, 2, 2}) {
                        consumer.addVertex(
                            (float) t[i * 3], (float) t[i * 3 + 1], (float) t[i * 3 + 2], 0xFFFFFFFF,
                            uv[i * 2], uv[i * 2 + 1], OverlayTexture.NO_OVERLAY, LightTexture.FULL_BRIGHT,
                            (float) n.x, (float) n.y, (float) n.z
                        );
                    }
                }
            )
        );
    }

    /**
     * Draws the portal planes into the shadow map. Opaque casters belong before the shadow
     * pass copies its opaque depth, translucent ones after the translucent terrain.
     */
    public static void renderShadowCasters(Camera camera, boolean translucentStage) {
        IPGlobal.PortalShadow mode = IPGlobal.portalShadow;
        if (mode == IPGlobal.PortalShadow.none) return;
        boolean glass = mode == IPGlobal.PortalShadow.purple_glass;
        if (glass != translucentStage) return;

        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level == null) return;
        Vec3 cameraPos = camera.getPosition();
        double range = Math.min(client.options.getEffectiveRenderDistance() * 16, MAX_SHADOW_RANGE);
        List<Portal> portals = IPMcHelper.getNearbyPortals(level, cameraPos, range)
            .filter(p -> p.isVisible() && !p.isFuseView() && !p.getIsGlobal())
            .toList();
        if (portals.isEmpty()) return;

        TextureAtlasSprite sprite = client.getBlockRenderer().getBlockModelShaper()
            .getParticleIcon((glass ? Blocks.NETHER_PORTAL : Blocks.BLACK_CONCRETE).defaultBlockState());
        RenderType type = glass
            ? RenderType.entityTranslucent(InventoryMenu.BLOCK_ATLAS)
            : RenderType.entitySolid(InventoryMenu.BLOCK_ATLAS);
        Matrix4f pose = new Matrix4f(ShadowRenderer.MODELVIEW);
        Matrix3f normalPose = new Matrix3f(pose);

        MultiBufferSource.BufferSource buffers = shadowBuffers();
        VertexConsumer consumer = buffers.getBuffer(type);
        for (Portal portal : portals) {
            new ShadowCaster(consumer, portal, cameraPos, sprite, pose, normalPose).emit();
        }
        buffers.endBatch();
    }

    /**
     * One portal plane in the shadow map, tiled like blocks: tiles are about one block, sized so
     * a whole number of them spans the portal, and laid out from its center.
     */
    private static final class ShadowCaster {
        private final VertexConsumer consumer;
        private final TextureAtlasSprite sprite;
        private final Matrix4f pose;
        private final Vec3 origin;
        private final Vec3 axisW;
        private final Vec3 axisH;
        private final Vector3f normal;
        private final double tileW;
        private final double tileH;
        private final int tilesW;
        private final int tilesH;
        private final Portal portal;
        private final Vector3f p = new Vector3f();

        ShadowCaster(
            VertexConsumer consumer, Portal portal, Vec3 cameraPos, TextureAtlasSprite sprite,
            Matrix4f pose, Matrix3f normalPose
        ) {
            this.consumer = consumer;
            this.portal = portal;
            this.sprite = sprite;
            this.pose = pose;
            this.origin = portal.getOriginPos().subtract(cameraPos);
            this.axisW = portal.getAxisW();
            this.axisH = portal.getAxisH();
            Vec3 n = portal.getNormal();
            this.normal = new Vector3f((float) n.x, (float) n.y, (float) n.z).mul(normalPose);
            this.tilesW = tileCount(portal.getWidth());
            this.tilesH = tileCount(portal.getHeight());
            this.tileW = Math.max(portal.getWidth(), 1.0e-6) / tilesW;
            this.tileH = Math.max(portal.getHeight(), 1.0e-6) / tilesH;
        }

        private static int tileCount(double size) {
            return (int) Math.max(1, Math.min(MAX_TILES_PER_AXIS, Math.round(size)));
        }

        void emit() {
            portal.renderViewAreaMesh(origin, (x0, y0, z0, x1, y1, z1, x2, y2, z2) -> {
                List<double[]> triangle = List.of(vertex(x0, y0, z0), vertex(x1, y1, z1), vertex(x2, y2, z2));
                double minA = Double.MAX_VALUE, maxA = -Double.MAX_VALUE;
                double minB = Double.MAX_VALUE, maxB = -Double.MAX_VALUE;
                for (double[] v : triangle) {
                    minA = Math.min(minA, v[0]);
                    maxA = Math.max(maxA, v[0]);
                    minB = Math.min(minB, v[1]);
                    maxB = Math.max(maxB, v[1]);
                }
                for (int i = (int) Math.floor(minA); i < Math.ceil(maxA); i++) {
                    for (int j = (int) Math.floor(minB); j < Math.ceil(maxB); j++) {
                        emitTile(clipToCell(triangle, i, j), i, j);
                    }
                }
            });
        }

        /** {tile coordinate along width, along height, x, y, z}; tile corners are integers. */
        private double[] vertex(double x, double y, double z) {
            double dx = x - origin.x, dy = y - origin.y, dz = z - origin.z;
            double a = (dx * axisW.x + dy * axisW.y + dz * axisW.z) / tileW + tilesW * 0.5;
            double b = (dx * axisH.x + dy * axisH.y + dz * axisH.z) / tileH + tilesH * 0.5;
            return new double[]{a, b, x, y, z};
        }

        private void emitTile(List<double[]> polygon, int i, int j) {
            // Fan triangles drawn with both windings: the sun may light either side.
            for (int k = 1; k + 1 < polygon.size(); k++) {
                double[][] t = {polygon.get(0), polygon.get(k), polygon.get(k + 1)};
                for (int index : new int[]{0, 1, 2, 2, 2, 1, 0, 0}) {
                    double[] v = t[index];
                    pose.transformPosition(p.set((float) v[2], (float) v[3], (float) v[4]));
                    consumer.addVertex(
                        p.x, p.y, p.z, 0xFFFFFFFF,
                        sprite.getU((float) clamp01(v[0] - i)), sprite.getV((float) clamp01(1 - (v[1] - j))),
                        OverlayTexture.NO_OVERLAY, LightTexture.FULL_BRIGHT, normal.x, normal.y, normal.z
                    );
                }
            }
        }

        private static List<double[]> clipToCell(List<double[]> polygon, int i, int j) {
            polygon = clip(polygon, 0, i, true);
            polygon = clip(polygon, 0, i + 1, false);
            polygon = clip(polygon, 1, j, true);
            return clip(polygon, 1, j + 1, false);
        }

        /** Sutherland-Hodgman against one cell edge, keeping the side above or below {@code bound}. */
        private static List<double[]> clip(List<double[]> polygon, int axis, double bound, boolean keepAbove) {
            List<double[]> out = new ArrayList<>(polygon.size() + 1);
            for (int k = 0; k < polygon.size(); k++) {
                double[] a = polygon.get(k);
                double[] b = polygon.get((k + 1) % polygon.size());
                boolean aIn = keepAbove ? a[axis] >= bound : a[axis] <= bound;
                boolean bIn = keepAbove ? b[axis] >= bound : b[axis] <= bound;
                if (aIn) out.add(a);
                if (aIn != bIn) {
                    double s = (bound - a[axis]) / (b[axis] - a[axis]);
                    double[] c = new double[5];
                    for (int m = 0; m < 5; m++) c[m] = a[m] + (b[m] - a[m]) * s;
                    c[axis] = bound;
                    out.add(c);
                }
            }
            return out;
        }
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static void subdivide(double[] t, int levels, Consumer<double[]> out) {
        if (levels == 0) {
            out.accept(t);
            return;
        }
        double[] m = new double[9];
        for (int e = 0; e < 3; e++) {
            int a = e, b = (e + 1) % 3;
            for (int k = 0; k < 3; k++) m[e * 3 + k] = (t[a * 3 + k] + t[b * 3 + k]) * 0.5;
        }
        subdivide(new double[]{t[0], t[1], t[2], m[0], m[1], m[2], m[6], m[7], m[8]}, levels - 1, out);
        subdivide(new double[]{m[0], m[1], m[2], t[3], t[4], t[5], m[3], m[4], m[5]}, levels - 1, out);
        subdivide(new double[]{m[6], m[7], m[8], m[3], m[4], m[5], t[6], t[7], t[8]}, levels - 1, out);
        subdivide(new double[]{m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8]}, levels - 1, out);
    }

    private static MultiBufferSource.BufferSource shadowBuffers() {
        if (shadowBuffers == null) {
            shadowBuffers = MultiBufferSource.immediate(new ByteBufferBuilder(4096));
        }
        return shadowBuffers;
    }

    private static ResourceLocation backdropLocation(int slot) {
        while (BACKDROP_SLOTS.size() <= slot) {
            int index = BACKDROP_SLOTS.size();
            ResourceLocation location =
                McHelper.newResourceLocation("immersive_portals", "iris_portal_backdrop_" + index);
            Minecraft.getInstance().getTextureManager().register(location, new Backdrop(index));
            BACKDROP_SLOTS.add(location);
        }
        return BACKDROP_SLOTS.get(slot);
    }

    /** A backdrop slot of the view being rendered, owned by {@link IrisPortalBackdrop}. */
    private static final class Backdrop extends AbstractTexture {
        private final int slot;

        Backdrop(int slot) {
            this.slot = slot;
        }

        @Override
        public int getId() {
            IrisPortalBackdrop backdrop = IrisPortalBackdrop.peek(IrisViewContexts.current());
            return backdrop == null ? 0 : backdrop.textureIdOfSlot(slot);
        }

        @Override
        public void releaseId() {}

        @Override
        public void load(ResourceManager resourceManager) {}

        @Override
        public void setFilter(boolean blur, boolean mipmap) {}
    }
}

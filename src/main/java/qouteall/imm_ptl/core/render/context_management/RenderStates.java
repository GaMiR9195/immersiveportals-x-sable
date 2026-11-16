package qouteall.imm_ptl.core.render.context_management;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationClient;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.ducks.IEGameRenderer;
import qouteall.imm_ptl.core.miscellaneous.ClientPerformanceMonitor;
import qouteall.imm_ptl.core.mixin.client.particle.IEParticle;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.animation.StableClientTimer;
import qouteall.imm_ptl.core.render.CrossPortalEntityRenderer;
import qouteall.imm_ptl.core.render.CrossPortalViewRendering;
import qouteall.imm_ptl.core.render.ForceMainThreadRebuild;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.QueryManager;
import qouteall.q_misc_util.Helper;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RenderStates {
    
    private static final Logger LOGGER = LogUtils.getLogger();
    
    public static int frameIndex = 0;
    
    public static ResourceKey<Level> originalPlayerDimension;
    public static Vec3 originalPlayerPos = Vec3.ZERO;
    public static Vec3 originalPlayerLastTickPos = Vec3.ZERO;
    public static GameType originalGameMode;
    public static AABB originalPlayerBoundingBox;
    
    /**
     * It will be 0 right after ticking.
     */
    private static float partialTick = 0;
    
    public static Set<ResourceKey<Level>> renderedDimensions = new HashSet<>();
    public static List<List<WeakReference<Portal>>> lastPortalRenderInfos = new ArrayList<>();
    public static List<List<WeakReference<Portal>>> portalRenderInfos = new ArrayList<>();
    public static int portalsRenderedThisFrame = 0;// mixins to sodium use that
    
    public static Vec3 lastCameraPos = Vec3.ZERO;
    public static Vec3 cameraPosDelta = Vec3.ZERO;
    
    public static boolean shouldForceDisableCull = false;
    
    public static long renderStartNanoTime;
    
    public static double viewBobFactor;
    
    public static Matrix4f basicProjectionMatrix;
    
    public static Camera originalCamera;
    
    public static String debugText;
    
    public static boolean isLaggy = false;
    
    public static boolean isRenderingEntities = false;
    
    public static boolean renderedScalingPortal = false;
    
    public static void updatePreRenderInfo(
        float newPartialTick
    ) {
        ClientWorldLoader.initializeIfNeeded();
        
        Entity cameraEntity = MyRenderHelper.client.cameraEntity;
        
        if (cameraEntity == null) {
            return;
        }
        
        originalPlayerDimension = cameraEntity.level().dimension();
        originalPlayerPos = cameraEntity.position();
        originalPlayerLastTickPos = McHelper.lastTickPosOf(cameraEntity);
        PlayerInfo entry = CHelper.getClientPlayerListEntry();
        originalGameMode = entry != null ? entry.getGameMode() : GameType.CREATIVE;
        partialTick = newPartialTick;
        
        renderedDimensions.clear();
        lastPortalRenderInfos = portalRenderInfos;
        portalRenderInfos = new ArrayList<>();
        portalsRenderedThisFrame = 0;
        WorldRenderInfo.onFrameStart();
        
        FogRendererContext.update();
        
        renderStartNanoTime = System.nanoTime();
        
        updateViewBobbingFactor(cameraEntity);
        
        basicProjectionMatrix = null;
        originalCamera = MyRenderHelper.client.gameRenderer.getMainCamera();
        
        updateIsLaggy();
        
        ForceMainThreadRebuild.onPreRender();
        
        debugText = "";
//        debugText = originalCamera.getPos().toString();
        
        QueryManager.queryStallCounter = 0;
        
        Vec3 velocity = McHelper.getWorldVelocity(cameraEntity);
        originalPlayerBoundingBox = cameraEntity.getBoundingBox().expandTowards(
            -velocity.x, -velocity.y, -velocity.z
        );
        
        CrossPortalEntityRenderer.updateCrossings(partialTick);
    }
    
    //protect the player from mirror room lag attack
    private static void updateIsLaggy() {
        if (!IPGlobal.lagAttackProof) {
            isLaggy = false;
            return;
        }
        if (isLaggy) {
            if (ClientPerformanceMonitor.getMinimumFps() > 15) {
                isLaggy = false;
            }
        }
        else {
            if (lastPortalRenderInfos.size() > 10) {
                if (ClientPerformanceMonitor.getAverageFps() < 8 || ClientPerformanceMonitor.getMinimumFps() < 6) {
                    MyRenderHelper.client.gui.setOverlayMessage(
                        Component.translatable("imm_ptl.laggy"),
                        false
                    );
                    isLaggy = true;
                }
            }
        }
    }
    
    /**
     * The bob offset is a translation of the view: near a portal it could carry the view across
     * the portal plane before the cross-portal view decides which side the camera is on, so it
     * fades out there.
     *
     * <p>First person: fades between 2 and 1 blocks from the eyes to the nearest portal.
     * Third person: the detached camera is what moves, not the eyes; bobbing fades only in a thin
     * layer around a portal plane near the camera and stays as in vanilla elsewhere, including
     * while the camera looks through a portal. The main camera of the last frame may already be
     * moved to a portal's far side, so this frame's local camera position is probed.
     */
    private static void updateViewBobbingFactor(Entity cameraEntity) {
        Minecraft client = MyRenderHelper.client;
        if (!client.options.getCameraType().isFirstPerson() && client.level != null) {
            Vec3 cameraPos = CrossPortalViewRendering.getLocalCameraPos(cameraEntity, getPartialTick());
            double distance = getDistanceToNearestPortal(cameraPos);
            setViewBobFactor(Mth.clamp((distance - 0.08) / 0.3, 0.0, 1.0));
            return;
        }
        
        double distance = getDistanceToNearestPortal(cameraEntity.getEyePosition(getPartialTick()));
        setViewBobFactor(Mth.clamp(distance - 1, 0.0, 1.0));
    }
    
    private static double getDistanceToNearestPortal(Vec3 pos) {
        return CHelper.getClientNearbyPortals(16)
            .mapToDouble(portal -> portal.getDistanceToNearestPointInPortal(pos))
            .min().orElse(100.0);
    }
    
    public static double getViewBobbingOffsetMultiplier() {
        if (!IPGlobal.viewBobbingReduce) {
            return 1;
        }
    
        if (!WorldRenderInfo.isViewBobbingEnabled()) {
            return 0;
        }
        
        double allScaling = PortalRendering.getExtraModelViewScaling();
        
        return viewBobFactor * allScaling;
    }
    
    private static void setViewBobFactor(double arg) {
        if (arg < viewBobFactor) {
            viewBobFactor = arg;
        }
        else {
            viewBobFactor = Mth.lerp(0.1, viewBobFactor, arg);
        }
    }
    
    public static void onTotalRenderEnd() {
        Minecraft client = Minecraft.getInstance();
        IEGameRenderer gameRenderer = (IEGameRenderer) Minecraft.getInstance().gameRenderer;
        gameRenderer.ip_setLightmapTextureManager(ClientWorldLoader
            .getDimensionRenderHelper(client.level.dimension()).lightmapTexture);
        
        Vec3 currCameraPos = client.gameRenderer.getMainCamera().getPosition();
        cameraPosDelta = currCameraPos.subtract(lastCameraPos);
        if (cameraPosDelta.lengthSqr() > 1) {
            cameraPosDelta = Vec3.ZERO;
        }
        lastCameraPos = currCameraPos;
        
        
    }
    
    public static int getRenderedPortalNum() {
        return portalRenderInfos.size();
    }
    
    public static boolean isDimensionRendered(ResourceKey<Level> dimensionType) {
        if (dimensionType == originalPlayerDimension) {
            return true;
        }
        return renderedDimensions.contains(dimensionType);
    }
    
    public static boolean shouldRenderParticle(Particle particle) {
        if (((IEParticle) particle).portal_getWorld() != Minecraft.getInstance().level) {
            return false;
        }
        if (PortalRendering.isRendering()) {
            Portal renderingPortal = PortalRendering.getRenderingPortal();
            Vec3 particlePos = particle.getBoundingBox().getCenter();
            return renderingPortal.isOnDestinationSide(particlePos, 0.5);
        }
        return true;
    }
    
    public static void setPartialTick(float partialTick_) {
        partialTick = partialTick_;
    }
    
    /**
     * This does not always equal Minecraft.getFrameTime.
     * It will be 0 right after ticking.
     */
    public static float getPartialTick() {
        return partialTick;
    }
    
    public static List<String> collectDebugText() {
        List<String> result = new ArrayList<>();
        result.add("Rendered Portals: " + lastPortalRenderInfos.size());
        
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            Portal collidingPortal = ((IEEntity) player).ip_getCollidingPortal();
            if (collidingPortal != null) {
                String text = "Colliding " + collidingPortal.toString();
                result.addAll(Helper.splitStringByLen(text, 50));
            }
        }
        
        result.add("Occlusion Query Stall: " + QueryManager.queryStallCounter);
        result.add("Client Perf %s %d %d".formatted(
            ClientPerformanceMonitor.level,
            ClientPerformanceMonitor.getAverageFps(),
            ClientPerformanceMonitor.getAverageFreeMemoryMB()
        ));
        
        result.add(StableClientTimer.getDebugString());
        
        String blockPointingInfo = BlockManipulationClient.getDebugString();
        if (blockPointingInfo != null) {
            result.add(blockPointingInfo);
        }
        
        if (debugText != null && !debugText.isEmpty()) {
            result.addAll(Helper.splitStringByLen(
                "Debug: " + debugText,
                50
            ));
        }
        
        return result;
    }
}

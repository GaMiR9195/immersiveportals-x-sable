package qouteall.imm_ptl.core.render;

import com.mojang.datafixers.util.Pair;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.ducks.IECamera;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalUtils;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.imm_ptl.core.teleportation.ClientTeleportationManager;

/**
 * The main view rendered from the far side of a portal the player has not gone through: the
 * detached third-person camera behind a portal the player stands in front of, or a first-person
 * eye carried through a portal before the player (isometric view, smoothed eye height).
 *
 * <p>In third person the camera is where the camera itself (vanilla's or a camera mod's) puts it
 * this frame, with its block collision passing portals ({@link PortalCameraClip}): a camera
 * that ends up behind a portal is rendered from the far side, at the same place, so its
 * distance, offsets and zoom animations carry through the opening unchanged.
 */
public class CrossPortalViewRendering {
    private static final Minecraft client = Minecraft.getInstance();
    
    /** Reused for the camera of the current frame; render thread only. */
    private static final Camera PROBE_CAMERA = new Camera();
    
    private CrossPortalViewRendering() {}
    
    /** Renders the cross-portal view when the camera is through a portal; true if rendered. */
    public static boolean renderCrossPortalView() {
        if (!IPGlobal.enableCrossPortalView || client.level == null || client.cameraEntity == null) {
            return false;
        }
        
        Entity cameraEntity = client.cameraEntity;
        float partialTick = RenderStates.getPartialTick();
        Camera camera = setupLocalCamera(cameraEntity, partialTick);
        Vec3 eyePos = ClientTeleportationManager.getPlayerEyePos(partialTick);
        
        CrossPortalCamera crossPortal = isThirdPerson()
            ? thirdPersonCamera(camera, cameraEntity, eyePos)
            : firstPersonCamera(camera, cameraEntity, eyePos);
        if (crossPortal == null) {
            return false;
        }
        
        ((IECamera) RenderStates.originalCamera).portal_setPos(crossPortal.position());
        
        Portal portal = crossPortal.portal();
        WorldRenderInfo worldRenderInfo = new WorldRenderInfo.Builder()
            .setWorld(ClientWorldLoader.getWorld(portal.getDestDim()))
            .setCameraPos(crossPortal.position())
            .setCameraTransformation(portal.getAdditionalCameraTransformation())
            .setOverwriteCameraTransformation(false)
            .setDescription(null)
            .setRenderDistance(client.options.getEffectiveRenderDistance())
            .setDoRenderHand(false)
            // the bob offset is part of the projection: the view stays continuous with the normal one
            .setEnableViewBobbing(true)
            .build();
        
        IPCGlobal.renderer.invokeWorldRendering(worldRenderInfo);
        
        return true;
    }
    
    /**
     * The position vanilla gives the camera this frame in the camera entity's own level (in third
     * person: detached and pulled in by local blocks), regardless of portals.
     */
    public static Vec3 getLocalCameraPos(Entity cameraEntity, float partialTick) {
        return setupLocalCamera(cameraEntity, partialTick).getPosition();
    }
    
    private static Camera setupLocalCamera(Entity cameraEntity, float partialTick) {
        IECamera mainCamera = (IECamera) client.gameRenderer.getMainCamera();
        ((IECamera) PROBE_CAMERA).ip_setCameraY(mainCamera.ip_getCameraY(), mainCamera.ip_getLastCameraY());
        PROBE_CAMERA.setup(client.level, cameraEntity, isThirdPerson(), isFrontView(), partialTick);
        return PROBE_CAMERA;
    }
    
    private record CrossPortalCamera(Portal portal, Vec3 position) {}
    
    @Nullable
    private static CrossPortalCamera firstPersonCamera(Camera camera, Entity cameraEntity, Vec3 eyePos) {
        Vec3 cameraPos = camera.getPosition();
        Vec3 rayEnd = TransformationManager.getIsometricAdjustedCameraPos(camera);
        Pair<Portal, Vec3> portalHit = raytracePortal(eyePos, rayEnd, cameraEntity);
        if (portalHit == null) {
            return null;
        }
        Portal portal = portalHit.getFirst();
        return new CrossPortalCamera(portal, portal.transformPoint(cameraPos));
    }
    
    /**
     * The camera through the portal between the eyes and where the camera was placed; a camera
     * moved sideways off the eyes' line (shoulder cameras) is taken through the portal its own
     * rays passed.
     */
    @Nullable
    private static CrossPortalCamera thirdPersonCamera(Camera camera, Entity cameraEntity, Vec3 eyePos) {
        Vec3 cameraPos = camera.getPosition();
        Pair<Portal, Vec3> portalHit = raytracePortal(eyePos, cameraPos, cameraEntity);
        Portal portal = portalHit != null ? portalHit.getFirst() : PortalCameraClip.portalBehind(cameraPos);
        if (portal == null) {
            return null;
        }
        return new CrossPortalCamera(portal, portal.transformPoint(cameraPos));
    }
    
    @Nullable
    private static Pair<Portal, Vec3> raytracePortal(Vec3 from, Vec3 to, Entity cameraEntity) {
        return PortalUtils.raytracePortals(client.level, from, to, true, portal -> true)
            .filter(hit -> hit.getFirst().canTeleportEntity(cameraEntity))
            .map(hit -> Pair.of(hit.getFirst(), hit.getSecond().hitPos()))
            .orElse(null);
    }
    
    private static boolean isFrontView() {
        return client.options.getCameraType().isMirrored();
    }
    
    private static boolean isThirdPerson() {
        return !client.options.getCameraType().isFirstPerson();
    }
}

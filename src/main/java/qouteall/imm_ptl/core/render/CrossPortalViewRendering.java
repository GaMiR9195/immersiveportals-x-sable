package qouteall.imm_ptl.core.render;

import com.mojang.datafixers.util.Pair;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.ScaleUtils;
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
 * <p>In third person the camera is placed as vanilla places it ({@code Camera.getMaxZoom}): along
 * the look ray, stopped by blocks with a small probe box. Through a portal the ray is split at the
 * opening: blocks on this side in front of the opening stop it here (no cross-portal view), and
 * the rest of the ray continues on the far side, where the blocks behind the destination opening
 * stop the camera.
 */
public class CrossPortalViewRendering {
    private static final Minecraft client = Minecraft.getInstance();
    
    /** Half size of vanilla's camera collision probe. */
    private static final double CAMERA_PROBE = 0.1;
    /**
     * Local blocks this close to the opening do not stop the camera: they are the wall the
     * opening is placed on, reached by the probe rays only around the opening's plane.
     */
    private static final double OPENING_TOLERANCE = 0.2;
    
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
    
    @Nullable
    private static CrossPortalCamera thirdPersonCamera(Camera camera, Entity cameraEntity, Vec3 eyePos) {
        Vec3 backward = new Vec3(camera.getLookVector()).scale(-1).normalize();
        double maxDistance = getThirdPersonMaxDistance();
        Pair<Portal, Vec3> portalHit = raytracePortal(eyePos, eyePos.add(backward.scale(maxDistance)), cameraEntity);
        if (portalHit == null) {
            return null;
        }
        
        Portal portal = portalHit.getFirst();
        Vec3 openingPos = portalHit.getSecond();
        double nearDistance = openingPos.distanceTo(eyePos);
        double localLimit = collideCamera(client.level, eyePos, backward, nearDistance, cameraEntity, 0);
        if (localLimit < nearDistance - OPENING_TOLERANCE) {
            // blocked on this side before the opening: the normal view is right
            return null;
        }
        
        Vec3 destStart = portal.transformPoint(openingPos);
        Vec3 destDirection = portal.transformLocalVecNonScale(backward).normalize();
        double destDistance = (maxDistance - nearDistance) * portal.getScale();
        double destLimit = collideCamera(
            portal.getDestinationWorld(), destStart, destDirection, destDistance, cameraEntity, OPENING_TOLERANCE
        );
        return new CrossPortalCamera(portal, destStart.add(destDirection.scale(destLimit)));
    }
    
    @Nullable
    private static Pair<Portal, Vec3> raytracePortal(Vec3 from, Vec3 to, Entity cameraEntity) {
        return PortalUtils.raytracePortals(client.level, from, to, true, portal -> true)
            .filter(hit -> hit.getFirst().canTeleportEntity(cameraEntity))
            .map(hit -> Pair.of(hit.getFirst(), hit.getSecond().hitPos()))
            .orElse(null);
    }
    
    /**
     * Vanilla's camera collision ({@code Camera.getMaxZoom}): eight rays from the corners of a
     * small box around {@code start} along {@code direction}; the nearest block hit limits the
     * distance. Hits closer than {@code ignoreBelow} are skipped (rays starting inside the wall the
     * destination opening is placed on).
     */
    private static double collideCamera(
        Level level, Vec3 start, Vec3 direction, double maxDistance, Entity entity, double ignoreBelow
    ) {
        double limit = maxDistance;
        for (int i = 0; i < 8; i++) {
            Vec3 from = start.add(
                ((i & 1) * 2 - 1) * CAMERA_PROBE,
                ((i >> 1 & 1) * 2 - 1) * CAMERA_PROBE,
                ((i >> 2 & 1) * 2 - 1) * CAMERA_PROBE
            );
            Vec3 to = from.add(direction.scale(maxDistance));
            BlockHitResult hit = level.clip(new ClipContext(
                from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity
            ));
            if (hit.getType() == HitResult.Type.MISS) {
                continue;
            }
            double distance = hit.getLocation().distanceTo(start);
            if (distance >= ignoreBelow) {
                limit = Math.min(limit, distance);
            }
        }
        return limit;
    }
    
    private static boolean isFrontView() {
        return client.options.getCameraType().isMirrored();
    }
    
    private static boolean isThirdPerson() {
        return !client.options.getCameraType().isFirstPerson();
    }
    
    private static double getThirdPersonMaxDistance() {
        return 4.0d * ScaleUtils.computeThirdPersonScale(client.cameraEntity);
    }
}

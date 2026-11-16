package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.vertex.PoseStack;
import de.nick1st.imm_ptl.events.ClientCleanupEvent;
import de.nick1st.imm_ptl.events.DimensionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.common.NeoForge;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.collision.CollisionHelper;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.ducks.IEWorldRenderer;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalManipulation;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.imm_ptl.core.render.renderer.PortalRenderer;
import qouteall.q_misc_util.Helper;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Entities halfway through a portal: cut at the portal on this side and drawn again, transformed,
 * on the far side.
 *
 * <p>Crossings are found every frame from the entities' interpolated positions, so the cut and the
 * far-side image follow the entity from the first frame it touches an opening to the frame it
 * leaves it, including the frames right after a teleport.
 */
@OnlyIn(Dist.CLIENT)
public class CrossPortalEntityRenderer {
    private static final Minecraft client = Minecraft.getInstance();
    
    /** How far an entity may move between its tick position and its rendered position. */
    private static final double ENTITY_SEARCH_MARGIN = 2.0;
    
    /** Teleportable portals of each client level, refreshed every tick. */
    private static final IdentityHashMap<ClientLevel, List<Portal>> teleportablePortals = new IdentityHashMap<>();
    
    /** Portals each entity crosses at its rendered position in the current frame. */
    private static final IdentityHashMap<Entity, List<Portal>> crossings = new IdentityHashMap<>();
    
    public static boolean isRenderingEntityNormally = false;
    
    public static boolean isRenderingEntityProjection = false;
    
    public static void init() {
        NeoForge.EVENT_BUS.addListener(IPGlobal.PostClientTickEvent.class, e -> CrossPortalEntityRenderer.refreshPortals());

        NeoForge.EVENT_BUS.addListener(ClientCleanupEvent.class, e -> CrossPortalEntityRenderer.cleanUp());
        NeoForge.EVENT_BUS.addListener(DimensionEvents.CLIENT_DIMENSION_DYNAMIC_REMOVE_EVENT.class, e -> CrossPortalEntityRenderer.cleanUp());
    }
    
    private static void cleanUp() {
        teleportablePortals.clear();
        crossings.clear();
    }
    
    private static void refreshPortals() {
        teleportablePortals.clear();
        if (!ClientWorldLoader.getIsInitialized()) {
            return;
        }
        for (ClientLevel level : ClientWorldLoader.getClientWorlds()) {
            List<Portal> portals = new ArrayList<>();
            for (Portal globalPortal : GlobalPortalStorage.getGlobalPortals(level)) {
                if (globalPortal.isTeleportable()) {
                    portals.add(globalPortal);
                }
            }
            for (Entity entity : level.entitiesForRendering()) {
                if (entity instanceof Portal portal && portal.isTeleportable()) {
                    portals.add(portal);
                }
            }
            if (!portals.isEmpty()) {
                teleportablePortals.put(level, portals);
            }
        }
    }
    
    /** Start of a frame (and again after a teleport): the crossings at the rendered positions. */
    public static void updateCrossings(float partialTick) {
        crossings.clear();
        if (!isCrossPortalRenderingEnabled()) {
            return;
        }
        teleportablePortals.forEach((level, portals) -> {
            for (Portal portal : portals) {
                if (portal.isRemoved() || portal.level() != level) {
                    continue;
                }
                AABB searchBox = portal.getBoundingBox().inflate(ENTITY_SEARCH_MARGIN);
                for (Entity entity : level.getEntities((Entity) null, searchBox, e -> !(e instanceof Portal))) {
                    if (isCrossing(entity, portal, partialTick)) {
                        crossings.computeIfAbsent(entity, e -> new ArrayList<>(1)).add(portal);
                    }
                }
            }
        });
    }
    
    private static boolean isCrossing(Entity entity, Portal portal, float partialTick) {
        Vec3 renderOffset = entity.getPosition(partialTick).subtract(entity.position());
        AABB renderBox = entity.getBoundingBox().move(renderOffset);
        return renderBox.intersects(portal.getBoundingBox())
            && CollisionHelper.mayEntityCollideWithPortal(
                entity, portal, entity.getEyePosition(partialTick), renderBox
            );
    }
    
    /**
     * The portal whose plane cuts {@code entity} on this side. There is one object cut: an entity
     * crossing two openings at once is cut at the first.
     */
    private static @Nullable Portal getCuttingPortal(Entity entity) {
        List<Portal> portals = crossings.get(entity);
        return portals == null ? null : portals.get(0);
    }
    
    public static void onBeginRenderingEntitiesAndBlockEntities(Matrix4f modelView) {
        isRenderingEntityNormally = true;
        
        if (PortalRendering.isRendering()) {
            FrontClipping.setupInnerClipping(
                PortalRendering.getActiveClippingPlane(),
                modelView, 0
            );
        }
    }
    
    /**
     * The cuts are clip distances written by every world program, Iris shader packs included
     * ({@code IplClipShaderInjector}); flushing the buffer source also draws Iris's batched entities.
     */
    private static boolean isCrossPortalRenderingEnabled() {
        return IPGlobal.correctCrossPortalEntityRendering;
    }
    
    public static void onEndRenderingEntitiesAndBlockEntities(PoseStack matrixStack) {
        isRenderingEntityNormally = false;
        
        FrontClipping.disableClipping();
        
        if (!isCrossPortalRenderingEnabled()) {
            return;
        }
        
        renderEntityProjections(matrixStack);
    }
    
    public static void beforeRenderingEntity(Entity entity, PoseStack matrixStack) {
        if (!isCrossPortalRenderingEnabled()) {
            return;
        }
        if (PortalRendering.isRendering()) {
            return;
        }
        Portal cuttingPortal = getCuttingPortal(entity);
        if (cuttingPortal != null) {
            // the triangles built so far are drawn without the cut
            client.renderBuffers().bufferSource().endBatch();
            FrontClipping.setupOuterClipping(matrixStack, cuttingPortal);
        }
    }
    
    public static void afterRenderingEntity(Entity entity) {
        if (!isCrossPortalRenderingEnabled()) {
            return;
        }
        if (!PortalRendering.isRendering() && crossings.containsKey(entity)) {
            // drawn with the cut in its own draw call
            client.renderBuffers().bufferSource().endBatch();
            FrontClipping.disableClipping();
        }
    }
    
    //if an entity is in overworld but halfway through a nether portal
    //then it has a projection in nether
    private static void renderEntityProjections(PoseStack matrixStack) {
        if (!isCrossPortalRenderingEnabled()) {
            return;
        }
        
        ResourceKey<Level> clientDim = client.level.dimension();
        
        crossings.forEach((entity, portals) -> {
            for (Portal portal : portals) {
                if (!(portal instanceof Mirror) && portal.getDestDim() == clientDim) {
                    renderProjectedEntity(entity, portal, matrixStack);
                }
            }
        });
    }
    
    public static boolean hasIntersection(
        Vec3 outerPlanePos, Vec3 outerPlaneNormal,
        Vec3 entityPos, Vec3 collidingPortalNormal
    ) {
        return entityPos.subtract(outerPlanePos).dot(outerPlaneNormal) > 0.01 &&
            outerPlanePos.subtract(entityPos).dot(collidingPortalNormal) > 0.01;
    }
    
    private static void renderProjectedEntity(
        Entity entity,
        Portal collidingPortal,
        PoseStack matrixStack
    ) {
        if (PortalRendering.isRendering()) {
            Portal renderingPortal = PortalRendering.getRenderingPortal();
            if (Portal.isFlippedPortal(renderingPortal, collidingPortal)
                || Portal.isReversePortal(renderingPortal, collidingPortal)
            ) {
                return;
            }
        }
        
        // The already built triangles are drawn without the object cut.
        FrontClipping.disableClipping();
        client.renderBuffers().bufferSource().endBatch();
        
        // The pass's front clip stays on; the colliding portal's destination plane is the object
        // cut, so only the part that really went through the colliding portal shows.
        FrontClipping.setupInnerClipping(collidingPortal.getInnerClipping(), matrixStack.last().pose(), 0);
        try {
            renderEntity(entity, collidingPortal, matrixStack);
            client.renderBuffers().bufferSource().endBatch();
        }
        finally {
            FrontClipping.disableClipping();
        }
    }
    
    private static void renderEntity(
        Entity entity,
        Portal transformingPortal,
        PoseStack matrixStack
    ) {
        Vec3 cameraPos = client.gameRenderer.getMainCamera().getPosition();
        
        ClientLevel newWorld = ClientWorldLoader.getWorld(transformingPortal.getDestDim());
        
        Vec3 entityPos = entity.position();
        Vec3 entityEyePos = McHelper.getEyePos(entity);
        Vec3 entityLastTickPos = McHelper.lastTickPosOf(entity);
        Vec3 entityLastTickEyePos = McHelper.getLastTickEyePos(entity);
        Level oldWorld = entity.level();
        
        Vec3 newEyePos = transformingPortal.transformPoint(entityEyePos);
        
        if (PortalRendering.isRendering()) {
            Portal renderingPortal = PortalRendering.getRenderingPortal();
            
            Vec3 transformedEntityPos = newEyePos.subtract(McHelper.getEyeOffset(entity));
            AABB transformedBoundingBox = McHelper.getBoundingBoxWithMovedPosition(entity, transformedEntityPos);
            
            boolean intersects = PortalManipulation.isOtherSideBoxInside(transformedBoundingBox, renderingPortal);
            
            if (!intersects) {
                return;
            }
        }
        
        if (entity instanceof LocalPlayer) {
            if (!IPGlobal.renderYourselfInPortal) {
                return;
            }
            
            if (!transformingPortal.getDoRenderPlayer()) {
                return;
            }
            
            if (client.options.getCameraType().isFirstPerson()) {
                //avoid rendering player too near and block view
                double dis = newEyePos.distanceTo(cameraPos);
                double valve = 0.5 + entityLastTickPos.distanceTo(entityPos);
                if (transformingPortal.getScaling() > 1) {
                    valve *= transformingPortal.getScaling();
                }
                if (dis < valve) {
                    return;
                }
                
                AABB transformedBoundingBox =
                    Helper.transformBox(RenderStates.originalPlayerBoundingBox, transformingPortal::transformPoint);
                if (transformedBoundingBox.contains(CHelper.getCurrentCameraPos())) {
                    return;
                }
            }
        }
        
        isRenderingEntityProjection = true;
        matrixStack.pushPose();
        try {
            // we don't switch the entity position now
            // to make the entity to render in the new position,
            // we change the camera pos passed in
            
            // renderedPos = entityPos - cameraPos
            // cameraPos = entityPos - renderedPos
            
            // expectedRenderedPos = newEntityPos - cameraPos
            // newCameraPos = entityPos - expectedRenderedPos
            //              = entityPos - newEntityPos + cameraPos
            
            Vec3 entityInstantPos = entityLastTickPos.lerp(entityPos, RenderStates.getPartialTick());
            Vec3 newEntityInstantPos = transformingPortal.transformPoint(entityInstantPos);
            Vec3 newCameraPos = entityInstantPos.subtract(newEntityInstantPos).add(cameraPos);
            
            setupEntityProjectionRenderingTransformation(
                transformingPortal, matrixStack,
                entityPos, entityLastTickPos,
                newCameraPos
            );
            
            MultiBufferSource.BufferSource consumers = client.renderBuffers().bufferSource();
            ((IEWorldRenderer) client.levelRenderer).ip_myRenderEntity(
                entity,
                newCameraPos.x, newCameraPos.y, newCameraPos.z,
                RenderStates.getPartialTick(), matrixStack,
                consumers
            );
            //immediately invoke draw call
            consumers.endBatch();
        }
        finally {
            matrixStack.popPose();
            isRenderingEntityProjection = false;
        }
    }
    
    private static void setupEntityProjectionRenderingTransformation(
        Portal portal, PoseStack matrixStack,
        Vec3 entityPos, Vec3 entityLastTickPos, Vec3 cameraPos
    ) {
        if (portal.getScaling() == 1.0 && portal.getRotation() == null) {
            return;
        }
        
        Vec3 anchor = entityLastTickPos.lerp(entityPos, RenderStates.getPartialTick())
            .subtract(cameraPos);
        
        matrixStack.translate(anchor.x, anchor.y, anchor.z);
        
        float scaling = (float) portal.getScaling();
        matrixStack.scale(scaling, scaling, scaling);
        
        if (portal.getRotation() != null) {
            matrixStack.mulPose(portal.getRotation().toMcQuaternion());
        }
        
        matrixStack.translate(-anchor.x, -anchor.y, -anchor.z);
    }
    
    public static boolean shouldRenderPlayerDefault() {
        if (!IPGlobal.renderYourselfInPortal) {
            return false;
        }
        if (!WorldRenderInfo.isRendering()) {
            return false;
        }
        LocalPlayer player = client.player;
        assert player != null;
        
        if (PortalRendering.isRendering()) {
            Portal renderingPortal = PortalRendering.getRenderingPortal();
            if (renderingPortal instanceof Mirror) {
                // if the camera pos is too close to the mirror,
                // it will show the inside of the player head.
                // avoid rendering player in this case.
                float width = player.getBbWidth();
                if (renderingPortal.getDistanceToNearestPointInPortal(player.getEyePosition()) < width * 0.8) {
                    return false;
                }
            }
        }
        
        if (client.level == player.level()) {
            return true;
        }
        
        return false;
    }
    
    public static boolean shouldRenderEntityNow(Entity entity) {
        Validate.notNull(entity);
        if (IrisInterface.invoker.isRenderingShadowMap()) {
            return true;
        }
        if (PortalRendering.isRendering()) {
            Portal renderingPortal = PortalRendering.getRenderingPortal();
            Portal collidingPortal = getCuttingPortal(entity);
            
            if (entity instanceof Player && !renderingPortal.getDoRenderPlayer()) {
                return false;
            }
            
            if (collidingPortal != null && !(entity instanceof LocalPlayer)) {
                if (renderingPortal instanceof Portal) {
                    if (!Portal.isReversePortal(collidingPortal, ((Portal) renderingPortal))) {
                        Vec3 cameraPos = PortalRenderer.client.gameRenderer.getMainCamera().getPosition();
                        
                        boolean isHidden = cameraPos.subtract(collidingPortal.getOriginPos())
                            .dot(collidingPortal.getNormal()) < 0;
                        if (isHidden) {
                            return false;
                        }
                    }
                }
            }
            
            return renderingPortal.isOnDestinationSide(
                getRenderingCameraPos(entity), -0.01
            );
        }
        return true;
    }
    
    public static boolean shouldRenderPlayerNormally(Entity entity) {
        if (!client.options.getCameraType().isFirstPerson()) {
            return true;
        }
        
        if (RenderStates.originalPlayerBoundingBox.contains(CHelper.getCurrentCameraPos())) {
            return false;
        }
        
        double distanceToCamera =
            getRenderingCameraPos(entity)
                .distanceTo(client.gameRenderer.getMainCamera().getPosition());
        //avoid rendering player too near and block view except mirror
        return distanceToCamera > 1 || PortalRendering.isRenderingOddNumberOfMirrors();
    }
    
    public static Vec3 getRenderingCameraPos(Entity entity) {
        if (entity instanceof LocalPlayer) {
            return RenderStates.originalPlayerPos.add(
                McHelper.getEyeOffset(entity)
            );
        }
        return entity.getEyePosition(RenderStates.getPartialTick());
    }
}

package qouteall.imm_ptl.core.compat.mixin.sodium;

import ipl.sable.render.IplForeignPerspective;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.RenderSectionVisitor;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.GraphDirectionSet;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.collections.WriteQueue;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.PortalApertureSections;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

@Mixin(OcclusionCuller.class)
public abstract class MixinSodiumOcclusionCuller {
    @Shadow(remap = false)
    private int outOfWorldRadius;
    
    @Shadow(remap = false)
    protected abstract RenderSection getRenderSection(int x, int y, int z);
    
    @Shadow(remap = false)
    public static boolean isWithinFrustum(Viewport viewport, RenderSection section) {
        throw new RuntimeException();
    }
    
    @Shadow(remap = false)
    private static int getOutwardDirections(SectionPos origin, RenderSection section) {
        throw new RuntimeException();
    }
    
    @Shadow(remap = false)
    private static void visitNeighbors(WriteQueue<RenderSection> queue, RenderSection section, int outgoing, int frame) {
        throw new RuntimeException();
    }
    
    @Unique
    private @Nullable SectionPos ip_modifiedStartPoint;
    
    /** Sections of the rendering portal's opening that seed the search, or null. */
    @Unique
    private long @Nullable [] ip_apertureSeeds;
    
    @Unique
    private final ObjectArrayList<RenderSection> ip_enteredSeeds = new ObjectArrayList<>();
    
    @Unique
    private static boolean ip_tolerantInitialFrustumTestFail;
    
    @SuppressWarnings("ConstantValue")
    @ModifyVariable(
        method = "findVisible", at = @At("HEAD"), argsOnly = true, remap = false
    )
    boolean modifyUseOcclusionCulling(
        boolean originalValue,
        RenderSectionVisitor visitor, Viewport viewport, float searchDistance, boolean useOcclusionCulling, int frame
    ) {
        ip_modifiedStartPoint = null;
        ip_apertureSeeds = null;
        ip_tolerantInitialFrustumTestFail = false;
        
        if (!PortalRendering.isRendering() || IplForeignPerspective.isRendering()) {
            // the player's own view or another mod's camera: Sodium decides
            return originalValue;
        }
        
        Portal portal = PortalRendering.getRenderingPortal();
        
        ip_apertureSeeds = PortalApertureSections.seedsCurrentPass() ? PortalApertureSections.collect(portal) : null;
        if (ip_apertureSeeds != null) {
            // smartCull already reflects PortalRendering.shouldEnableSodiumCaveCulling()
            return originalValue;
        }
        
        boolean doUseOcclusionCulling = PortalRendering.shouldEnableSodiumCaveCulling();
        
        Vec3 cameraPos = CHelper.getCurrentCameraPos();
        ip_modifiedStartPoint = portal.getPortalShape().getModifiedVisibleSectionIterationOrigin(
            portal, cameraPos
        );
        if (ip_modifiedStartPoint != null) {
            doUseOcclusionCulling = false;
            
            RenderSection renderSection = getRenderSection(
                ip_modifiedStartPoint.x(), ip_modifiedStartPoint.y(), ip_modifiedStartPoint.z()
            );
            if (renderSection != null && !isWithinFrustum(viewport, renderSection)) {
                ip_tolerantInitialFrustumTestFail = true;
            }
        }
        
        return doUseOcclusionCulling;
    }
    
    /**
     * Seeds the search with the sections of the portal's opening instead of the camera's section.
     * Lines of sight enter a seed through the opening inside it, not through one of its faces, so
     * the face-to-face visibility of the seed says nothing about where they can leave it: every face
     * leading away from the camera stays open. The search beyond the seeds is Sodium's own.
     */
    @Inject(method = "init", at = @At("HEAD"), cancellable = true, remap = false)
    private void ip_seedFromAperture(
        RenderSectionVisitor visitor, WriteQueue<RenderSection> queue, Viewport viewport,
        boolean useOcclusionCulling, int frame, CallbackInfo ci
    ) {
        long[] seeds = ip_apertureSeeds;
        if (seeds == null) {
            return;
        }
        ci.cancel();
        outOfWorldRadius = -1;
        
        // mark every seed before enqueuing neighbors, so that no seed is entered through a face
        ObjectArrayList<RenderSection> entered = ip_enteredSeeds;
        for (long pos : seeds) {
            RenderSection section = getRenderSection(SectionPos.x(pos), SectionPos.y(pos), SectionPos.z(pos));
            if (section == null || section.getLastVisibleFrame() == frame || !isWithinFrustum(viewport, section)) {
                continue;
            }
            section.setLastVisibleFrame(frame);
            section.setIncomingDirections(GraphDirectionSet.NONE);
            visitor.visit(section);
            entered.add(section);
        }
        for (RenderSection section : entered) {
            int outgoing = useOcclusionCulling
                ? getOutwardDirections(viewport.getChunkCoord(), section)
                : GraphDirectionSet.ALL;
            visitNeighbors(queue, section, outgoing, frame);
        }
        entered.clear();
    }
    
    // apply start point modification
    @Redirect(
        method = "init",
        at = @At(
            value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;getChunkCoord()Lnet/minecraft/core/SectionPos;",
            remap = true
        ),
        remap = false
    )
    private SectionPos redirectGetChunkCoordInInit(Viewport instance) {
        if (ip_modifiedStartPoint != null) {
            return ip_modifiedStartPoint;
        }
        
        return instance.getChunkCoord();
    }
    
    // apply start point modification
    @Redirect(
        method = "initWithinWorld",
        at = @At(
            value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;getChunkCoord()Lnet/minecraft/core/SectionPos;"
        ),
        remap = false
    )
    private SectionPos redirectGetChunkCoordInInitWithinWorld(Viewport instance) {
        if (ip_modifiedStartPoint != null) {
            return ip_modifiedStartPoint;
        }
        
        return instance.getChunkCoord();
    }
    
    // when iteration start point become a position that's outside of frustum
    // make it tolerant early frustum test failures to avoid wrongly halting iteration
    @Inject(
        method = "isWithinFrustum", at = @At("RETURN"), cancellable = true,
        remap = false
    )
    private static void onIsOutsideFrustum(
        Viewport viewport, RenderSection section,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (ip_tolerantInitialFrustumTestFail) {
            boolean withinFrustum = cir.getReturnValueZ();
            if (withinFrustum) {
                // when found a section that's in frustum, frustum test become normal
                ip_tolerantInitialFrustumTestFail = false;
            }
            cir.setReturnValue(true);
        }
    }
}

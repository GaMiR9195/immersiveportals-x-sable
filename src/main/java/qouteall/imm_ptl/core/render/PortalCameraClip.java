package qouteall.imm_ptl.core.render;

import ipl.sable.mixin.ClipContextAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalUtils;

/**
 * Camera collision through portals, for every camera: vanilla's and those of camera mods.
 *
 * <p>While a camera is set up ({@code Camera.setup}), block clips in the camera entity's level
 * that pass a portal opening continue on the far side: blocks on this side stop the ray before
 * the opening, the rest is traced behind the exit, and the result is returned where it is seen
 * through the portal (beyond the opening, in this level's space). However a camera places
 * itself (distance, shoulder offsets, animated zoom), it lands where it would be behind the
 * portal, and {@link CrossPortalViewRendering} renders the view from there. Render thread only;
 * other threads always clip normally.
 */
public final class PortalCameraClip {
    /**
     * Blocks this close to the opening do not stop a ray: the wall the opening is placed on, which
     * rays through the opening graze around its plane, on either side.
     */
    static final double OPENING_TOLERANCE = 0.2;
    
    private static volatile boolean active;
    @Nullable private static Thread owner;
    @Nullable private static Level level;
    @Nullable private static Entity entity;
    /** A clip of this class is being traced: its own sub-clips are plain. */
    private static boolean tracing;
    /** The portal the last ray continued through during the current or last setup. */
    @Nullable private static Portal lastPortal;
    
    private PortalCameraClip() {}
    
    /** Runs a camera setup with portal-aware block clips. */
    public static void setup(BlockGetter area, @Nullable Entity focused, Runnable setup) {
        if (active || !IPGlobal.enableCrossPortalView || !(area instanceof Level areaLevel)
            || focused == null || focused.level() != areaLevel) {
            setup.run();
            return;
        }
        owner = Thread.currentThread();
        level = areaLevel;
        entity = focused;
        lastPortal = null;
        active = true;
        try {
            setup.run();
        }
        finally {
            active = false;
            owner = null;
            level = null;
            entity = null;
        }
    }
    
    /** The result of {@code context} seen through the portal it passes, or null to clip normally. */
    @Nullable
    public static BlockHitResult clip(BlockGetter getter, ClipContext context) {
        if (!active || tracing || getter != level || Thread.currentThread() != owner) {
            return null;
        }
        Level from = level;
        Entity viewer = entity;
        Vec3 start = context.getFrom();
        Vec3 end = context.getTo();
        var hit = PortalUtils.raytracePortals(from, start, end, true, p -> p.canTeleportEntity(viewer)).orElse(null);
        if (hit == null) {
            return null;
        }
        Portal portal = hit.getFirst();
        Vec3 opening = hit.getSecond().hitPos();
        tracing = true;
        try {
            BlockHitResult near = from.clip(withRay(context, start, opening));
            if (near.getType() != HitResult.Type.MISS
                && near.getLocation().distanceTo(start) < opening.distanceTo(start) - OPENING_TOLERANCE) {
                return near;
            }
            lastPortal = portal;
            Vec3 destFrom = portal.transformPoint(opening);
            Vec3 destTo = portal.transformPoint(end);
            Vec3 ray = destTo.subtract(destFrom);
            double length = ray.length();
            if (length <= OPENING_TOLERANCE) {
                return BlockHitResult.miss(end, faceOf(start, end), BlockPos.containing(end));
            }
            Vec3 farStart = destFrom.add(ray.scale(OPENING_TOLERANCE / length));
            BlockHitResult far = portal.getDestinationWorld().clip(withRay(context, farStart, destTo));
            if (far.getType() == HitResult.Type.MISS) {
                return BlockHitResult.miss(end, faceOf(start, end), BlockPos.containing(end));
            }
            Vec3 location = portal.inverseTransformPoint(far.getLocation());
            Vec3 normal = portal.inverseTransformLocalVecNonScale(Vec3.atLowerCornerOf(far.getDirection().getNormal()));
            Direction face = Direction.getNearest(normal.x, normal.y, normal.z);
            return new BlockHitResult(location, face, BlockPos.containing(location), far.isInside());
        }
        finally {
            tracing = false;
        }
    }
    
    /**
     * The portal the last camera setup looked through, when {@code cameraPos} lies behind it and
     * over its opening (the camera went through it although the straight line from the eyes does
     * not, e.g. with a shoulder offset); null otherwise.
     */
    @Nullable
    public static Portal portalBehind(Vec3 cameraPos) {
        Portal portal = lastPortal;
        if (portal == null || portal.isRemoved() || portal.isInFrontOfPortal(cameraPos)) {
            return null;
        }
        double overOpening = portal.getDistanceToNearestPointInPortal(cameraPos) - portal.getDistanceToPlane(cameraPos);
        return overOpening < 1.0e-3 ? portal : null;
    }
    
    private static ClipContext withRay(ClipContext context, Vec3 from, Vec3 to) {
        ClipContextAccessor modes = (ClipContextAccessor) context;
        return new ClipContext(from, to, modes.ipl$getBlock(), modes.ipl$getFluid(), modes.ipl$getCollisionContext());
    }
    
    private static Direction faceOf(Vec3 from, Vec3 to) {
        Vec3 back = from.subtract(to);
        return Direction.getNearest(back.x, back.y, back.z);
    }
}

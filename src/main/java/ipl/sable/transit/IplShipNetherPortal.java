package ipl.sable.transit;

import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;
import qouteall.imm_ptl.core.portal.PortalExtension;
import qouteall.imm_ptl.core.portal.nether_portal.BlockPortalShape;
import qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity;

import java.util.stream.Collectors;

/**
 * Nether portals living ON physics structures (spec v3 §2.8 extension).
 *
 * <p>Two ways a breakable portal ends up ship-borne, both landing in the same steady
 * state — portal entity in the PARENT dimension at the ship-mapped world pose, anchored
 * via {@link IplShipPortalAnchor}, with {@code blockPortalShape} in PLOT coordinates
 * (the plot bridge makes plot coords readable/writable from any dimension, so IP's
 * frame-integrity sweep and placeholder handling work unchanged):
 * <ul>
 *   <li><b>Assembly capture</b> ({@link #attachOnAssembly}): a glue box swallows an
 *       existing portal's frame. The blocks move world→plot (pure translation at
 *       assembly), the entity stays put; we translate the shape by the same delta and
 *       anchor the cluster to the new ship.</li>
 *   <li><b>Generation on a ship</b> (SableBridge hooks in the IP generation pipeline):
 *       lighting obsidian on a sub-level forms the shape in plot space; the portal
 *       entity is posed through the ship's pose into the parent world and anchored on
 *       spawn.</li>
 * </ul>
 */
public final class IplShipNetherPortal {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-ship-portal");

    private IplShipNetherPortal() {}

    private record Pending(
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim,
        BlockPos anchor,
        BoundingBox3ic bounds
    ) {}

    /** Ship UUID → capture waiting for the rehome. Server thread only. */
    private static final java.util.Map<java.util.UUID, Pending> PENDING = new java.util.HashMap<>();

    public static void clearPending() {
        PENDING.clear();
    }

    /**
     * Post-{@code assembleBlocks}: QUEUE the portal capture. A fresh assembly still
     * lives in the parent level's embedded plot; SableRehomeOps moves it to the
     * hosting dimension — INTO A DIFFERENT PLOT SLOT — a tick later. Capturing now
     * would bake pre-rehome plot coordinates into the anchor, the shape translation
     * and the placeholder writes (~2048 blocks off after the move: vanishing frames,
     * integrity breaks). Capture runs from the rehome-complete hook instead.
     */
    public static void queueAssemblyCapture(
        ServerLevel level, BlockPos anchor, BoundingBox3ic bounds, ServerSubLevel ship
    ) {
        if (ship == null || ship.isRemoved() || bounds == null) return;
        // Nested assembly (glue box on another ship's deck) hands us plot-space
        // coords; ship-on-ship portal capture is out of scope.
        if (Math.abs(anchor.getX()) >= 1_000_000 || Math.abs(anchor.getZ()) >= 1_000_000) return;

        if (ipl.sable.dim.IplDimAgnostic.isHosted(ship)) {
            captureNow(level, anchor, bounds, ship);
            return;
        }
        PENDING.put(ship.getUniqueId(), new Pending(level.dimension(), anchor, bounds));
    }

    /**
     * Rehome finished: the hosted twin owns its FINAL plot slot; plot coordinates
     * computed from here on stay valid. Consumes the pending capture, if any.
     */
    public static void onRehomeComplete(ServerSubLevel hosted, ServerLevel parentLevel) {
        Pending pending = PENDING.remove(hosted.getUniqueId());
        if (pending == null) return;
        if (parentLevel == null || parentLevel.dimension() != pending.dim()) return;
        captureNow(parentLevel, pending.anchor(), pending.bounds(), hosted);
    }

    /**
     * Attach every portal whose origin the assembly swallowed. Shapes translate
     * world→plot by (plotAnchor − anchor) — the assembly transform is a pure
     * translation ({@code angle=0, Rotation.NONE}), and the rehome's slot move
     * preserves block offsets relative to the plot center.
     */
    private static void captureNow(
        ServerLevel level, BlockPos anchor, BoundingBox3ic bounds, ServerSubLevel ship
    ) {
        if (ship.isRemoved()) return;
        BlockPos plotAnchor = ship.getPlot().getCenterBlock();
        Vec3i delta = plotAnchor.subtract(anchor);
        AABB box = bounds.toAABB().inflate(1.0);

        for (Portal portal : level.getEntitiesOfClass(Portal.class, box)) {
            if (portal.isRemoved()) continue;
            if (!box.contains(portal.getOriginPos())) continue;

            if (portal instanceof BreakablePortalEntity breakable
                && breakable.blockPortalShape != null
                && Math.abs(breakable.blockPortalShape.anchor.getX()) < 1_000_000) {
                BlockPortalShape worldShape = breakable.blockPortalShape;
                breakable.blockPortalShape = translate(worldShape, delta);
                reconcilePlaceholders(level, worldShape, breakable.blockPortalShape);
            }

            // One anchor per cluster: the tick driver rectifies flipped/reverse/parallel
            // from the primary; a second independent anchor would fight the rectify.
            if (isClusterAnchored(portal)) continue;

            String result = IplShipPortalAnchor.anchorToShip(portal, ship);
            LOG.info("[IPL-SHIP-PORTAL] assembly captured portal {}: {}",
                portal.getUUID(), result);
        }
    }

    /** Is this portal — or any same-cluster member — already anchored? */
    private static boolean isClusterAnchored(Portal portal) {
        if (IplShipPortalAnchor.isAnchored(portal.getUUID())) return true;
        PortalExtension ext = PortalExtension.get(portal);
        return (ext.flippedPortal != null && IplShipPortalAnchor.isAnchored(ext.flippedPortal.getUUID()))
            || (ext.reversePortal != null && IplShipPortalAnchor.isAnchored(ext.reversePortal.getUUID()))
            || (ext.parallelPortal != null && IplShipPortalAnchor.isAnchored(ext.parallelPortal.getUUID()));
    }

    /**
     * The glue gather may not carry the invisible {@code PortalPlaceholderBlock} area
     * blocks into the plot (gather predicates vary) — the integrity sweep would then
     * see air and break the portal within ~12s. Make the plot side whole and clear
     * anything left behind in the world. Idempotent (runs once per face).
     */
    private static void reconcilePlaceholders(
        ServerLevel parent, BlockPortalShape worldShape, BlockPortalShape plotShape
    ) {
        ServerLevel hosting =
            ipl.sable.dim.SableSubLevelDimension.getSableSubLevelsOrNull(parent.getServer());
        if (hosting == null) return;
        for (BlockPos plotPos : plotShape.area) {
            if (hosting.getBlockState(plotPos).getBlock() != PortalPlaceholderBlock.instance) {
                hosting.setBlockAndUpdate(plotPos,
                    PortalPlaceholderBlock.instance.defaultBlockState()
                        .setValue(PortalPlaceholderBlock.AXIS, plotShape.axis));
            }
        }
        for (BlockPos worldPos : worldShape.area) {
            if (parent.getBlockState(worldPos).getBlock() == PortalPlaceholderBlock.instance) {
                parent.setBlockAndUpdate(worldPos,
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static BlockPortalShape translate(BlockPortalShape shape, Vec3i delta) {
        return new BlockPortalShape(
            shape.area.stream().map(pos -> pos.offset(delta)).collect(Collectors.toSet()),
            shape.axis);
    }
}

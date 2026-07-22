package ipl.sable.transit;

import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.IplSceneOwnership;
import ipl.sable.mixin.IplRapierPipelineAccess;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * ALWAYS-ON lifecycle for portal containment rims (see {@link IplStraddlePortalRim}).
 *
 * <p>Session-scoped rims had a hole: a ship shearing laterally into the aperture never
 * starts a crossing session, so nothing existed to stop it. Containment is a property
 * of the PORTAL, not of any crossing — so rims follow portal entity lifecycle: spawned
 * when a loaded, teleportable portal is seen in a level with a physics scene,
 * re-anchored when the portal moves (portal-wand drags; future ship-mounted portals),
 * removed when the portal despawns or unloads. Both ends of a pair get rims naturally,
 * since IP creates a portal entity at each end.
 *
 * <p>Coincident bi-face duplicates (two entities, same plane, opposite normals) each
 * get a rim; the duplicate is identical static geometry and merely doubles contacts —
 * harmless, and far simpler than replicating the canonical-face selection here.
 */
public final class IplPortalRimManager {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-portal-rim");

    private static final boolean ENABLED = !"false".equals(System.getProperty("ipl.sable.portalRim"));
    /**
     * Frame border width beyond the aperture, blocks. A 1-voxel ring suffices: a
     * straddling ship's cross-section at the plane cannot leave the aperture without
     * its hull passing through the ring, so width beyond one block buys nothing —
     * it just plants a bigger invisible wall in front of bystander ships.
     */
    private static final int OUTER_MARGIN =
        Integer.getInteger("ipl.sable.portalRim.margin", 1);
    /** Portals larger than this per axis get no rim (dimension seams, world wraps). */
    private static final double MAX_APERTURE = 128.0;
    /** Full portal rescan cadence, ticks (move checks run every tick, on known rims). */
    private static final int SCAN_INTERVAL = 10;

    private record Rim(long scene, int[] ids, IplStraddlePortalRim.RimGeometry geometry) {}

    /** Portal UUID → live rim. Server-thread only. */
    private static final Map<UUID, Rim> RIMS = new HashMap<>();

    /**
     * Ship-anchored portals (atlas M6): the rim is aperture containment for
     * TRAVERSING bodies — it must never push its own CARRIER (the hull surrounds
     * the aperture, so rim-vs-carrier contact is constant). Portal UUID → carrier
     * body id; exclusions are (re)applied whenever this portal's rim (re)spawns.
     */
    private static final Map<UUID, Integer> CARRIER_EXCLUSIONS = new HashMap<>();

    private static int tickCounter = 0;

    private IplPortalRimManager() {}

    /** Called once per server tick per level (from the transit controller's tick). */
    public static void tick(ServerLevel level) {
        if (!ENABLED || IplDimAgnostic.isHostingLevel(level)) return;
        RapierPhysicsPipeline pipeline = IplSceneOwnership.pipelineOf(level);
        if (pipeline == null) return;
        long scene = ((IplRapierPipelineAccess) pipeline).ipl$sceneHandle();
        if (scene == 0) return;

        boolean fullScan = (tickCounter++ % SCAN_INTERVAL) == 0;

        // Move/despawn pass over known rims of THIS level's scene, every tick.
        Iterator<Map.Entry<UUID, Rim>> it = RIMS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Rim> entry = it.next();
            Rim rim = entry.getValue();
            if (rim.scene() != scene) continue;

            Entity entity = level.getEntity(entry.getKey());
            if (!(entity instanceof Portal portal) || portal.isRemoved()
                || !portal.isTeleportable()) {
                for (int id : rim.ids()) IplStraddlePortalRim.remove(scene, id);
                LOG.info("[IPL-RIM] portal {} gone — rim removed", entry.getKey());
                it.remove();
                continue;
            }
            IplStraddlePortalRim.RimGeometry current = geometryOf(portal);
            if (!approxEquals(current, rim.geometry())) {
                if (sameHoleShape(current, rim.geometry())) {
                    IplStraddlePortalRim.updateTransforms(scene, rim.ids(), current);
                    entry.setValue(new Rim(scene, rim.ids(), current));
                } else {
                    // Aperture size changed: voxel content is stale — respawn.
                    for (int id : rim.ids()) IplStraddlePortalRim.remove(scene, id);
                    it.remove();
                }
            }
        }

        if (!fullScan) return;

        // Discovery pass: any loaded teleportable portal without a rim gets one.
        // Plain loaded-entity iteration — an infinite-bounds section query would walk
        // the whole section storage; this is a flat pass over loaded entities.
        for (Entity candidate : level.getAllEntities()) {
            if (!(candidate instanceof Portal portal) || !portal.isTeleportable()) continue;
            if (RIMS.containsKey(portal.getUUID())) continue;
            if (portal.getWidth() <= 0 || portal.getHeight() <= 0
                || portal.getWidth() > MAX_APERTURE || portal.getHeight() > MAX_APERTURE) {
                continue;
            }
            IplStraddlePortalRim.RimGeometry geometry = geometryOf(portal);
            int[] ids = IplStraddlePortalRim.spawn(
                scene,
                ((IplRapierPipelineAccess) pipeline).ipl$colliderBakery(),
                geometry, OUTER_MARGIN);
            if (ids.length > 0) {
                RIMS.put(portal.getUUID(), new Rim(scene, ids, geometry));
                Integer carrier = CARRIER_EXCLUSIONS.get(portal.getUUID());
                if (carrier != null) {
                    applyCarrierExclusion(scene, ids, carrier, true);
                }
            }
        }
    }

    public static void clearAll() {
        // Scenes die with their levels on stop; just drop the bookkeeping.
        RIMS.clear();
        CARRIER_EXCLUSIONS.clear();
        tickCounter = 0;
    }

    /**
     * Register (or clear) the carrier of a ship-anchored portal: the rim's bodies
     * stop colliding with the carrier ship. Applied immediately to a live rim and
     * re-applied automatically when the rim respawns (aperture change, portal
     * reload). Body ids are never reused, so stale exclusions are inert.
     */
    public static void setCarrierExclusion(UUID portalId, int carrierBodyId, boolean on) {
        Rim rim = RIMS.get(portalId);
        if (on) {
            CARRIER_EXCLUSIONS.put(portalId, carrierBodyId);
            if (rim != null) {
                applyCarrierExclusion(rim.scene(), rim.ids(), carrierBodyId, true);
            }
        } else {
            // Unwind with the STORED id (callers pass -1 on release paths); the
            // ex-carrier must collide with the rim again.
            Integer old = CARRIER_EXCLUSIONS.remove(portalId);
            if (old != null && rim != null) {
                applyCarrierExclusion(rim.scene(), rim.ids(), old, false);
            }
        }
    }

    private static void applyCarrierExclusion(long scene, int[] rimIds, int carrier, boolean on) {
        if (!ipl.sable.natives.IplRapierNatives.isAvailable()) return;
        for (int id : rimIds) {
            ipl.sable.natives.IplRapierNatives.setBodyPairExclusion(scene, id, carrier, on);
        }
        LOG.info("[IPL-RIM] carrier exclusion {} for {} rim body(ies) vs carrier {}",
            on ? "ON" : "off", rimIds.length, carrier);
    }

    private static IplStraddlePortalRim.RimGeometry geometryOf(Portal portal) {
        return new IplStraddlePortalRim.RimGeometry(
            portal.getOriginPos(), portal.getNormal(),
            portal.getAxisW(), portal.getAxisH(),
            portal.getWidth() * 0.5, portal.getHeight() * 0.5);
    }

    private static boolean approxEquals(
        IplStraddlePortalRim.RimGeometry a, IplStraddlePortalRim.RimGeometry b
    ) {
        return closeEnough(a.planePoint(), b.planePoint())
            && closeEnough(a.normal(), b.normal())
            && closeEnough(a.axisW(), b.axisW())
            && Math.abs(a.halfW() - b.halfW()) < 1.0e-4
            && Math.abs(a.halfH() - b.halfH()) < 1.0e-4;
    }

    private static boolean sameHoleShape(
        IplStraddlePortalRim.RimGeometry a, IplStraddlePortalRim.RimGeometry b
    ) {
        return Math.abs(a.halfW() - b.halfW()) < 1.0e-4
            && Math.abs(a.halfH() - b.halfH()) < 1.0e-4;
    }

    private static boolean closeEnough(Vec3 a, Vec3 b) {
        return a.distanceToSqr(b) < 1.0e-6;
    }
}

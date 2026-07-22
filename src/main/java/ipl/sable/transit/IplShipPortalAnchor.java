package ipl.sable.transit;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalExtension;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Atlas M6 (spec v3 §2.8, "portals on physics structures"): SHIP-ANCHORED PORTALS.
 *
 * <p>An anchored portal's origin end is glued to a sub-level at a ship-local pose.
 * Every server tick, immediately after the fused step publishes fresh physics poses,
 * the portal entity is re-posed from the ship: origin = shipPose(localPos),
 * orientation = shipRot ∘ localOrient. The rotation TRANSFORM is re-derived so the
 * (static) destination end stays fixed: with D₀ = R_t(0) ∘ O(0) locked at anchor
 * time, R_t(now) = D₀ ∘ O(now)⁻¹. {@link PortalExtension#rectifyClusterPortals}
 * then propagates everything to the flipped/reverse/parallel cluster members and
 * syncs clients.
 *
 * <p>Physics follows for free: straddle sessions re-derive their isometry from the
 * portal each tick (M5a) and push it to the image collider; rims re-anchor from the
 * same movement check; the router and entity layers read the refreshed mapping.
 * Within a tick the isometry is frozen — the kinematic-frame fiat (§2.8): traversal
 * impulses do not back-react on the anchor ship.
 *
 * <p>V1 scope: origin end on a ship, destination end static; anchors are runtime
 * state (not persisted across restarts); the anchor ship straddling its OWN portal
 * is physics-safe (engine same-parent filter) but not a supported gameplay loop.
 */
public final class IplShipPortalAnchor {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-ship-portal");

    private record Anchor(
        UUID shipId,
        ResourceKey<Level> portalDim,
        Vector3d localPos,
        DQuaternion localOrient,
        /** D₀ = R_t(0) ∘ O(0): the dest end's orientation lock. */
        DQuaternion destLock
    ) {}

    /** Portal UUID → anchor. Server-thread only. */
    private static final Map<UUID, Anchor> ANCHORS = new HashMap<>();

    private IplShipPortalAnchor() {}

    /** Server stopping/starting: drop all runtime anchors. */
    public static void clearAll() {
        ANCHORS.clear();
    }

    public static boolean isAnchored(UUID portalId) {
        return ANCHORS.containsKey(portalId);
    }

    public static int count() {
        return ANCHORS.size();
    }

    /**
     * Anchor {@code portal} to the sub-level whose bounds contain its origin (or the
     * nearest within 8 blocks). Returns a human-readable result message.
     */
    public static String anchor(Portal portal) {
        if (!(portal.level() instanceof ServerLevel level)) return "server side only";
        ServerSubLevel ship = findShipAt(level, portal.getOriginPos(), ANCHOR_MARGIN);
        if (ship == null) {
            return "no sub-level under the portal origin (stand the portal on the ship)";
        }
        return anchorToShip(portal, ship);
    }

    private static String anchorToShip(Portal portal, ServerSubLevel ship) {
        ServerLevel level = (ServerLevel) portal.level();
        Pose3dc pose = ship.logicalPose();
        Quaterniond shipRot = new Quaterniond(pose.orientation());
        Vector3d localPos = new Vector3d(
            portal.getOriginPos().x - pose.position().x(),
            portal.getOriginPos().y - pose.position().y(),
            portal.getOriginPos().z - pose.position().z());
        new Quaterniond(shipRot).conjugate().transform(localPos);

        DQuaternion shipD = new DQuaternion(shipRot.x, shipRot.y, shipRot.z, shipRot.w);
        DQuaternion o0 = portal.getOrientationRotation();
        DQuaternion localOrient = shipD.getConjugated().hamiltonProduct(o0);
        DQuaternion rt0 = portal.getRotation() == null ? DQuaternion.identity : portal.getRotation();
        DQuaternion destLock = rt0.hamiltonProduct(o0);

        ANCHORS.put(portal.getUUID(), new Anchor(
            ship.getUniqueId(), level.dimension(), localPos, localOrient, destLock));
        LOG.info("[IPL-SHIP-PORTAL] anchored portal {} to ship {} at local ({}, {}, {})",
            portal.getUUID(), ship.getUniqueId(),
            String.format("%.2f", localPos.x), String.format("%.2f", localPos.y),
            String.format("%.2f", localPos.z));
        return "anchored portal to ship " + ship.getUniqueId();
    }

    /** Detach the portal; it stays wherever the ship last carried it. */
    public static String unanchor(Portal portal) {
        return ANCHORS.remove(portal.getUUID()) != null
            ? "unanchored" : "portal was not anchored";
    }

    /**
     * Drive all anchored portals from their ships' fresh physics poses. Called at the
     * end of the fused step (IplFusedStep), when this tick's poses are final.
     */
    /**
     * Auto-anchor (default ON): a portal whose origin sits INSIDE an assembled
     * ship's bounds — e.g. a lit nether portal frame that is part of the hull —
     * anchors itself, no command needed. Strictly-inside only (small inflation),
     * so ground portals NEXT to a parked ship are never grabbed.
     */
    private static final boolean AUTO_ANCHOR =
        !"false".equals(System.getProperty("ipl.sable.shipPortal.autoAnchor"));
    private static final double AUTO_ANCHOR_INFLATE = 1.0;
    private static final int AUTO_SCAN_INTERVAL = 20;
    private static int scanCounter = 0;

    public static void tickAll(MinecraftServer server) {
        if (AUTO_ANCHOR && (scanCounter++ % AUTO_SCAN_INTERVAL) == 0) {
            autoAnchorScan(server);
        }
        if (ANCHORS.isEmpty()) return;

        Iterator<Map.Entry<UUID, Anchor>> it = ANCHORS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Anchor> entry = it.next();
            Anchor a = entry.getValue();

            ServerLevel level = server.getLevel(a.portalDim());
            if (level == null) continue;
            Entity entity = level.getEntity(entry.getKey());
            if (entity == null) continue; // unloaded — keep the anchor, skip this tick
            if (!(entity instanceof Portal portal) || portal.isRemoved()) {
                LOG.info("[IPL-SHIP-PORTAL] portal {} gone — anchor dropped", entry.getKey());
                it.remove();
                continue;
            }

            ServerSubLevel ship = findShip(server, a.shipId());
            if (ship == null || ship.isRemoved()) {
                LOG.info("[IPL-SHIP-PORTAL] ship {} gone — portal {} released",
                    a.shipId(), entry.getKey());
                it.remove();
                continue;
            }

            Pose3dc pose = ship.logicalPose();
            Quaterniond shipRot = new Quaterniond(pose.orientation());
            Vector3d world = new Vector3d(a.localPos());
            shipRot.transform(world);
            Vec3 originNow = new Vec3(
                world.x + pose.position().x(),
                world.y + pose.position().y(),
                world.z + pose.position().z());
            DQuaternion shipD = new DQuaternion(shipRot.x, shipRot.y, shipRot.z, shipRot.w);
            DQuaternion oNow = shipD.hamiltonProduct(a.localOrient());

            // Cheap static-ship skip: pose unchanged within epsilon.
            DQuaternion oCur = portal.getOrientationRotation();
            boolean moved = portal.getOriginPos().distanceToSqr(originNow) > 1.0e-10
                || Math.abs(oCur.getX() * oNow.getX() + oCur.getY() * oNow.getY()
                    + oCur.getZ() * oNow.getZ() + oCur.getW() * oNow.getW()) < 1.0 - 1.0e-10;
            if (!moved) continue;

            DQuaternion rtNow = a.destLock().hamiltonProduct(oNow.getConjugated());

            portal.setOriginPos(originNow);
            portal.setOrientationRotation(oNow);
            portal.setRotation(rtNow);
            portal.reloadAndSyncToClientNextTick();
            PortalExtension.get(portal).rectifyClusterPortals(portal, true);
        }
    }

    private static void autoAnchorScan(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            if (ipl.sable.dim.IplDimAgnostic.isHostingLevel(level)) continue;
            for (Entity candidate : level.getAllEntities()) {
                if (!(candidate instanceof Portal portal) || portal.isRemoved()) continue;
                if (ANCHORS.containsKey(portal.getUUID())) continue;
                if (portal.getWidth() <= 0 || portal.getHeight() <= 0) continue;
                ServerSubLevel ship =
                    findShipAt(level, portal.getOriginPos(), AUTO_ANCHOR_INFLATE);
                if (ship == null) continue;
                LOG.info("[IPL-SHIP-PORTAL] auto-anchoring portal {} inside ship {}",
                    portal.getUUID(), ship.getUniqueId());
                anchorToShip(portal, ship);
            }
        }
    }

    // ------------------------------------------------------------------

    /**
     * Acceptance margin (blocks) between the portal origin and the ship's BOUNDS —
     * measured to the box surface, not the center, so large hulls qualify. Generous
     * by default: a deck-mounted portal floats a rim's width above the hull, and the
     * origin of a wand-made portal can sit a few blocks off the frame.
     */
    private static final double ANCHOR_MARGIN =
        Double.parseDouble(System.getProperty("ipl.sable.shipPortal.anchorMargin", "8.0"));

    /**
     * Find the ship nearest {@code pos} whose EFFECTIVE PARENT is {@code level}.
     * Hosted ships' gameplay state lives in the HOSTING dimension's container (the
     * dim-agnostic architecture), so scanning only the portal's own level finds
     * nothing — the same disease as Sable's nearby-sub-level commands. Enumerate
     * every container and filter by parent instead.
     */
    private static ServerSubLevel findShipAt(ServerLevel level, Vec3 pos, double margin) {
        ServerSubLevel best = null;
        double bestDist = margin * margin;
        for (ServerLevel any : level.getServer().getAllLevels()) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(any);
            if (container == null) continue;
            for (ServerSubLevel sub : container.getAllSubLevels()) {
                if (sub.isRemoved()) continue;
                ServerLevel parent = ipl.sable.dim.IplDimAgnostic.isHosted(sub)
                    ? ipl.sable.dim.IplDimAgnostic.getServerParentLevel(sub)
                    : (sub.getLevel() instanceof ServerLevel sl ? sl : null);
                if (parent != level) continue;
                var bb = sub.boundingBox();
                // Distance from the origin to the closest point of the ship's AABB
                // (zero when inside): a portal hovering just above a large deck is
                // near the SURFACE while being far from the center.
                double dx = Math.max(0.0, Math.max(bb.minX() - pos.x, pos.x - bb.maxX()));
                double dy = Math.max(0.0, Math.max(bb.minY() - pos.y, pos.y - bb.maxY()));
                double dz = Math.max(0.0, Math.max(bb.minZ() - pos.z, pos.z - bb.maxZ()));
                double d = dx * dx + dy * dy + dz * dz;
                if (d == 0.0) return sub;
                if (d < bestDist) {
                    bestDist = d;
                    best = sub;
                }
            }
        }
        return best;
    }

    private static ServerSubLevel findShip(MinecraftServer server, UUID shipId) {
        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) continue;
            for (ServerSubLevel sub : container.getAllSubLevels()) {
                if (sub.getUniqueId().equals(shipId)) return sub;
            }
        }
        return null;
    }
}

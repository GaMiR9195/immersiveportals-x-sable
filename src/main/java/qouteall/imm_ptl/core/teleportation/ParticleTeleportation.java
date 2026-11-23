package qouteall.imm_ptl.core.teleportation;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPMcHelper;
import qouteall.imm_ptl.core.mixin.client.particle.IEParticle;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Particles go through teleportable portals: a particle whose motion in a tick crosses a portal
 * from its front continues beyond the destination with its velocity turned by the portal, in the
 * destination level for a portal into another dimension. Any particle moving by
 * {@link Particle#move} or by its velocity is carried (vanilla and modded); particles that place
 * themselves along a path of their own (portal swirls, enchanting glyphs) are left alone, since
 * their next tick would put them back. Nothing is checked while no portal is near the camera.
 *
 * <p>A particle at an opening is also drawn on the other side of it ({@link #renderAtOpening}):
 * the part of its quad that already passed shows beyond the portal and the rest stays in front,
 * each cut by the portal's own depth or view clipping, so it crosses without popping.
 * Client thread only.
 */
public final class ParticleTeleportation {
    /** Portals farther than this from the camera are ignored; particles there are barely visible. */
    private static final double RANGE = 64.0;
    /** Extra reach of a particle at an opening, for the motion drawn between two ticks. */
    private static final double OPENING_MARGIN = 0.25;
    private static final double COPLANAR = 1.0e-3;

    /**
     * Where a particle at an opening is drawn again: {@code back == false} through {@code portal}
     * into its destination, {@code back == true} (it just came out of {@code portal}) back to its entrance.
     */
    private record Opening(Portal portal, boolean back) {
        ClientLevel targetLevel() {
            return back ? (ClientLevel) portal.getOriginWorld() : ClientWorldLoader.getOptionalWorld(portal.getDestDim());
        }

        Vec3 map(Vec3 pos) {
            return back ? portal.inverseTransformPoint(pos) : portal.transformPoint(pos);
        }

        /** A point of the plane the copy is cut at in the target level. */
        Vec3 planePoint() {
            return back ? portal.getOriginPos() : portal.getDestPos();
        }

        /** Unit direction from that plane towards the side where the copy is visible. */
        Vec3 visibleSide() {
            return back ? portal.getNormal() : portal.getContentDirection();
        }
    }

    private static List<Portal> portals = List.of();
    @Nullable
    private static ClientLevel portalsLevel;
    @Nullable
    private static Particle ticking;
    private static boolean moved;
    /** Particles at an opening, from their level's last particle tick. */
    private static final Map<Particle, Opening> AT_OPENING = new IdentityHashMap<>();

    private ParticleTeleportation() {}

    /** Start of a particle tick of {@code level}: collects the portals its particles may cross. */
    public static void beginTick(@Nullable ClientLevel level) {
        portalsLevel = level;
        AT_OPENING.keySet().removeIf(p -> !p.isAlive() || ((IEParticle) p).portal_getWorld() == level);
        if (level == null) {
            portals = List.of();
            return;
        }
        Vec3 center = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        portals = IPMcHelper.getNearbyPortalList(level, center, RANGE, Portal::isTeleportable);
    }

    /** Runs {@code tick} of {@code particle}, then carries it through the portal its motion crossed. */
    public static void tick(Particle particle, Runnable tick) {
        IEParticle access = (IEParticle) particle;
        if (portals.isEmpty() || access.portal_getWorld() != portalsLevel) {
            tick.run();
            return;
        }
        Vec3 from = particle.getPos();
        Vec3 velocity = velocity(access);
        ticking = particle;
        moved = false;
        try {
            tick.run();
        }
        finally {
            ticking = null;
        }
        if (!particle.isAlive()) return;
        Vec3 to = particle.getPos();
        if (moved || followsVelocity(to.subtract(from), velocity, velocity(access))) {
            Portal portal = crossedPortal(from, to);
            if (portal != null && carry(particle, access, portal)) {
                AT_OPENING.put(particle, new Opening(portal, true));
                return;
            }
        }
        noteOpening(particle, access);
    }

    /** {@code Particle.move} of the particle being ticked. */
    public static void onMove(Particle particle) {
        if (particle == ticking) moved = true;
    }

    /** Whether {@code motion} from the particle's position crosses a portal (block collision is skipped then). */
    public static boolean passesPortal(Particle particle, Vec3 motion) {
        if (portals.isEmpty() || particle != ticking) return false;
        Vec3 from = particle.getPos();
        return crossedPortal(from, from.add(motion)) != null;
    }

    /** Whether {@code particle} is drawn at an opening and must not be culled by its own position. */
    public static boolean isAtOpening(Particle particle) {
        return !AT_OPENING.isEmpty() && AT_OPENING.containsKey(particle);
    }

    /**
     * Draws the copy of a particle at an opening into the level being rendered, when that level is
     * the other side of the opening and the copy's cut is guaranteed there: the camera sees the
     * opening from the side the copy belongs to (the portal's depth hides the rest), or the pass
     * is the view through that very plane (its clipping hides the rest).
     */
    public static void renderAtOpening(Particle particle, VertexConsumer consumer, Camera camera, float partialTick) {
        if (AT_OPENING.isEmpty()) return;
        Opening opening = AT_OPENING.get(particle);
        if (opening == null || !particle.isAlive() || opening.portal().isRemoved()) return;
        ClientLevel current = Minecraft.getInstance().level;
        if (current == null || opening.targetLevel() != current || !cutHolds(opening, camera.getPosition())) return;

        IEParticle access = (IEParticle) particle;
        double halfHeight = particle.getBoundingBox().getYsize() / 2.0;
        Vec3 pos = particle.getPos();
        Vec3 old = new Vec3(access.ip_getXo(), access.ip_getYo(), access.ip_getZo());
        Vec3 mappedPos = mapBottom(opening, pos, halfHeight);
        Vec3 mappedOld = mapBottom(opening, old, halfHeight);
        ClientLevel own = access.portal_getWorld();
        // Light is read where the copy is drawn.
        access.ip_setWorld(current);
        setPositions(access, mappedPos, mappedOld);
        try {
            particle.render(consumer, camera, partialTick);
        }
        finally {
            setPositions(access, pos, old);
            access.ip_setWorld(own);
        }
    }

    private static boolean cutHolds(Opening opening, Vec3 cameraPos) {
        Vec3 point = opening.planePoint();
        Vec3 side = opening.visibleSide();
        if (cameraPos.subtract(point).dot(side) > 0.0) return true;
        if (!PortalRendering.isRendering()) return false;
        Portal viewed = PortalRendering.getRenderingPortal();
        return viewed.getContentDirection().dot(side) > 1.0 - COPLANAR
            && Math.abs(viewed.getDestPos().subtract(point).dot(side)) < COPLANAR;
    }

    /** Records a particle whose quad, or its motion until the next tick, reaches a portal's opening from the front. */
    private static void noteOpening(Particle particle, IEParticle access) {
        AABB box = particle.getBoundingBox();
        Vec3 center = box.getCenter();
        double reach = radius(particle, box) + velocity(access).length() + OPENING_MARGIN;
        AABB around = box.inflate(reach);
        for (Portal portal : portals) {
            if (portal.isRemoved() || !portal.getBoundingBox().intersects(around)) continue;
            double distance = portal.getDistanceToPlane(center);
            if (distance < 0.0 || distance > reach || portal.getDistanceToNearestPointInPortal(center) > reach) continue;
            AT_OPENING.put(particle, new Opening(portal, false));
            return;
        }
    }

    /** Half diagonal of what the particle draws: its quad for quad particles, else its box. */
    private static double radius(Particle particle, AABB box) {
        double boxRadius = 0.5 * Math.sqrt(box.getXsize() * box.getXsize() + box.getYsize() * box.getYsize()
            + box.getZsize() * box.getZsize());
        if (particle instanceof SingleQuadParticle quad) {
            return Math.max(boxRadius, quad.getQuadSize(1.0f) * Math.sqrt(2.0));
        }
        return boxRadius;
    }

    private static Vec3 velocity(IEParticle access) {
        return new Vec3(access.ip_getXd(), access.ip_getYd(), access.ip_getZd());
    }

    /** A particle that set its own position moved along its velocity (before or after the tick changed it). */
    private static boolean followsVelocity(Vec3 displacement, Vec3 before, Vec3 after) {
        double tolerance = 1.0e-4 + 1.0e-2 * displacement.lengthSqr();
        return displacement.distanceToSqr(before) <= tolerance || displacement.distanceToSqr(after) <= tolerance;
    }

    /** The first portal the segment passes from its front, or null. */
    @Nullable
    private static Portal crossedPortal(Vec3 from, Vec3 to) {
        if (from.equals(to)) return null;
        AABB segment = new AABB(from, to).inflate(1.0e-3);
        Portal best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Portal portal : portals) {
            if (portal.isRemoved() || !portal.getBoundingBox().intersects(segment)) continue;
            Vec3 hit = portal.rayTrace(from, to);
            if (hit == null) continue;
            double distance = hit.distanceToSqr(from);
            if (distance < bestDistance) {
                best = portal;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Maps position, previous position and velocity through {@code portal}; false when the particle was dropped. */
    private static boolean carry(Particle particle, IEParticle access, Portal portal) {
        ClientLevel from = access.portal_getWorld();
        ClientLevel to = portal.getDestDim() == from.dimension() ? from : ClientWorldLoader.getOptionalWorld(portal.getDestDim());
        if (to == null) {
            particle.remove();
            return false;
        }
        double halfHeight = particle.getBoundingBox().getYsize() / 2.0;
        Opening through = new Opening(portal, false);
        Vec3 pos = mapBottom(through, particle.getPos(), halfHeight);
        Vec3 old = mapBottom(through, new Vec3(access.ip_getXo(), access.ip_getYo(), access.ip_getZo()), halfHeight);
        Vec3 velocity = portal.transformLocalVec(velocity(access));
        particle.setPos(pos.x, pos.y, pos.z);
        access.ip_setXo(old.x);
        access.ip_setYo(old.y);
        access.ip_setZo(old.z);
        particle.setParticleSpeed(velocity.x, velocity.y, velocity.z);
        if (to != from) access.ip_setWorld(to);
        return true;
    }

    /** A particle's position is the bottom centre of its box; the box centre is what goes through. */
    private static Vec3 mapBottom(Opening opening, Vec3 bottom, double halfHeight) {
        return opening.map(bottom.add(0.0, halfHeight, 0.0)).subtract(0.0, halfHeight, 0.0);
    }

    /** Sets the current and previous position without touching the box (restored right after drawing). */
    private static void setPositions(IEParticle access, Vec3 pos, Vec3 old) {
        access.ip_setX(pos.x);
        access.ip_setY(pos.y);
        access.ip_setZ(pos.z);
        access.ip_setXo(old.x);
        access.ip_setYo(old.y);
        access.ip_setZo(old.z);
    }
}

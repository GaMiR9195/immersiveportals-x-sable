package qouteall.imm_ptl.core.teleportation;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPMcHelper;
import qouteall.imm_ptl.core.mixin.client.particle.IEParticle;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.q_misc_util.my_util.Plane;

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
 * <p>A particle reaching across an opening is drawn on both sides of it: its own level shows
 * the part on its side of the plane, the level beyond shows the part that passed, each cut exactly
 * by the portal plane ({@link ClippedQuadConsumer}), and in a portal view also by the plane of
 * that view's exit. It crosses without popping and without half of it missing, the same in every
 * view. This holds for every particle that draws quads into the particle buffer, vanilla or modded;
 * particles with a renderer of their own ({@code ParticleRenderType.CUSTOM}) are not cut. Client
 * thread only.
 */
public final class ParticleTeleportation {
    /** Portals farther than this from the camera are ignored; particles there are barely visible. */
    private static final double RANGE = 64.0;
    /** Extra reach of a particle at an opening, for the motion drawn between two ticks. */
    private static final double OPENING_MARGIN = 0.25;
    /** Least drawn radius assumed for a particle whose quad size is unknown (modded quads often outgrow their box). */
    private static final double MIN_UNKNOWN_RADIUS = 1.0;

    /** A particle across {@code portal}: in front of its entrance, or ({@code back}) just out of its exit. */
    private record Opening(Portal portal, boolean back) {
        /** Level of the other side, or null when it is not loaded. */
        @Nullable
        ClientLevel otherLevel() {
            return back ? (ClientLevel) portal.getOriginWorld() : ClientWorldLoader.getOptionalWorld(portal.getDestDim());
        }

        /** A point of the particle's side mapped to the other side. */
        Vec3 toOther(Vec3 pos) {
            return back ? portal.inverseTransformPoint(pos) : portal.transformPoint(pos);
        }

        /** A point of the plane on the particle's own side. */
        Vec3 ownPoint() {
            return back ? portal.getDestPos() : portal.getOriginPos();
        }

        /** Direction from that plane to the part the particle's own level shows. */
        Vec3 ownSide() {
            return back ? portal.getContentDirection() : portal.getNormal();
        }

        Vec3 otherPoint() {
            return back ? portal.getOriginPos() : portal.getDestPos();
        }

        Vec3 otherSide() {
            return back ? portal.getNormal() : portal.getContentDirection();
        }
    }

    private static List<Portal> portals = List.of();
    @Nullable
    private static ClientLevel portalsLevel;
    @Nullable
    private static Particle ticking;
    private static boolean moved;
    /** Particles across an opening, as of their last tick. */
    private static final Map<Particle, Opening> AT_OPENING = new IdentityHashMap<>();
    /**
     * Particles carried into another level, by the client tick of the carry: that level may tick
     * its particles later in the same client tick, which must not move them a second time.
     */
    private static final Map<Particle, Integer> CARRIED = new IdentityHashMap<>();
    /** Cut at the plane of the opening a particle is across. */
    private static final ClippedQuadConsumer OPENING_CUT = new ClippedQuadConsumer();
    /** Cut at the plane of the exit of the portal view being rendered. */
    private static final ClippedQuadConsumer VIEW_CUT = new ClippedQuadConsumer();
    /** Marker of a plane a quad lies wholly behind. */
    private static final Plane BEHIND = new Plane(Vec3.ZERO, new Vec3(0.0, 1.0, 0.0));

    private ParticleTeleportation() {}

    /** Start of a particle tick of {@code level}: collects the portals its particles may cross. */
    public static void beginTick(@Nullable ClientLevel level) {
        portalsLevel = level;
        int now = clientTick();
        AT_OPENING.keySet().removeIf(p -> !p.isAlive());
        CARRIED.values().removeIf(tick -> tick != now);
        if (level == null) {
            portals = List.of();
            return;
        }
        Vec3 center = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        portals = IPMcHelper.getNearbyPortalList(level, center, RANGE, Portal::isTeleportable);
    }

    /** Runs {@code tick} of {@code particle}, then carries it through the portal its motion crossed. */
    public static void tick(Particle particle, Runnable tick) {
        if (!CARRIED.isEmpty() && CARRIED.containsKey(particle)) return;
        IEParticle access = (IEParticle) particle;
        Opening previous = AT_OPENING.remove(particle);
        if (portals.isEmpty() || access.portal_getWorld() != portalsLevel) {
            tick.run();
            keepLeaving(particle, previous);
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
                if (access.portal_getWorld() != portalsLevel) CARRIED.put(particle, clientTick());
                return;
            }
        }
        if (!keepLeaving(particle, previous)) noteEntrance(particle, access);
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

    /** Whether {@code particle} is drawn across an opening and must not be culled by its own position. */
    public static boolean isAtOpening(Particle particle) {
        return !AT_OPENING.isEmpty() && AT_OPENING.containsKey(particle);
    }

    /**
     * Draws a particle into the level being rendered and returns false (the engine draws nothing
     * itself). Its own level shows the part on its
     * side of an opening it is across, the level beyond the opening shows the part that passed. In
     * a portal view each part is also cut at the plane of that view's exit, so a particle partly out
     * of an exit is drawn up to the plane instead of vanishing until it is half a block away.
     */
    public static boolean render(Particle particle, VertexConsumer consumer, Camera camera, float partialTick) {
        IEParticle access = (IEParticle) particle;
        ClientLevel current = Minecraft.getInstance().level;
        Opening opening = AT_OPENING.isEmpty() ? null : AT_OPENING.get(particle);
        if (opening != null && opening.portal().isRemoved()) opening = null;
        if (access.portal_getWorld() == current) {
            draw(particle, access, consumer, camera, partialTick,
                opening == null ? null : new Plane(opening.ownPoint(), opening.ownSide()));
        }
        if (opening != null && current != null && opening.otherLevel() == current) {
            drawOtherSide(particle, access, opening, current, consumer, camera, partialTick);
        }
        return false;
    }

    /** The part of the particle that passed, at its place beyond the opening (light read there). */
    private static void drawOtherSide(Particle particle, IEParticle access, Opening opening, ClientLevel current,
                                      VertexConsumer consumer, Camera camera, float partialTick) {
        double halfHeight = particle.getBoundingBox().getYsize() / 2.0;
        Vec3 pos = particle.getPos();
        Vec3 old = new Vec3(access.ip_getXo(), access.ip_getYo(), access.ip_getZo());
        ClientLevel own = access.portal_getWorld();
        access.ip_setWorld(current);
        setPositions(access, mapBottom(opening, pos, halfHeight), mapBottom(opening, old, halfHeight));
        try {
            draw(particle, access, consumer, camera, partialTick, new Plane(opening.otherPoint(), opening.otherSide()));
        }
        finally {
            setPositions(access, pos, old);
            access.ip_setWorld(own);
        }
    }

    /**
     * Draws the particle at its current positions, keeping only what lies on the positive side of
     * {@code cut} (null: no cut) and, in a portal view, beyond the plane of that view's exit.
     * A plane the quad does not reach costs nothing; a quad wholly behind one is not drawn.
     */
    private static void draw(Particle particle, IEParticle access, VertexConsumer consumer, Camera camera,
                             float partialTick, @Nullable Plane cut) {
        Vec3 at = new Vec3(Mth.lerp(partialTick, access.ip_getXo(), particle.getPos().x),
            Mth.lerp(partialTick, access.ip_getYo(), particle.getPos().y),
            Mth.lerp(partialTick, access.ip_getZo(), particle.getPos().z));
        double radius = drawnRadius(particle, partialTick);
        Plane view = PortalRendering.isRendering() ? PortalRendering.getRenderingPortal().getInnerClipping() : null;
        view = reached(view, at, radius);
        cut = reached(cut, at, radius);
        if (view == BEHIND || cut == BEHIND) return;
        Vec3 cam = camera.getPosition();
        VertexConsumer target = consumer;
        if (view != null) target = VIEW_CUT.begin(target, view.pos().subtract(cam), view.normal());
        if (cut != null) target = OPENING_CUT.begin(target, cut.pos().subtract(cam), cut.normal());
        try {
            particle.render(target, camera, partialTick);
        }
        finally {
            if (cut != null) OPENING_CUT.end();
            if (view != null) VIEW_CUT.end();
        }
    }

    /** {@code plane} when the quad around {@code at} reaches across it, null when wholly in front, {@link #BEHIND} when wholly behind. */
    @Nullable
    private static Plane reached(@Nullable Plane plane, Vec3 at, double radius) {
        if (plane == null) return null;
        double distance = at.subtract(plane.pos()).dot(plane.normal());
        return distance > radius ? null : distance < -radius ? BEHIND : plane;
    }

    /** Records a particle whose quad, or its motion until the next tick, reaches a portal's opening from the front. */
    private static void noteEntrance(Particle particle, IEParticle access) {
        AABB box = particle.getBoundingBox();
        Vec3 center = box.getCenter();
        double reach = reach(particle, access, box);
        AABB around = box.inflate(reach);
        for (Portal portal : portals) {
            if (portal.isRemoved() || !portal.getBoundingBox().intersects(around)) continue;
            double distance = portal.getDistanceToPlane(center);
            if (distance < 0.0 || distance > reach || portal.getDistanceToNearestPointInPortal(center) > reach) continue;
            AT_OPENING.put(particle, new Opening(portal, false));
            return;
        }
    }

    /**
     * Keeps a particle that came out of {@code previous}'s exit across it while its quad still
     * reaches back through the opening. Also for a one-way portal, which has no entrance on that side.
     */
    private static boolean keepLeaving(Particle particle, @Nullable Opening previous) {
        if (previous == null || !previous.back() || previous.portal().isRemoved() || !particle.isAlive()) return false;
        Portal portal = previous.portal();
        AABB box = particle.getBoundingBox();
        double reach = reach(particle, (IEParticle) particle, box) / portal.getScale();
        Vec3 entrance = portal.inverseTransformPoint(box.getCenter());
        double distance = portal.getDistanceToPlane(entrance);
        if (distance > 0.0 || -distance > reach || portal.getDistanceToNearestPointInPortal(entrance) > reach) return false;
        AT_OPENING.put(particle, previous);
        return true;
    }

    /** How far from its box centre the particle draws, plus its motion until the next tick. */
    private static double reach(Particle particle, IEParticle access, AABB box) {
        double boxRadius = 0.5 * Math.sqrt(box.getXsize() * box.getXsize() + box.getYsize() * box.getYsize()
            + box.getZsize() * box.getZsize());
        return Math.max(boxRadius, drawnRadius(particle, 1.0f)) + velocity(access).length() + OPENING_MARGIN;
    }

    /** How far from its position the particle's quads reach; generous when the particle does not tell. */
    private static double drawnRadius(Particle particle, float partialTick) {
        if (particle instanceof SingleQuadParticle quad) return quad.getQuadSize(partialTick) * Math.sqrt(2.0);
        AABB box = particle.getBoundingBox();
        return Math.max(MIN_UNKNOWN_RADIUS, Math.max(box.getXsize(), Math.max(box.getYsize(), box.getZsize())) * Math.sqrt(3.0));
    }

    private static Vec3 velocity(IEParticle access) {
        return new Vec3(access.ip_getXd(), access.ip_getYd(), access.ip_getZd());
    }

    private static int clientTick() {
        return Minecraft.getInstance().gui.getGuiTicks();
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
        return opening.toOther(bottom.add(0.0, halfHeight, 0.0)).subtract(0.0, halfHeight, 0.0);
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

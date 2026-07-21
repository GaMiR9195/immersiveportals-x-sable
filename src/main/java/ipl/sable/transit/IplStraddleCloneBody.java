package ipl.sable.transit;

import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.physics.chunk.VoxelNeighborhoodState;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.physics.impl.rapier.collider.RapierVoxelColliderData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.util.LevelAccelerator;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.IplSceneOwnership;
import ipl.sable.mixin.IplRapierPipelineAccess;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 2-3 of the per-scene portal physics (spec §2.3-§2.4): the straddle CLONE BODY.
 *
 * <p>While a hosted ship straddles a portal, a second native Rapier body — same voxel
 * colliders, mirrored mass — lives in the DESTINATION dimension's scene at the
 * portal-mapped pose. It is dynamic and velocity-SERVOED to the real body each substep
 * (a naive kinematic clone would present infinite mass to far-side contacts), and the
 * contact impulses it accumulates are read off the destination scene's contact-event
 * buffer and applied to the real body through the (inverse) portal isometry. The through-
 * part therefore collides with real dest terrain and real dest-side ships, with one-step
 * coupling lag — replacing the phantom terrain clone ({@link IplStraddleTerrainClone}).
 *
 * <p>The clone is PURE NATIVE STATE: a fresh body id from {@link Rapier3D#nextBodyID()},
 * never registered in any Java pipeline's {@code activeSubLevels} — invisible to the
 * ownership guard, Sable bookkeeping, and gameplay. Collider handles are valid across
 * scenes (the native voxel-collider registry is global), so the bake reuses the parent
 * pipeline's bakery and accelerator (under the hosting read override, where plot content
 * physically lives).
 *
 * <p>Scope: any scale-1 isometry pair (rotation fully supported — the clone's pose and
 * the servo's correction mapping carry it; the section feed is plot-local). Legacy note:
 * pairs. Known limitations of this first cut: clone colliders are not clipped at the
 * portal plane (the not-yet-through hull part can touch dest-side geometry behind the
 * aperture — spec §2.5, phase 4 native work), feedback runs on pair-level force scalars
 * (no tangent/friction component until the readout fidelity patch), and block changes
 * mid-straddle do not update the clone's colliders until the next straddle session.
 *
 * <p>Kill switch: {@code -Dipl.sable.cloneBodies=false} reverts to the phantom terrain
 * clone.
 */
public final class IplStraddleCloneBody {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-straddle-clone");

    private static final boolean ENABLED =
        !"false".equalsIgnoreCase(System.getProperty("ipl.sable.cloneBodies", "true"));

    /**
     * Same-dimension portal pairs put the real body and its clone in ONE scene. That is
     * only safe with the custom natives' dedicated clone storage — without it the clone's
     * plot-coordinate sections corrupt the shared scene map (and despawn deletes the real
     * body's sections). Gate is ANDed with a live-natives check at session start.
     */
    private static final boolean SAME_DIM_ENABLED =
        !"false".equalsIgnoreCase(System.getProperty("ipl.sable.sameDimStraddle", "true"));

    /**
     * Allow multiple simultaneous clone sessions for ONE ship (double straddle across two
     * portals). Default OFF: each session runs its own PBC servo against the same real
     * body, and two servos fight — the first in-game double straddle (two same-dim portals
     * facing each other) oscillated and died in native code. One session per ship until a
     * multi-session coupling is actually designed; the deepest/first straddle wins.
     */
    private static final boolean MULTI_STRADDLE =
        Boolean.parseBoolean(System.getProperty("ipl.sable.multiStraddle", "false"));

    // ------------------------------------------------------------------
    // Position-based coupling (replaces impulse feedback): the clone is PINNED to the
    // portal-mapped pose before each dest-scene substep; the solver then resolves its
    // contacts — positionally and with correct torques, because the contact points act on
    // the clone's real collider — and whatever pose/velocity correction the solver applied
    // to the clone is copied onto the real body after the step. Per-substep transfer caps
    // keep deep-penetration recovery from teleporting the ship.
    // ------------------------------------------------------------------

    /** Ignore tiny solver position corrections that otherwise cause visible pose churn. */
    private static final double TRANSFER_DEADBAND =
        Double.parseDouble(System.getProperty("ipl.sable.clone.transferDeadband", "0.01"));
    /** Max position correction copied to the real body per substep (blocks). */
    private static final double MAX_POS_TRANSFER =
        Double.parseDouble(System.getProperty("ipl.sable.clone.maxPosTransfer", "0.5"));
    /** Max rotation correction copied per substep (radians). */
    private static final double MAX_ANG_TRANSFER =
        Double.parseDouble(System.getProperty("ipl.sable.clone.maxAngTransfer", "0.15"));
    /** Max velocity correction copied per substep (m/s). */
    private static final double MAX_VEL_TRANSFER =
        Double.parseDouble(System.getProperty("ipl.sable.clone.maxVelTransfer", "3.0"));
    /**
     * Floor of the dest solver's velocity authority: velocity corrections are scaled by
     * {@code minAuthority + (1 - minAuthority) * crossingFraction}, so a barely-poked-through
     * ship gets a gentle nudge instead of a full-strength yank while a mostly-through ship
     * gets (near) full dest authority. Position/rotation transfers are never scaled.
     */
    private static final double MIN_AUTHORITY =
        Double.parseDouble(System.getProperty("ipl.sable.clone.minAuthority", "0.3"));
    /**
     * Wobble damp: once the ship is MAJORITY-through, the fraction of the real body's
     * velocity opposing the transferred correction that is bled off per substep (ramps
     * from 0 at half-crossed to this value at fully-crossed). Kills the source solver's
     * re-fight of the dest correction instead of letting the two solvers alternate.
     */
    private static final double STRADDLE_DAMP =
        Double.parseDouble(System.getProperty("ipl.sable.clone.straddleDamp", "0.5"));
    /**
     * Rock damp: while a ROTATION correction is actively firing, the fraction of the real
     * body's angular velocity along the correction axis removed per substep. The rocking
     * mode exchanges energy through the orientation-teleport channel, which the linear
     * bleed never touches — and unlike the bleed it must damp BOTH signs, because a rock
     * alternates direction every half-cycle. Crossing-independent: two-sided contact
     * fights the orientation at any fraction.
     */
    private static final double ANGULAR_DAMP =
        Double.parseDouble(System.getProperty("ipl.sable.clone.angularDamp", "0.3"));

    private static final Map<StraddleKey, Session> SESSIONS = new HashMap<>();

    private static boolean loggedSkip = false;
    private static boolean loggedSameDimSkip = false;
    private static long lastFeedbackLogMs = 0;
    private static long lastServoLogMs = 0;
    private static long lastTeeLogMs = 0;
    private static long lastClipStatLogMs = 0;

    private IplStraddleCloneBody() {}

    private static final class Session {
        final ServerSubLevel sub;
        final Portal portal;
        final ServerLevel parent;
        final ServerLevel dest;
        /** Sable 2.0: scenes are native long handles held by each level's pipeline. */
        final long parentScene;
        final long destScene;
        final int realId;
        final int cloneId;
        /** Full portal isometry source→dest (rotation-capable; scale gated at 1). */
        final IplStraddlePoseMap.StraddleMapping mapping;
        /** Unit crossing direction at the source plane (for the crossing fraction). */
        final Vec3 sourceToDestN;
        /**
         * Fraction of the ship's bounds past the portal plane (0 = all source-side,
         * 1 = all through). Refreshed each preStep; drives servo authority weighting.
         */
        double destFraction = 0.5;
        /** The REAL body's aperture clip region for this portal (14 doubles), if natives allow. */
        double[] realClipRegion;
        /**
         * Clone sections live in the clone's private native chunk_map (freed with the body on
         * removeSubLevel). False only without the custom natives — then sections sit in the
         * shared scene map and despawn must remove them one by one (legacy behavior, unsafe
         * for same-dimension portals but better than leaking stale collision).
         */
        boolean dedicatedChunks;
        final LongArrayList fedSections = new LongArrayList();
        final double[] poseBuf = new double[7];
        final double[] realLin = new double[3];
        final double[] realAng = new double[3];
        final double[] cloneLin = new double[3];
        final double[] cloneAng = new double[3];
        /** Pre-step pin state, for measuring the solver's post-step correction. */
        final double[] targetPose = new double[7];
        final double[] pinnedLin = new double[3];
        final double[] pinnedAng = new double[3];
        boolean pinned = false;
        /** Dest dimension gravity (m/s²), analytically removed from the velocity delta. */
        org.joml.Vector3d destGravity = new org.joml.Vector3d(0, -9.8, 0);

        Session(ServerSubLevel sub, Portal portal, ServerLevel parent, ServerLevel dest,
                IplStraddlePoseMap.StraddleMapping mapping, Vec3 sourceToDest,
                long parentScene, long destScene) {
            this.sub = sub;
            this.portal = portal;
            this.parent = parent;
            this.dest = dest;
            this.mapping = mapping;
            this.sourceToDestN = sourceToDest.normalize();
            this.parentScene = parentScene;
            this.destScene = destScene;
            this.realId = Rapier3D.getID(sub);
            this.cloneId = Rapier3D.nextBodyID();
        }
    }

    public static boolean isEnabled() {
        return ENABLED && IplSceneOwnership.isEnabled();
    }

    /** Called each tick a hosted ship is STRADDLING (from the transit controller). */
    public static void onStraddleTick(ServerSubLevel hosted, Portal portal, Vec3 sourceToDest) {
        if (!isEnabled() || !IplDimAgnostic.isHosted(hosted)) return;

        ServerLevel parent = IplDimAgnostic.getServerParentLevel(hosted);
        if (parent == null) return;
        ServerLevel dest = parent.getServer().getLevel(portal.getDestDim());
        if (dest == null || IplDimAgnostic.isHostingLevel(dest)) return;

        // Same-dimension pair: real body and clone share one scene, which requires the
        // dedicated clone storage in the custom natives (see SAME_DIM_ENABLED doc).
        if (dest == parent
            && (!SAME_DIM_ENABLED || !ipl.sable.natives.IplRapierNatives.isAvailable())) {
            if (!loggedSameDimSkip) {
                loggedSameDimSkip = true;
                LOG.info("[IPL-CLONE] same-dimension portal {} skipped ({}) — logged once",
                    portal.getUUID(),
                    SAME_DIM_ENABLED ? "custom natives unavailable"
                                     : "sameDimStraddle disabled");
            }
            return;
        }

        // Scale-1 gate only (spec §3: sub-level bodies traverse isometries). Rotation is
        // fully supported: the clone's pose carries it, the section feed is plot-local,
        // and the servo maps corrections through the portal isometry.
        if (Math.abs(portal.getScaling() - 1.0) > 1e-9) {
            if (!loggedSkip) {
                loggedSkip = true;
                LOG.info("[IPL-CLONE] portal {} has scale != 1; clone bodies support "
                    + "isometry pairs only — skipping (logged once)", portal.getUUID());
            }
            return;
        }
        IplStraddlePoseMap.StraddleMapping mapping = IplStraddlePoseMap.StraddleMapping.of(portal);

        // The real body must actually be in the parent scene — raw native reads against a
        // scene that doesn't hold the body are the hang/abort class the ownership guard
        // exists to prevent. (Body lands in the parent scene via phase 1's routing; a
        // boot-fallback body still in the hosting scene gets reconciled within a tick.)
        if (IplSceneOwnership.getBodyHome(hosted) != parent) return;

        StraddleKey key = new StraddleKey(hosted.getUniqueId(), portal.getUUID());
        if (SESSIONS.containsKey(key)) return;

        // One clone session per ship (see MULTI_STRADDLE): a second portal's straddle
        // waits until the active session clears.
        if (!MULTI_STRADDLE) {
            for (Session t : SESSIONS.values()) {
                if (t.sub == hosted) return;
            }
        }

        RapierPhysicsPipeline parentPipeline = IplSceneOwnership.pipelineOf(parent);
        RapierPhysicsPipeline destPipeline = IplSceneOwnership.pipelineOf(dest);
        if (parentPipeline == null || destPipeline == null) return;
        long parentScene = ((IplRapierPipelineAccess) parentPipeline).ipl$sceneHandle();
        long destScene = ((IplRapierPipelineAccess) destPipeline).ipl$sceneHandle();

        Session session = new Session(
            hosted, portal, parent, dest, mapping, sourceToDest, parentScene, destScene);
        try {
            session.destGravity.set(
                dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData
                    .getGravity(dest));
        } catch (Throwable t) {
            // keep the (0, -9.8, 0) default
        }
        if (!spawnClone(session)) return;
        SESSIONS.put(key, session);

        // The clone must never contact its own real body or sibling clones of the same
        // ship: through a same-dimension portal they share ONE scene and would
        // phantom-collide (clip regions only cover the aperture area). Registered in
        // every scene that could hold both ids — inert where one id is absent.
        if (ipl.sable.natives.IplRapierNatives.isAvailable()) {
            ipl.sable.natives.IplRapierNatives.setBodyPairExclusion(
                session.destScene, session.realId, session.cloneId, true);
            for (Session t : SESSIONS.values()) {
                if (t != session && t.sub == session.sub) {
                    ipl.sable.natives.IplRapierNatives.setBodyPairExclusion(
                        session.destScene, session.cloneId, t.cloneId, true);
                    ipl.sable.natives.IplRapierNatives.setBodyPairExclusion(
                        t.destScene, session.cloneId, t.cloneId, true);
                }
            }
        }

        // Aperture contact clipping (spec §2.5), when the IPSable natives are live:
        //  - REAL body: contacts past the portal plane inside the aperture are dropped —
        //    the through-part stops colliding with SOURCE-side terrain and ships.
        //  - CLONE body: the complementary half — contacts BEFORE the (offset-mapped)
        //    plane are dropped, so only the through-part is physically present dest-side.
        if (ipl.sable.natives.IplRapierNatives.isAvailable()) {
            Vec3 origin = portal.getOriginPos();

            // Real body: source plane, source aperture axes.
            session.realClipRegion = clipRegion(
                origin, sourceToDest, portal.getAxisW(), portal.getAxisH(),
                portal.getWidth() * 0.5, portal.getHeight() * 0.5);
            applyRealClipRegions(hosted, session.parentScene, session.realId);

            // Clone body: the DEST aperture — plane point, normal, and axes all mapped
            // through the portal isometry (the native clip is an arbitrary-orientation OBB).
            double[] cloneRegion = clipRegion(
                mapping.mapPoint(origin),
                mapping.mapVec(sourceToDest).scale(-1.0),
                mapping.mapVec(portal.getAxisW()),
                mapping.mapVec(portal.getAxisH()),
                portal.getWidth() * 0.5, portal.getHeight() * 0.5);
            ipl.sable.natives.IplRapierNatives.setClipRegions(
                session.destScene, session.cloneId, cloneRegion);
        }

        // Portal containment rims are ALWAYS-ON per portal entity (IplPortalRimManager)
        // — session-scoped rims had a hole: a ship shearing in laterally never starts a
        // session, so nothing existed yet to stop it.

        LOG.info("[IPL-CLONE] start uuid={} portal={} dest={} rotated={} cloneId={} destScene={} clipped={}",
            hosted.getUniqueId(), portal.getUUID(), portal.getDestPos(),
            !mapping.isIdentityRotation(), session.cloneId, session.destScene,
            session.realClipRegion != null);
    }

    private static double[] clipRegion(
        Vec3 point, Vec3 normal, Vec3 axisW, Vec3 axisH, double halfW, double halfH
    ) {
        return new double[]{
            point.x, point.y, point.z,
            normal.x, normal.y, normal.z,
            axisW.x, axisW.y, axisW.z, halfW,
            axisH.x, axisH.y, axisH.z, halfH
        };
    }

    /**
     * (Re)apply the union of all active sessions' clip regions to a ship's REAL body —
     * a ship can straddle several portals at once, and regions replace wholesale.
     */
    private static void applyRealClipRegions(ServerSubLevel ship, long parentScene, int realId) {
        java.util.ArrayList<double[]> regions = new java.util.ArrayList<>(2);
        for (Session s : SESSIONS.values()) {
            if (s.sub == ship && s.realClipRegion != null) regions.add(s.realClipRegion);
        }
        double[] flat = new double[regions.size() * 14];
        for (int i = 0; i < regions.size(); i++) {
            System.arraycopy(regions.get(i), 0, flat, i * 14, 14);
        }
        ipl.sable.natives.IplRapierNatives.setClipRegions(parentScene, realId, flat);
        // Diagnostic for the source-side wall bug: show exactly what half-space the real
        // body is being clipped against (a reversed locked normal clips the wrong half).
        for (double[] r : regions) {
            LOG.info("[IPL-CLONE-CLIP] realId={} plane=({},{},{}) normal=({},{},{}) halfW={} halfH={}",
                realId,
                String.format("%.1f", r[0]), String.format("%.1f", r[1]), String.format("%.1f", r[2]),
                String.format("%.2f", r[3]), String.format("%.2f", r[4]), String.format("%.2f", r[5]),
                String.format("%.1f", r[9]), String.format("%.1f", r[13]));
        }
    }

    /** Straddle ended (backed out, flipped, or left the zone): despawn the clone. */
    public static void clear(StraddleKey key, String reason) {
        Session session = SESSIONS.remove(key);
        if (session == null) return;
        despawn(session);
        // Drop this session's pair exclusions (ids are never reused, so stale entries
        // would only be inert bloat — but keep the set tight).
        if (ipl.sable.natives.IplRapierNatives.isAvailable()) {
            ipl.sable.natives.IplRapierNatives.setBodyPairExclusion(
                session.destScene, session.realId, session.cloneId, false);
            for (Session t : SESSIONS.values()) {
                if (t.sub == session.sub) {
                    ipl.sable.natives.IplRapierNatives.setBodyPairExclusion(
                        session.destScene, session.cloneId, t.cloneId, false);
                    ipl.sable.natives.IplRapierNatives.setBodyPairExclusion(
                        t.destScene, session.cloneId, t.cloneId, false);
                }
            }
        }
        // Recompute the real body's clip regions from whatever sessions remain (usually
        // none → cleared): the through-part becomes fully source-solid again.
        if (session.realClipRegion != null
            && ipl.sable.natives.IplRapierNatives.isAvailable()
            && !session.sub.isRemoved()) {
            applyRealClipRegions(session.sub, session.parentScene, session.realId);
        }
        LOG.info("[IPL-CLONE] end uuid={} cloneId={} reason={}",
            session.sub.getUniqueId(), session.cloneId, reason);
    }

    /** Server stopping: drop all state without native despawns (scenes die with the JVM). */
    public static void clearAll() {
        SESSIONS.clear();
        loggedSkip = false;
        loggedSameDimSkip = false;
    }

    /** Whether any clone session is active for this ship (server-side straddle truth). */
    public static boolean hasSession(UUID shipUuid) {
        for (Session s : SESSIONS.values()) {
            if (s.sub.getUniqueId().equals(shipUuid)) return true;
        }
        return false;
    }

    /**
     * The active clone session's portal isometry mapping {@code sub}'s source frame into
     * {@code destLevel}, or null. Feeds the frame mapping for entity collision /
     * interaction on the through-part.
     */
    public static IplStraddlePoseMap.StraddleMapping getMappingInto(
        dev.ryanhcode.sable.sublevel.SubLevel sub, net.minecraft.world.level.Level destLevel
    ) {
        for (Session s : SESSIONS.values()) {
            if (s.dest == destLevel && s.sub.getUniqueId().equals(sub.getUniqueId())) {
                return s.mapping;
            }
        }
        return null;
    }

    /** Portal of the active session mapping this ship INTO {@code level} (dest side). */
    public static qouteall.imm_ptl.core.portal.Portal getSessionPortalInto(
        dev.ryanhcode.sable.sublevel.SubLevel sub, net.minecraft.world.level.Level level
    ) {
        for (Session s : SESSIONS.values()) {
            if (s.dest == level && s.sub.getUniqueId().equals(sub.getUniqueId())) {
                return s.portal;
            }
        }
        return null;
    }

    /** Portal of the active session this ship is exiting FROM {@code level} (source side). */
    public static qouteall.imm_ptl.core.portal.Portal getSessionPortalFrom(
        dev.ryanhcode.sable.sublevel.SubLevel sub, net.minecraft.world.level.Level level
    ) {
        for (Session s : SESSIONS.values()) {
            if (s.parent == level && s.sub.getUniqueId().equals(sub.getUniqueId())) {
                return s.portal;
            }
        }
        return null;
    }

    /** Legacy BlockPos view of {@link #getMappingInto} (translation-only sessions). */
    public static BlockPos getOffsetInto(
        dev.ryanhcode.sable.sublevel.SubLevel sub, net.minecraft.world.level.Level destLevel
    ) {
        IplStraddlePoseMap.StraddleMapping mapping = getMappingInto(sub, destLevel);
        return mapping == null ? null : mapping.blockOffsetOrNull();
    }

    /** True when {@code position} is on the destination side of a same-dimension session. */
    public static boolean isInMappedHalf(
        dev.ryanhcode.sable.sublevel.SubLevel sub, net.minecraft.world.level.Level level, Vec3 position
    ) {
        for (Session s : SESSIONS.values()) {
            if (s.dest == level && s.parent == level && s.sub.getUniqueId().equals(sub.getUniqueId())) {
                // Mapped dest-plane test + proximity disambiguation (shared with the
                // client branch — see IplStraddlePoseMap.isInMappedHalf for why both
                // conditions are needed on rotated pairs).
                return IplStraddlePoseMap.isInMappedHalf(
                    s.mapping, s.portal, s.sub.boundingBox(), position);
            }
        }
        return false;
    }

    /**
     * Visit every active clone session whose DESTINATION is {@code destLevel}: the ship and
     * the source→dest block offset. Used by the ticket enrollment — the dest scene must hold
     * terrain around the CLONE's region (ship bounds ⊕ offset), which no stock path covers:
     * the ticket manager only enrolls around ships whose parent is that level, and the clone
     * is pure native state.
     */
    public static void forEachSessionInto(
        ServerLevel destLevel,
        java.util.function.BiConsumer<ServerSubLevel, IplStraddlePoseMap.StraddleMapping> visitor
    ) {
        for (Session s : SESSIONS.values()) {
            if (s.dest == destLevel && !s.sub.isRemoved()) {
                visitor.accept(s.sub, s.mapping);
            }
        }
    }

    // ------------------------------------------------------------------
    // Spawn / despawn.
    // ------------------------------------------------------------------

    private static boolean spawnClone(Session s) {
        RapierPhysicsPipeline parentPipeline = IplSceneOwnership.pipelineOf(s.parent);
        if (parentPipeline == null) return false;

        Pose3dc pose = s.sub.logicalPose();
        Vec3 mappedPos = s.mapping.mapPoint(new Vec3(
            pose.position().x(), pose.position().y(), pose.position().z()));
        org.joml.Quaterniond mappedOrient = s.mapping.mapQuat(pose.orientation());
        double[] p7 = {
            mappedPos.x, mappedPos.y, mappedPos.z,
            mappedOrient.x, mappedOrient.y, mappedOrient.z, mappedOrient.w
        };
        ipl.sable.mixin.IplRapier3DInvoker.ipl$createSubLevel(s.destScene, s.cloneId, p7);

        // Private section storage BEFORE any chunk feed: in a same-dimension straddle the
        // clone shares the real body's scene while describing the same ship-local section
        // coordinates, so the shared scene map would let the two bodies corrupt each other.
        if (ipl.sable.natives.IplRapierNatives.isAvailable()) {
            ipl.sable.natives.IplRapierNatives.useDedicatedChunks(s.destScene, s.cloneId);
            s.dedicatedChunks = true;
        }

        // Stats BEFORE any chunk insert — native insert_block unwraps local_bounds and
        // hard-aborts the process without them (the phase-1 lesson, lib.rs:218).
        Vector3dc com = s.sub.getMassTracker().getCenterOfMass();
        if (com != null) {
            ipl.sable.mixin.IplRapier3DInvoker.ipl$setCenterOfMass(s.destScene, s.cloneId, com.x(), com.y(), com.z());
            ipl.sable.mixin.IplRapier3DInvoker.ipl$setMassPropertiesFrom(s.destScene, s.cloneId, s.sub.getMassTracker());
        }
        BoundingBox3ic bounds = s.sub.getPlot().getBoundingBox();
        ipl.sable.mixin.IplRapier3DInvoker.ipl$setLocalBounds(s.destScene, s.cloneId,
            bounds.minX(), bounds.minY(), bounds.minZ(),
            bounds.maxX(), bounds.maxY(), bounds.maxZ());

        feedCloneChunks(s, parentPipeline);
        return true;
    }

    /**
     * Bake the ship's plot voxels and feed them to the clone in the dest scene. Reuses the
     * PARENT pipeline's accelerator + bakery (collider handles are valid in every scene —
     * the native registry is global) under the hosting read override (plot chunks
     * physically live in the hosting level).
     */
    private static void feedCloneChunks(Session s, RapierPhysicsPipeline parentPipeline) {
        IplRapierPipelineAccess access = (IplRapierPipelineAccess) parentPipeline;
        LevelAccelerator accel = access.ipl$accelerator();
        var bakery = access.ipl$colliderBakery();

        IplTerrainReadOverride.set(s.sub.getLevel());
        try {
            accel.clearCache();
            int[] packed = new int[LevelChunkSection.SECTION_SIZE];
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

            for (PlotChunkHolder holder : s.sub.getPlot().getLoadedChunks()) {
                LevelChunk chunk = holder.getChunk();
                ChunkPos cp = chunk.getPos();
                LevelChunk accelChunk = accel.getChunk(cp.x, cp.z);
                LevelChunkSection[] sections = chunk.getSections();

                for (int i = 0; i < chunk.getSectionsCount(); i++) {
                    if (sections[i].hasOnlyAir()) continue;
                    int sectionY = chunk.getSectionYFromSectionIndex(i);
                    int minX = cp.getMinBlockX(), minY = sectionY << 4, minZ = cp.getMinBlockZ();

                    java.util.Arrays.fill(packed, 0);
                    for (int bx = 0; bx < 16; bx++) {
                        for (int bz = 0; bz < 16; bz++) {
                            for (int by = 0; by < 16; by++) {
                                cursor.set(minX + bx, minY + by, minZ + bz);
                                VoxelNeighborhoodState state =
                                    VoxelNeighborhoodState.getState(accel, cursor, accelChunk);
                                RapierVoxelColliderData data = bakery.getPhysicsDataForBlock(
                                    accel.getBlockState(accelChunk, cursor));
                                int colliderValue = data == null ? 0 : data.handle() + 1;
                                packed[bx + (bz << 4) + (by << 8)] =
                                    ((int) state.byteRepresentation()) | (colliderValue << 16);
                            }
                        }
                    }
                    ipl.sable.mixin.IplRapier3DInvoker.ipl$addChunk(s.destScene, cp.x, sectionY, cp.z, packed, false, s.cloneId);
                    s.fedSections.add(SectionPos.asLong(cp.x, sectionY, cp.z));
                }
            }
        } finally {
            IplTerrainReadOverride.clear();
        }
    }

    private static void despawn(Session s) {
        // Dedicated storage is freed with the body. Calling removeChunk there would be
        // actively harmful: it targets the SHARED scene map, deleting real terrain or
        // sub-level sections that happen to share the clone's section coordinates.
        if (!s.dedicatedChunks) {
            for (int i = 0; i < s.fedSections.size(); i++) {
                long packed = s.fedSections.getLong(i);
                ipl.sable.mixin.IplRapier3DInvoker.ipl$removeChunk(s.destScene,
                    SectionPos.x(packed), SectionPos.y(packed), SectionPos.z(packed), false);
            }
        }
        ipl.sable.mixin.IplRapier3DInvoker.ipl$removeSubLevel(s.destScene, s.cloneId);
    }

    // ------------------------------------------------------------------
    // Position-based coupling: pin before the substep, measure after it.
    // ------------------------------------------------------------------

    /** Pin every clone whose DEST scene is about to step to the portal-mapped pose. */
    public static void preStep(ServerLevel steppingLevel, double dt) {
        if (SESSIONS.isEmpty()) return;

        for (Session s : SESSIONS.values()) {
            if (s.dest != steppingLevel) continue;
            if (s.sub.isRemoved()) {
                s.pinned = false;
                continue;
            }

            s.destFraction = destFraction(s);

            Pose3dc real = s.sub.logicalPose();
            Vec3 mappedPos = s.mapping.mapPoint(new Vec3(
                real.position().x(), real.position().y(), real.position().z()));
            Quaterniond mappedOrient = s.mapping.mapQuat(real.orientation());
            s.targetPose[0] = mappedPos.x;
            s.targetPose[1] = mappedPos.y;
            s.targetPose[2] = mappedPos.z;
            s.targetPose[3] = mappedOrient.x;
            s.targetPose[4] = mappedOrient.y;
            s.targetPose[5] = mappedOrient.z;
            s.targetPose[6] = mappedOrient.w;

            ipl.sable.mixin.IplRapier3DInvoker.ipl$teleportObject(s.destScene, s.cloneId,
                s.targetPose[0], s.targetPose[1], s.targetPose[2],
                s.targetPose[3], s.targetPose[4], s.targetPose[5], s.targetPose[6]);

            // Exact-set the clone's velocities to the real body's, rotated into the DEST
            // frame through the portal isometry (translation-only pairs degenerate to a
            // copy; addLinearAngularVelocities is a native set of current + delta).
            ipl.sable.mixin.IplRapier3DInvoker.ipl$getLinearVelocity(s.parentScene, s.realId, s.realLin);
            ipl.sable.mixin.IplRapier3DInvoker.ipl$getAngularVelocity(s.parentScene, s.realId, s.realAng);
            ipl.sable.mixin.IplRapier3DInvoker.ipl$getLinearVelocity(s.destScene, s.cloneId, s.cloneLin);
            ipl.sable.mixin.IplRapier3DInvoker.ipl$getAngularVelocity(s.destScene, s.cloneId, s.cloneAng);
            Vec3 mappedLin = s.mapping.mapVec(new Vec3(s.realLin[0], s.realLin[1], s.realLin[2]));
            Vec3 mappedAng = s.mapping.mapVec(new Vec3(s.realAng[0], s.realAng[1], s.realAng[2]));
            Rapier3D.addLinearAngularVelocities(s.destScene, s.cloneId,
                mappedLin.x - s.cloneLin[0], mappedLin.y - s.cloneLin[1],
                mappedLin.z - s.cloneLin[2],
                mappedAng.x - s.cloneAng[0], mappedAng.y - s.cloneAng[1],
                mappedAng.z - s.cloneAng[2], true);

            // The pinned baseline is the DEST-frame values (what the clone integrates
            // from); postStep's delta measurement subtracts these in the dest frame.
            s.pinnedLin[0] = mappedLin.x;
            s.pinnedLin[1] = mappedLin.y;
            s.pinnedLin[2] = mappedLin.z;
            s.pinnedAng[0] = mappedAng.x;
            s.pinnedAng[1] = mappedAng.y;
            s.pinnedAng[2] = mappedAng.z;
            s.pinned = true;
        }
    }

    /**
     * Fraction of the ship's bounds past the portal plane, measured as the ship AABB's
     * extent along the crossing direction that lies dest-side of the plane. A 1-D proxy
     * (ignores block density), but monotone with the actual crossing — all the authority
     * weighting needs.
     */
    private static double destFraction(Session s) {
        dev.ryanhcode.sable.companion.math.BoundingBox3dc box = s.sub.boundingBox();
        Vec3 p = s.portal.getOriginPos();
        Vec3 n = s.sourceToDestN;
        double tc = ((box.minX() + box.maxX()) * 0.5 - p.x) * n.x
                  + ((box.minY() + box.maxY()) * 0.5 - p.y) * n.y
                  + ((box.minZ() + box.maxZ()) * 0.5 - p.z) * n.z;
        double r = (box.maxX() - box.minX()) * 0.5 * Math.abs(n.x)
                 + (box.maxY() - box.minY()) * 0.5 * Math.abs(n.y)
                 + (box.maxZ() - box.minZ()) * 0.5 * Math.abs(n.z);
        double tMin = tc - r, tMax = tc + r;
        if (tMax <= 0.0) return 0.0;
        if (tMin >= 0.0 || tMax - tMin < 1e-9) return 1.0;
        return tMax / (tMax - tMin);
    }

    /**
     * After the dest scene stepped, transfer its solver correction to the real body. Gravity's
     * free-fall contribution is removed so a contact-free clone transfers nothing.
     */
    public static void postStep(ServerLevel steppingLevel, double dt) {
        if (SESSIONS.isEmpty()) return;

        for (Session s : SESSIONS.values()) {
            if (s.dest != steppingLevel || !s.pinned) continue;
            s.pinned = false;
            if (s.sub.isRemoved()) continue;

            // Diagnostics (wall-past-the-plane bug): reliable in-log view of the native
            // clip pass — how many solver contacts it judged/dropped for the REAL body,
            // and the last contact point, comparable against [IPL-CLONE-CLIP]'s region.
            long statNow = System.currentTimeMillis();
            if (statNow - lastClipStatLogMs > 2000
                && ipl.sable.natives.IplRapierNatives.isAvailable()) {
                lastClipStatLogMs = statNow;
                double[] cs = new double[5];
                ipl.sable.natives.IplRapierNatives.getClipStats(s.parentScene, s.realId, cs);
                LOG.info("[IPL-CLIP-STATS] realId={} seen={} dropped={} lastContact=({},{},{})",
                    s.realId, (long) cs[0], (long) cs[1],
                    String.format("%.2f", cs[2]), String.format("%.2f", cs[3]),
                    String.format("%.2f", cs[4]));
            }

            Rapier3D.getPose(s.destScene, s.cloneId, s.poseBuf);
            double dx = s.poseBuf[0] - s.targetPose[0] - s.destGravity.x * dt * dt * 0.5;
            double dy = s.poseBuf[1] - s.targetPose[1] - s.destGravity.y * dt * dt * 0.5;
            double dz = s.poseBuf[2] - s.targetPose[2] - s.destGravity.z * dt * dt * 0.5;
            // The pin gives the clone the real body's velocity; subtract free integration
            // to retain only the destination solver's contact correction.
            dx -= s.pinnedLin[0] * dt;
            dy -= s.pinnedLin[1] * dt;
            dz -= s.pinnedLin[2] * dt;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

            Quaterniond target = new Quaterniond(
                s.targetPose[3], s.targetPose[4], s.targetPose[5], s.targetPose[6]);
            Quaterniond current = new Quaterniond(
                s.poseBuf[3], s.poseBuf[4], s.poseBuf[5], s.poseBuf[6]);
            double wLen = Math.sqrt(s.pinnedAng[0] * s.pinnedAng[0]
                + s.pinnedAng[1] * s.pinnedAng[1] + s.pinnedAng[2] * s.pinnedAng[2]);
            if (wLen > 1e-9) {
                Quaterniond freeSpin = new Quaterniond().rotationAxis(wLen * dt,
                    s.pinnedAng[0] / wLen, s.pinnedAng[1] / wLen, s.pinnedAng[2] / wLen);
                target = freeSpin.mul(target, new Quaterniond());
            }
            Quaterniond dq = current.mul(target.conjugate(new Quaterniond()), new Quaterniond());
            if (dq.w < 0) dq.set(-dq.x, -dq.y, -dq.z, -dq.w);
            double axisLen = Math.sqrt(dq.x * dq.x + dq.y * dq.y + dq.z * dq.z);
            double angle = axisLen > 1e-9
                ? 2.0 * Math.acos(Math.min(1.0, Math.max(-1.0, dq.w))) : 0.0;

            ipl.sable.mixin.IplRapier3DInvoker.ipl$getLinearVelocity(s.destScene, s.cloneId, s.cloneLin);
            ipl.sable.mixin.IplRapier3DInvoker.ipl$getAngularVelocity(s.destScene, s.cloneId, s.cloneAng);
            double dvx = s.cloneLin[0] - s.pinnedLin[0] - s.destGravity.x * dt;
            double dvy = s.cloneLin[1] - s.pinnedLin[1] - s.destGravity.y * dt;
            double dvz = s.cloneLin[2] - s.pinnedLin[2] - s.destGravity.z * dt;
            double dax = s.cloneAng[0] - s.pinnedAng[0];
            double day = s.cloneAng[1] - s.pinnedAng[1];
            double daz = s.cloneAng[2] - s.pinnedAng[2];

            // Straddle wobble damp: both solvers used to correct at FULL authority every
            // substep — the dest solver shoves the real body, the source solver shoves
            // back next substep, and the ship visibly hums mid-straddle. Velocity
            // authority now follows the crossing fraction: the less of the ship is
            // through, the gentler the dest solver's velocity say (position/rotation
            // transfers below stay full — penetration must always resolve).
            double authority = MIN_AUTHORITY + (1.0 - MIN_AUTHORITY) * s.destFraction;
            dvx *= authority; dvy *= authority; dvz *= authority;
            dax *= authority; day *= authority; daz *= authority;
            double dvMag = Math.sqrt(dvx * dvx + dvy * dvy + dvz * dvz);
            double daMag = Math.sqrt(dax * dax + day * day + daz * daz);

            boolean velMoved = dvMag > 0.05 || daMag > 0.02;
            boolean posMoved = dist > TRANSFER_DEADBAND;
            boolean rotMoved = angle > 0.01;
            if (!posMoved && !rotMoved && !velMoved) continue;

            if (dist > MAX_POS_TRANSFER) {
                double f = MAX_POS_TRANSFER / dist;
                dx *= f; dy *= f; dz *= f;
            }
            double appliedAngle = Math.min(angle, MAX_ANG_TRANSFER);
            if (dvMag > MAX_VEL_TRANSFER) {
                double f = MAX_VEL_TRANSFER / dvMag;
                dvx *= f; dvy *= f; dvz *= f;
            }
            if (daMag > MAX_VEL_TRANSFER) {
                double f = MAX_VEL_TRANSFER / daMag;
                dax *= f; day *= f; daz *= f;
            }

            // Map the (capped, dest-frame) corrections back through the inverse portal
            // isometry before touching the real body. Rotation preserves magnitudes, so
            // the caps/deadbands above are frame-independent. Rotation delta conjugates:
            // R⁻¹ · dq · R (angle preserved, axis rotated back).
            Vec3 dPos = s.mapping.unmapVec(new Vec3(dx, dy, dz));
            Vec3 dVel = s.mapping.unmapVec(new Vec3(dvx, dvy, dvz));
            Vec3 dAng = s.mapping.unmapVec(new Vec3(dax, day, daz));
            Quaterniond srcDq = s.mapping.unmapRotationDelta(dq);
            double srcAxisLen = Math.sqrt(
                srcDq.x * srcDq.x + srcDq.y * srcDq.y + srcDq.z * srcDq.z);

            // Apply clone contact response immediately, matching the original pre/post model.
            Rapier3D.getPose(s.parentScene, s.realId, s.poseBuf);
            double nx = s.poseBuf[0] + (posMoved ? dPos.x : 0.0);
            double ny = s.poseBuf[1] + (posMoved ? dPos.y : 0.0);
            double nz = s.poseBuf[2] + (posMoved ? dPos.z : 0.0);
            Quaterniond realRot = new Quaterniond(
                s.poseBuf[3], s.poseBuf[4], s.poseBuf[5], s.poseBuf[6]);
            if (rotMoved && srcAxisLen > 1e-9) {
                Quaterniond clampedDq = new Quaterniond().rotationAxis(appliedAngle,
                    srcDq.x / srcAxisLen, srcDq.y / srcAxisLen, srcDq.z / srcAxisLen);
                realRot = clampedDq.mul(realRot, new Quaterniond()).normalize();
            }
            if (posMoved || rotMoved) {
                ipl.sable.mixin.IplRapier3DInvoker.ipl$teleportObject(s.parentScene, s.realId,
                    nx, ny, nz, realRot.x, realRot.y, realRot.z, realRot.w);
            }
            double avx = velMoved ? dVel.x : 0.0;
            double avy = velMoved ? dVel.y : 0.0;
            double avz = velMoved ? dVel.z : 0.0;
            double aax = velMoved ? dAng.x : 0.0;
            double aay = velMoved ? dAng.y : 0.0;
            double aaz = velMoved ? dAng.z : 0.0;
            // Majority-through: also bleed the real body's velocity component that
            // OPPOSES the correction (the source solver's push back into the
            // constraint), so the minority side loses the argument instead of
            // restarting the cycle next substep. Uses the pre-step velocities
            // captured in preStep — stale by one solve, fine for a damping term.
            double bleed = STRADDLE_DAMP * Math.max(0.0, 2.0 * s.destFraction - 1.0);
            if (velMoved && bleed > 0.0) {
                double m = dVel.length();
                if (m > 1e-9) {
                    double ix = dVel.x / m, iy = dVel.y / m, iz = dVel.z / m;
                    double along = s.realLin[0] * ix + s.realLin[1] * iy + s.realLin[2] * iz;
                    if (along < 0.0) {
                        avx -= along * bleed * ix;
                        avy -= along * bleed * iy;
                        avz -= along * bleed * iz;
                    }
                }
                double ma = dAng.length();
                if (ma > 1e-9) {
                    double ix = dAng.x / ma, iy = dAng.y / ma, iz = dAng.z / ma;
                    double along = s.realAng[0] * ix + s.realAng[1] * iy + s.realAng[2] * iz;
                    if (along < 0.0) {
                        aax -= along * bleed * ix;
                        aay -= along * bleed * iy;
                        aaz -= along * bleed * iz;
                    }
                }
            }
            // Rock damp: an active rotation correction means the two solvers are fighting
            // the orientation; remove a slice of the angular velocity along the correction
            // axis (both signs — the rock alternates every half-cycle, and each removal
            // takes energy out of the mode). Runs even when the velocity deadband didn't
            // trip: the rocking sustains itself through the orientation teleport alone.
            if (ANGULAR_DAMP > 0.0 && rotMoved && srcAxisLen > 1e-9) {
                double ix = srcDq.x / srcAxisLen;
                double iy = srcDq.y / srcAxisLen;
                double iz = srcDq.z / srcAxisLen;
                double along = s.realAng[0] * ix + s.realAng[1] * iy + s.realAng[2] * iz;
                aax -= ANGULAR_DAMP * along * ix;
                aay -= ANGULAR_DAMP * along * iy;
                aaz -= ANGULAR_DAMP * along * iz;
            }
            if (avx != 0.0 || avy != 0.0 || avz != 0.0
                || aax != 0.0 || aay != 0.0 || aaz != 0.0) {
                Rapier3D.addLinearAngularVelocities(s.parentScene, s.realId,
                    avx, avy, avz, aax, aay, aaz, true);
            }
            Rapier3D.wakeUpObject(s.parentScene, s.realId);

            long now = System.currentTimeMillis();
            if (now - lastServoLogMs > 2000) {
                lastServoLogMs = now;
                LOG.info("[IPL-CLONE-PBC] cloneId={} dPos={} dAng={} dVel={} crossed={}",
                    s.cloneId, String.format("%.3f", dist),
                    String.format("%.3f", angle), String.format("%.2f", dvMag),
                    String.format("%.2f", s.destFraction));
            }
        }
    }

    // ------------------------------------------------------------------
    // Feedback: contact records teed off the dest pipeline's per-tick readout.
    // Record stride 15: [idA, idB, pairForce, normalA(3), normalB(3), pointA(3), pointB(3)]
    // — points/normals body-local (COM-relative), force a pair-level scalar repeated per
    // manifold point.
    // ------------------------------------------------------------------

    public static void onCollisionRecords(ServerLevel level, double[] records) {
        if (SESSIONS.isEmpty() || records == null) return;

        // Bring-up probe: prove the tee fires while a session targets this level, and show
        // what the dest scene's contact buffer contains (remove later).
        boolean anyHere = false;
        for (Session s : SESSIONS.values()) {
            if (s.dest == level) { anyHere = true; break; }
        }
        if (anyHere) {
            long now = System.currentTimeMillis();
            if (now - lastTeeLogMs > 2000) {
                lastTeeLogMs = now;
                StringBuilder ids = new StringBuilder();
                for (int i = 0; i + 15 <= records.length && i < 150; i += 15) {
                    ids.append('(').append((int) records[i]).append(',')
                        .append((int) records[i + 1]).append(") ");
                }
                LOG.info("[IPL-CLONE-TEE] dest={} records={} pairs: {}",
                    level.dimension().location(), records.length / 15, ids);
            }
        }
        // Contact impulses are no longer re-applied to the real body — the position-based
        // coupling (preStep/postStep) copies the solver's own resolution of the clone
        // instead, which carries correct torques by construction. The tee remains as a
        // diagnostic (which pairs are contacting dest-side).
    }
}

package ipl.sable.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

/** Per-render-pass cache for straddle projection lists and render plans (pieces and their cells). */
public final class IplStraddleRenderCache {

    private static final ThreadLocal<ArrayDeque<Pass>> PASSES =
        ThreadLocal.withInitial(ArrayDeque::new);

    private static final class Pass {
        final ClientLevel level;
        @Nullable List<IplClientProjections.StraddleProjection> projections;
        final IdentityHashMap<ClientSubLevel, IdentityHashMap<Object, Boolean>> visibility = new IdentityHashMap<>();
        final IdentityHashMap<ClientSubLevel, IplClientProjections.RenderPlan> plans = new IdentityHashMap<>();
        final IdentityHashMap<ClientSubLevel, List<Portal>> renderPortals = new IdentityHashMap<>();
        final IdentityHashMap<ClientLevel, List<Portal>> portalCandidates;
        final IdentityHashMap<Portal, Boolean> canonicalPortalFaces;

        Pass(ClientLevel level, @Nullable Pass root) {
            this.level = level;
            // Portal entities/canonical identities do not depend on the camera. Geometry
            // decisions and resolved ship sessions DO: keep those local to this pass.
            portalCandidates = root == null ? new IdentityHashMap<>() : root.portalCandidates;
            canonicalPortalFaces = root == null ? new IdentityHashMap<>() : root.canonicalPortalFaces;
        }
    }

    /**
     * Monotonic render-frame counter. Incremented when the OUTERMOST renderLevel of a frame
     * begins, so per-frame sampling (the straddle sweep in IplClientVisualTransitLatch) can
     * tell "new frame" from "another pass / another nested portal view of the same frame".
     * A frame draws the main pass plus one renderLevel per visible portal, and each of those
     * passes re-resolves the same ships.
     */
    private static volatile long frameId;

    public static long frameId() {
        return frameId;
    }

    private IplStraddleRenderCache() {}

    public static void begin(@Nullable ClientLevel level) {
        if (level == null) return;
        ArrayDeque<Pass> passes = PASSES.get();
        if (passes.isEmpty()) frameId++;
        passes.addLast(new Pass(level, passes.peekFirst()));
    }

    public static void end(@Nullable ClientLevel level) {
        if (level == null) return;
        ArrayDeque<Pass> passes = PASSES.get();
        if (!passes.isEmpty() && passes.peekLast().level == level) {
            passes.removeLast();
        } else {
            // A nested pass that threw, or a mod that renders a level without our HEAD hook,
            // would leave its Pass on the stack forever: every later frame would answer from
            // that dead pass's cache and the frame counter would stop advancing (the visual
            // latch would compare a pose against itself). Drop the innermost pass belonging
            // to this level instead.
            Iterator<Pass> it = passes.descendingIterator();
            while (it.hasNext()) {
                if (it.next().level == level) {
                    it.remove();
                    break;
                }
            }
        }
        if (passes.isEmpty()) PASSES.remove();
    }

    public static List<IplClientProjections.StraddleProjection> projections(
        @Nullable ClientLevel level,
        Supplier<List<IplClientProjections.StraddleProjection>> supplier
    ) {
        Pass pass = current(level);
        if (pass == null) return supplier.get();
        if (pass.projections == null) pass.projections = supplier.get();
        return pass.projections;
    }

    @Nullable
    public static Boolean visibility(ClientSubLevel sub, Object pose) {
        Pass pass = current(null);
        var instances = pass == null ? null : pass.visibility.get(sub);
        return instances == null ? null : instances.get(pose);
    }

    public static void cacheVisibility(ClientSubLevel sub, Object pose, boolean visible) {
        Pass pass = current(null);
        if (pass != null) pass.visibility.computeIfAbsent(sub, s -> new IdentityHashMap<>()).put(pose, visible);
    }

    /** The pieces of {@code sub} in this pass; null is a cache miss. */
    @Nullable
    public static IplClientProjections.RenderPlan plan(ClientSubLevel sub) {
        Pass pass = current(null);
        return pass == null ? null : pass.plans.get(sub);
    }

    public static void cachePlan(ClientSubLevel sub, IplClientProjections.RenderPlan plan) {
        Pass pass = current(null);
        if (pass != null) pass.plans.put(sub, plan);
    }

    /** Null is a cache miss; an empty list is a cached negative result. */
    @Nullable
    public static List<Portal> renderPortals(ClientSubLevel sub) {
        Pass pass = current(null);
        return pass == null ? null : pass.renderPortals.get(sub);
    }

    public static void cacheRenderPortals(ClientSubLevel sub, List<Portal> portals) {
        Pass pass = current(null);
        if (pass != null) pass.renderPortals.put(sub, List.copyOf(portals));
    }

    public static List<Portal> portalCandidates(
        ClientLevel level
    ) {
        Pass pass = current(null);
        return pass == null ? null : pass.portalCandidates.get(level);
    }

    public static void cachePortalCandidates(ClientLevel level, List<Portal> candidates) {
        Pass pass = current(null);
        if (pass != null) pass.portalCandidates.put(level, candidates);
    }

    @Nullable
    public static Boolean canonicalPortalFace(Portal portal) {
        Pass pass = current(null);
        return pass == null ? null : pass.canonicalPortalFaces.get(portal);
    }

    public static void cacheCanonicalPortalFace(Portal portal, boolean canonical) {
        Pass pass = current(null);
        if (pass != null) pass.canonicalPortalFaces.put(portal, canonical);
    }

    /**
     * A parent handoff can arrive inside a nested portal pass. Discard every active pass's
     * source-frame lists so neither that pass nor an enclosing pass can reuse a projection.
     */
    public static void invalidateActivePasses() {
        for (Pass pass : PASSES.get()) {
            pass.projections = null;
            pass.renderPortals.clear();
            pass.visibility.clear();
            pass.plans.clear();
            pass.portalCandidates.clear();
            pass.canonicalPortalFaces.clear();
        }
    }

    @Nullable
    private static Pass current(@Nullable ClientLevel level) {
        ArrayDeque<Pass> passes = PASSES.get();
        Pass pass = passes.peekLast();
        return pass != null && (level == null || pass.level == level) ? pass : null;
    }
}

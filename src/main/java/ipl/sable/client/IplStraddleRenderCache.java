package ipl.sable.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.render.SourceClipPortalFinder;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Supplier;

/** Per-render-pass cache for hosted and mapped straddle lists. */
public final class IplStraddleRenderCache {

    private static final ThreadLocal<ArrayDeque<Pass>> PASSES =
        ThreadLocal.withInitial(ArrayDeque::new);

    private static final class Pass {
        final ClientLevel level;
        @Nullable List<ClientSubLevel> hosted;
        @Nullable List<IplClientHostedLookup.StraddleProjection> projections;
        final IdentityHashMap<ClientSubLevel, IdentityHashMap<Object, Boolean>> visibility = new IdentityHashMap<>();
        final IdentityHashMap<ClientSubLevel, List<SourceClipPortalFinder.ClipDecision>> allDecisions = new IdentityHashMap<>();
        final IdentityHashMap<ClientSubLevel, List<Portal>> renderPortals = new IdentityHashMap<>();
        final IdentityHashMap<ClientLevel, List<Portal>> portalCandidates;
        final IdentityHashMap<Portal, Boolean> canonicalPortalFaces;
        final IdentityHashMap<ClientSubLevel, SourceClipPortalFinder.ClipDecision> decisions =
            new IdentityHashMap<>();
        final java.util.Set<ClientSubLevel> noDecision =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());

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
            // used to leave its Pass on the stack forever: every later frame then answered
            // from that dead pass's cache and the frame counter stopped advancing, which is
            // exactly the state that made the visual latch compare a pose against itself.
            // Drop the innermost pass belonging to this level instead.
            java.util.Iterator<Pass> it = passes.descendingIterator();
            while (it.hasNext()) {
                if (it.next().level == level) {
                    it.remove();
                    break;
                }
            }
        }
        if (passes.isEmpty()) PASSES.remove();
    }

    public static List<ClientSubLevel> hosted(
        @Nullable ClientLevel level, Supplier<List<ClientSubLevel>> supplier
    ) {
        Pass pass = current(level);
        if (pass == null) return supplier.get();
        if (pass.hosted == null) pass.hosted = supplier.get();
        return pass.hosted;
    }

    public static List<IplClientHostedLookup.StraddleProjection> projections(
        @Nullable ClientLevel level,
        Supplier<List<IplClientHostedLookup.StraddleProjection>> supplier
    ) {
        Pass pass = current(level);
        if (pass == null) return supplier.get();
        if (pass.projections == null) pass.projections = supplier.get();
        return pass.projections;
    }

    @Nullable
    public static Boolean visibility(ClientSubLevel sub, Object pose) {
        Pass pass=current(null);
        var instances=pass==null?null:pass.visibility.get(sub);
        return instances==null?null:instances.get(pose);
    }
    public static void cacheVisibility(ClientSubLevel sub,Object pose,boolean visible) {
        Pass pass=current(null);
        if (pass!=null) pass.visibility.computeIfAbsent(sub,s->new IdentityHashMap<>()).put(pose,visible);
    }
    @Nullable
    public static List<SourceClipPortalFinder.ClipDecision> allDecisions(ClientSubLevel sub) {
        Pass pass=current(null);
        return pass==null?null:pass.allDecisions.get(sub);
    }
    public static void cacheAllDecisions(ClientSubLevel sub,List<SourceClipPortalFinder.ClipDecision> decisions) {
        Pass pass=current(null);
        if (pass!=null) pass.allDecisions.put(sub,decisions);
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

    @Nullable
    public static SourceClipPortalFinder.ClipDecision decision(ClientSubLevel sub) {
        Pass pass = current(null);
        if (pass == null) return null;
        return pass.decisions.get(sub);
    }

    public static boolean hasDecision(ClientSubLevel sub) {
        Pass pass = current(null);
        return pass != null && (pass.decisions.containsKey(sub) || pass.noDecision.contains(sub));
    }

    public static void cacheDecision(
        ClientSubLevel sub, @Nullable SourceClipPortalFinder.ClipDecision computed
    ) {
        Pass pass = current(null);
        if (pass == null) return;
        if (computed == null) pass.noDecision.add(sub);
        else pass.decisions.put(sub, computed);
    }

    /**
     * A parent handoff can arrive inside a nested portal pass. Discard every active pass's
     * source-frame lists so neither that pass nor an enclosing pass can reuse a projection.
     */
    public static void invalidateActivePasses() {
        for (Pass pass : PASSES.get()) {
            pass.hosted = null;
            pass.projections = null;
            pass.renderPortals.clear();
            pass.visibility.clear();
            pass.allDecisions.clear();
            pass.portalCandidates.clear();
            pass.canonicalPortalFaces.clear();
            pass.decisions.clear();
            pass.noDecision.clear();
        }
    }

    @Nullable
    private static Pass current(@Nullable ClientLevel level) {
        ArrayDeque<Pass> passes = PASSES.get();
        Pass pass = passes.peekLast();
        return pass != null && (level == null || pass.level == level) ? pass : null;
    }
}

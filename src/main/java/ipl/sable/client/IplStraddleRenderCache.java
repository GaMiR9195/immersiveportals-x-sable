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
        final IdentityHashMap<ClientLevel, List<Portal>> portalCandidates = new IdentityHashMap<>();
        final IdentityHashMap<Portal, Boolean> canonicalPortalFaces = new IdentityHashMap<>();
        final IdentityHashMap<ClientSubLevel, SourceClipPortalFinder.ClipDecision> decisions =
            new IdentityHashMap<>();
        final java.util.Set<ClientSubLevel> noDecision =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());

        Pass(ClientLevel level) {
            this.level = level;
        }
    }

    private IplStraddleRenderCache() {}

    public static void begin(@Nullable ClientLevel level) {
        if (level != null) PASSES.get().addLast(new Pass(level));
    }

    public static void end(@Nullable ClientLevel level) {
        if (level == null) return;
        ArrayDeque<Pass> passes = PASSES.get();
        if (!passes.isEmpty() && passes.peekLast().level == level) passes.removeLast();
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

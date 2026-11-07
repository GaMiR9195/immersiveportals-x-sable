package ipl.sable.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

/** Per-render-pass cache of projection lists and sub-level visibility, plus the frame counter. */
public final class IplStraddleRenderCache {

    private static final ThreadLocal<ArrayDeque<Pass>> PASSES =
        ThreadLocal.withInitial(ArrayDeque::new);

    private static final class Pass {
        final ClientLevel level;
        @Nullable List<IplClientProjections.StraddleProjection> projections;
        final IdentityHashMap<ClientSubLevel, IdentityHashMap<Object, Boolean>> visibility = new IdentityHashMap<>();

        Pass(ClientLevel level) {
            this.level = level;
        }
    }

    /**
     * Monotonic render-frame counter, incremented when the OUTERMOST renderLevel of a frame
     * begins. A frame draws the main pass plus one renderLevel per visible portal; per-frame
     * data (piece frames of {@code IplPieceRender}) is computed once for all of them.
     */
    private static volatile long frameId;

    public static long frameId() {
        return frameId;
    }

    private IplStraddleRenderCache() {}

    public static void begin(@Nullable ClientLevel level) {
        if (level == null) return;
        ArrayDeque<Pass> passes = PASSES.get();
        if (passes.isEmpty()) {
            IplStraddleSessionStore.drainTimed();
            frameId++;
        }
        passes.addLast(new Pass(level));
    }

    public static void end(@Nullable ClientLevel level) {
        if (level == null) return;
        ArrayDeque<Pass> passes = PASSES.get();
        if (!passes.isEmpty() && passes.peekLast().level == level) {
            passes.removeLast();
        } else {
            // A nested pass that threw, or a mod that renders a level without our HEAD hook,
            // would leave its Pass on the stack forever: every later frame would answer from
            // that dead pass's cache and the frame counter would stop advancing. Drop the
            // innermost pass belonging to this level instead.
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

    /**
     * Sessions or a parent handoff can change inside a nested portal pass. Discard every active
     * pass's lists so neither that pass nor an enclosing pass reuses a stale projection.
     */
    public static void invalidateActivePasses() {
        for (Pass pass : PASSES.get()) {
            pass.projections = null;
            pass.visibility.clear();
        }
    }

    @Nullable
    private static Pass current(@Nullable ClientLevel level) {
        ArrayDeque<Pass> passes = PASSES.get();
        Pass pass = passes.peekLast();
        return pass != null && (level == null || pass.level == level) ? pass : null;
    }
}

package ipl.sable.dim;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Owner-local processing pass of one server sub-level container.
 *
 * <p>Sable and its addons do per-world work by enumerating
 * {@code SubLevelContainer.getAllSubLevels()} from container-scoped hooks: the container
 * tick, its observers ({@code SubLevelPhysicsSystem.tick}, tracking), and the physics
 * phases/events published per {@code SubLevelPhysicsSystem}. In stock Sable every ship is
 * listed by exactly one container, so those loops process each ship once.
 *
 * <p>The public world view of a parent world also lists its hosted ships (lookups must see
 * them), so a parent-world processing loop would process a hosted ship a second time — after
 * the storage pass already did. While a pass is open for a container, that container's
 * enumeration is therefore owner-local: a parent world lists only what it owns, and the
 * storage pass lists its ships with each element's world frame armed (see
 * {@link IplSubLevelReadViews#framed}). Outside a pass nothing changes.
 *
 * <p>The scope also snapshots/restores {@link IplWorldFrameContext}, so an element frame left
 * armed by an addon loop that broke early never escapes the pass.
 */
public final class IplProcessingPass {

    private static final ThreadLocal<SubLevelContainer> CURRENT = new ThreadLocal<>();

    private IplProcessingPass() {}

    public record Scope(
        @Nullable SubLevelContainer previousPass,
        @Nullable ServerLevel previousFrame,
        @Nullable ServerLevel previousElementFrame,
        boolean previousRolling
    ) {}

    private static Scope snapshot() {
        return new Scope(CURRENT.get(), IplWorldFrameContext.current(),
            IplSubLevelReadViews.elementFrame(), IplWorldFrameContext.rollingActive());
    }

    /** The container whose processing pass is innermost on this thread, or null. */
    @Nullable
    public static SubLevelContainer current() {
        return CURRENT.get();
    }

    public static boolean isPassOf(@Nullable SubLevelContainer container) {
        return container != null && CURRENT.get() == container;
    }

    /**
     * Open a pass for {@code container}. The world frame is cleared: a pass belongs to its
     * container, not to whichever hosted actor happened to be running when it was entered
     * (the fused physics step is driven from inside the first world's container tick).
     */
    public static Scope enter(SubLevelContainer container) {
        return enter(container, false);
    }

    private static Scope enter(SubLevelContainer container, boolean physicsPhase) {
        Scope scope = snapshot();
        CURRENT.set(container);
        IplWorldFrameContext.pop(null);
        IplSubLevelReadViews.restoreElementFrame(null);
        // In its physics phases the hosting pass runs addon physics listeners over ships of
        // many worlds; their frame follows the ship being worked on (hosted plot
        // resolutions). Not in the container tick, whose Sable systems (tracking!) must see
        // the honest storage audience.
        IplWorldFrameContext.setRolling(physicsPhase && IplDimAgnostic.isHostingLevel(container.getLevel()));
        return scope;
    }

    public static void exit(Scope scope) {
        if (scope.previousPass() == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(scope.previousPass());
        }
        IplWorldFrameContext.pop(scope.previousFrame());
        IplSubLevelReadViews.restoreElementFrame(scope.previousElementFrame());
        IplWorldFrameContext.setRolling(scope.previousRolling());
    }

    /**
     * Run {@code body} outside any processing pass, with no world frame armed. The fused
     * native step steps every chart at once; it is driven from inside the first world's
     * container tick but belongs to no container, so contact callbacks fired from it see
     * ordinary world views (an explosion callback must still find the hosted ships).
     */
    public static void runOutside(Runnable body) {
        Scope scope = snapshot();
        CURRENT.remove();
        IplWorldFrameContext.pop(null);
        IplSubLevelReadViews.restoreElementFrame(null);
        IplWorldFrameContext.setRolling(false);
        try {
            body.run();
        } finally {
            exit(scope);
        }
    }

    /** The container's tick pass (its sub-level ticks and observer systems). */
    public static void run(SubLevelContainer container, Runnable body) {
        run(container, false, body);
    }

    /** One of the container's physics phases (pre/post physics ticks, substep phases). */
    public static void runPhysics(SubLevelContainer container, Runnable body) {
        run(container, true, body);
    }

    private static void run(SubLevelContainer container, boolean physicsPhase, Runnable body) {
        Scope scope = enter(container, physicsPhase);
        try {
            body.run();
        } finally {
            exit(scope);
        }
    }

    /**
     * One unit of work inside the current pass — one addon listener of a Sable physics
     * event. It starts without a pass-armed frame (an element or rolling frame left by the
     * previous listener does not leak into this one; seam frames are never cleared) and the
     * pass state is restored afterwards.
     */
    public static void unit(Runnable body) {
        if (CURRENT.get() == null) {
            body.run();
            return;
        }
        ServerLevel frame = IplWorldFrameContext.current();
        ServerLevel element = IplSubLevelReadViews.elementFrame();
        boolean passArmed = frame != null && frame == element;
        if (passArmed) {
            IplWorldFrameContext.pop(null);
            IplSubLevelReadViews.restoreElementFrame(null);
        }
        try {
            body.run();
        } finally {
            IplWorldFrameContext.pop(frame);
            IplSubLevelReadViews.restoreElementFrame(element);
        }
    }
}

package ipl.sable.atlas;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.platform.SableEventPublishPlatform;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import ipl.sable.duck.IplPhysicsStepClock;
import ipl.sable.mixin.PhysicsSystemAccessor;
import ipl.sable.natives.IplRapierNatives;
import ipl.sable.transit.IplShipPortalAnchor;
import ipl.sable.transit.IplTransitController;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Physics stepping of all dimensions (atlas, spec v3 §2.7).
 *
 * <p><b>Fused world (IPSable natives).</b> Every dimension is a chart of ONE Rapier world, so
 * Sable's per-level {@code tickPipelinePhysics} would step it once per dimension. Instead the
 * first unpaused system to tick in a server tick drives every system's substep phases in
 * {@code getAllLevels()} order, with exactly one native step per substep (armed on the last
 * pipeline, after every chart pushed its contraption poses). The driver's own level keeps
 * stock timing. The other levels are stepped before their own tick runs, so their
 * {@code updateLastPose} happens here, before the step, and their tick skips it
 * ({@code PhysicsSystemMixin}): otherwise {@code lastPose == pose} for the whole of their tick,
 * entities on moving sub-levels would not be carried and start-tracking would send a frozen
 * first interval. Forces queued by their gameplay land next tick.
 *
 * <p><b>Stock natives</b> (no IPSable build for the platform, failed load, kill switch). Each
 * level has its own world and steps itself in its own tick, exactly as in stock Sable; only the
 * bookkeeping below runs.
 *
 * <p>A paused dimension is skipped (no forces, readback or events), but under the fused world
 * its bodies keep simulating with the others. Logged once.
 */
public final class IplFusedStep {

    private static final Logger LOG = LogManager.getLogger("IPL-ATLAS");

    /** The {@code physicsTick} executing now may step the fused world (the last pipeline of a substep). */
    private static boolean stepArmed;
    private static int fusedTick = Integer.MIN_VALUE;
    private static boolean loggedPauseSkew;
    private static boolean loggedSubstepSkew;

    private IplFusedStep() {}

    /** Tick counts restart with every server (singleplayer worlds share the JVM). */
    public static void onServerStopping() {
        fusedTick = Integer.MIN_VALUE;
    }

    /** All dimensions share one native world: IPSable's natives are loaded. */
    public static boolean isFused() {
        return IplRapierNatives.isAvailable();
    }

    /** Whether the {@code physicsTick} executing now may step its native world. */
    public static boolean mayStepWorld() {
        return stepArmed || !isFused();
    }

    /** {@code system}'s bodies were already stepped in the current server tick. */
    public static boolean steppedThisTick(SubLevelPhysicsSystem system) {
        return ((IplPhysicsStepClock) system).ipl$steppedTick() == system.getLevel().getServer().getTickCount();
    }

    /** {@code system}'s bodies are still to be stepped in the current server tick. */
    public static boolean stepsLaterThisTick(SubLevelPhysicsSystem system) {
        return !system.getPaused() && !steppedThisTick(system);
    }

    /**
     * HEAD of every {@code tickPipelinePhysics}. Returns whether the stock step must be skipped:
     * always under the fused world (the first call of a server tick drives every system, later
     * calls have nothing left to do), never with stock natives.
     */
    public static boolean onTickPipelinePhysics(SubLevelPhysicsSystem system, ServerSubLevelContainer container) {
        int tick = system.getLevel().getServer().getTickCount();
        if (!isFused()) {
            ((IplPhysicsStepClock) system).ipl$markStepped(tick);
            return false;
        }
        if (fusedTick != tick) {
            fusedTick = tick;
            driveFused(system, tick);
        }
        return true;
    }

    private record Participant(ServerLevel level, SubLevelPhysicsSystem system, ServerSubLevelContainer container) {}

    private static void driveFused(SubLevelPhysicsSystem driver, int tick) {
        MinecraftServer server = driver.getLevel().getServer();
        List<Participant> participants = participants(server);
        if (participants.isEmpty()) return;

        int substeps = driver.getConfig().substepsPerTick;
        for (Participant p : participants) {
            if (p.system().getConfig().substepsPerTick != substeps && !loggedSubstepSkew) {
                loggedSubstepSkew = true;
                LOG.warn("[IPL-ATLAS] substepsPerTick differs between dimensions; using {} (logged once)", substeps);
            }
        }
        double dt = 1.0 / 20.0 / substeps;

        for (Participant p : participants) {
            ((IplPhysicsStepClock) p.system()).ipl$markStepped(tick);
            if (p.system() != driver) {
                for (ServerSubLevel subLevel : p.container().getAllSubLevels()) {
                    subLevel.updateLastPose();
                }
            }
            p.system().getPipeline().prePhysicsTicks();
        }

        sampleTransit(participants, server);
        for (int substep = 0; substep < substeps; substep++) {
            for (Participant p : participants) {
                // Stock order: currentSubstep is set before the pre-phases
                // (getPartialPhysicsTick feeds updateMergedMassData).
                ((PhysicsSystemAccessor) p.system()).ipl$setCurrentSubstep(substep);
                runGuarded(p, () -> preSubstepPhases(p, dt));
            }

            SubLevelPhysicsSystem.IN_PHYSICS_STEP = true;
            try {
                for (int i = 0; i < participants.size(); i++) {
                    Participant p = participants.get(i);
                    boolean last = i == participants.size() - 1;
                    runGuarded(p, () -> {
                        stepArmed = last;
                        try {
                            p.system().getPipeline().physicsTick(dt);
                        } finally {
                            stepArmed = false;
                        }
                    });
                }
            } finally {
                SubLevelPhysicsSystem.IN_PHYSICS_STEP = false;
            }

            for (Participant p : participants) {
                runGuarded(p, () -> postSubstepPhases(p, dt));
            }
            sampleTransit(participants, server);
        }

        for (Participant p : participants) {
            ((PhysicsSystemAccessor) p.system()).ipl$setCurrentSubstep(substeps);
            runGuarded(p, () -> p.system().getPipeline().postPhysicsTicks());
        }

        // Ship-anchored portals follow their ships' poses of this tick before any transit runs.
        IplShipPortalAnchor.tickAll(server);
    }

    /** Apertures/labels are sampled at solver boundaries, not a tick later. */
    private static void sampleTransit(List<Participant> participants, MinecraftServer server) {
        IplShipPortalAnchor.tickAll(server);
        for (Participant p : participants) {
            runGuarded(p, () -> IplTransitController.onPhysicsSample(p.container()));
        }
    }

    /** Unpaused physics systems in level tick order. */
    private static List<Participant> participants(MinecraftServer server) {
        List<Participant> participants = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(level);
            if (container == null || system == null) continue;
            if (system.getPaused()) {
                if (!loggedPauseSkew) {
                    loggedPauseSkew = true;
                    LOG.info("[IPL-ATLAS] physics is paused in {}: its phases are skipped, but its bodies "
                        + "keep simulating in the shared world (logged once)", level.dimension().location());
                }
                continue;
            }
            participants.add(new Participant(level, system, container));
        }
        return participants;
    }

    private static void preSubstepPhases(Participant p, double dt) {
        SubLevelPhysicsSystem system = p.system();
        ServerSubLevelContainer container = p.container();

        for (ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) continue;
            subLevel.prePhysicsTickBegin();
        }
        for (ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) continue;
            subLevel.updateMergedMassData((float) system.getPartialPhysicsTick());
        }
        for (ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) continue;
            subLevel.prePhysicsTick(system, system.getPhysicsHandle(subLevel), dt);
        }
        SableEventPublishPlatform.INSTANCE.prePhysicsTick(system, dt);
        for (ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) continue;
            subLevel.applyQueuedForces(system, system.getPhysicsHandle(subLevel), dt);
        }
    }

    private static void postSubstepPhases(Participant p, double dt) {
        SubLevelPhysicsSystem system = p.system();
        PhysicsSystemAccessor access = (PhysicsSystemAccessor) system;

        // Fragile blocks can break during the step: removals are processed every substep.
        p.container().processSubLevelRemovals();
        access.ipl$updateAllPoses(p.container());

        var wakeUps = access.ipl$queuedWakeUps();
        for (var object : wakeUps) {
            object.wakeUp();
        }
        wakeUps.clear();

        SableEventPublishPlatform.INSTANCE.postPhysicsTick(system, dt);
    }

    /** Stock crash-report framing, naming the dimension whose phases failed. */
    private static void runGuarded(Participant p, Runnable phases) {
        SubLevelPhysicsSystem.currentlySteppingSystem = p.system();
        try {
            phases.run();
        } catch (Exception e) {
            CrashReport report = CrashReport.forThrowable(e, "Sable ticking physics (IPL fused step)");
            report.addCategory("Current physics state").setDetail("Dimension", p.level().dimension());
            throw new ReportedException(report);
        }
    }
}

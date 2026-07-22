package ipl.sable.mixin;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import ipl.sable.iface.IplKinematicSubLevelHolder;
import ipl.sable.transit.MirrorRemovalGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Block any {@link SubLevelContainer#removeSubLevel} call against a kinematic
 * mirror except those initiated by our own {@code MirrorOps.despawnMirror}.
 *
 * <p><b>Why this is necessary even though we already have the {@code isInvalid}
 * skip mixin:</b> the WrapOperation on {@code MassData.isInvalid()} only
 * defends the {@code processSubLevelRemovals} auto-removal path. The user's
 * test log showed the WrapOperation firing (mirrorChecks=458/5s, so the mixin
 * is binding correctly) yet mirrors were still disappearing from the dest
 * container every tick (sync fail reason: {@code getSubLevel null}). That
 * means some <i>other</i> code path is calling
 * {@code SubLevelContainer.removeSubLevel(...)} directly on our mirrors.
 *
 * <p>Rather than continuing to whack-a-mole each killer path, intercept the
 * removal at the choke point. Both removeSubLevel overloads converge here
 * (the SubLevel-variant just delegates to the (int, int, Reason) variant).
 *
 * <p>Authorisation: we set {@link MirrorRemovalGuard#inAuthorizedRemoval} to
 * {@code true} in {@code MirrorOps.despawnMirror} (in a try/finally) for the
 * duration of the legitimate removeSubLevel call. The mixin checks the flag;
 * if it's set, our own teardown is in progress, pass through. If it isn't,
 * the call is from somewhere else trying to kill our mirror -- log a stack
 * trace identifying the caller, then cancel.
 *
 * <p><b>Risk:</b> if Sable's caller is doing genuine cleanup (e.g., the plot
 * slot needs to be reclaimed for a new sub-level), blocking the removal could
 * leave Sable in a confused state. The diagnostic stack trace lets us
 * identify those cases. For now, blocking is correct because our mirrors are
 * <i>not</i> invalid -- we manage them externally and don't want Sable's
 * health checks killing them.
 */
@Pseudo
@Mixin(value = SubLevelContainer.class, remap = false)
public abstract class SableMirrorRemovalGuardMixin {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-mirror-guard");

    // Aggregate counters: the previous version logged a stack trace per call,
    // which fires thousands of times per millisecond when Sable's chunk-ticket
    // manager unloads the mirror. That choked the server thread (entities froze
    // in the user's last test) and dumped megabytes of log. Now we just count
    // and emit a 5s summary -- enough to confirm we're still backstopping
    // unexpected removals, cheap enough not to harm tick performance.
    @Unique
    private static long ipl$blockedCount = 0;
    @Unique
    private static long ipl$lastReportNanos = 0L;

    /**
     * Targets {@code removeSubLevel(int, int, SubLevelRemovalReason)} -- the
     * choke point both removeSubLevel overloads converge through.
     */
    @Inject(
        method = "removeSubLevel(IILdev/ryanhcode/sable/sublevel/storage/SubLevelRemovalReason;)V",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private void ipl$blockUnauthorizedMirrorRemoval(
        int x, int z, SubLevelRemovalReason reason, CallbackInfo ci
    ) {
        // Inline the container's get-by-plot-coords. Faster + avoids surfacing a
        // mismatched plot index into the diagnostic if our index math is off.
        SubLevelContainer self = (SubLevelContainer) (Object) this;
        SubLevel candidate;
        try {
            candidate = self.getSubLevel(x, z);
        } catch (Throwable t) {
            return; // fall through to Sable's own null-check; not our problem
        }
        if (candidate == null) return;

        boolean kinematic = candidate instanceof IplKinematicSubLevelHolder holder
            && holder.ipl$isKinematicMirror();
        if (!kinematic) return;

        if (MirrorRemovalGuard.inAuthorizedRemoval) {
            // Our despawn path: let Sable proceed normally.
            return;
        }

        // Unauthorized removal of a kinematic mirror -- silently cancel +
        // increment counter. Periodic summary lets us see if/when this is
        // backstopping anything (with the SkipMoveToUnloaded mixin in place,
        // this counter should ideally stay at zero in steady state).
        ipl$blockedCount++;
        long now = System.nanoTime();
        if (ipl$lastReportNanos == 0L) ipl$lastReportNanos = now;
        if (now - ipl$lastReportNanos >= 5_000_000_000L) {
            if (ipl$blockedCount > 0) {
                LOG.warn("[IPL-MIRROR-GUARD] backstopped {} unauthorized removeSubLevel calls in last 5s "
                    + "(reason of last: {} for uuid={})",
                    ipl$blockedCount, reason, candidate.getUniqueId());
            }
            ipl$blockedCount = 0;
            ipl$lastReportNanos = now;
        }
        ci.cancel();
    }
}

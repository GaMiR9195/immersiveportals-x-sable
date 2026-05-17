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

        // Unauthorized removal of a kinematic mirror. Log the caller so we can
        // identify what path is killing it, then cancel.
        Throwable trace = new Throwable("kinematic mirror removal blocked");
        StringBuilder sb = new StringBuilder();
        sb.append("[IPL-MIRROR-GUARD] BLOCKED unauthorized removeSubLevel for kinematic mirror uuid=")
          .append(candidate.getUniqueId())
          .append(" plot=(").append(x).append(",").append(z).append(")")
          .append(" reason=").append(reason)
          .append("\n  Caller stack (top 12 frames):");
        StackTraceElement[] frames = trace.getStackTrace();
        int limit = Math.min(frames.length, 12);
        for (int i = 0; i < limit; i++) {
            sb.append("\n    ").append(frames[i]);
        }
        LOG.warn(sb.toString());
        ci.cancel();
    }
}

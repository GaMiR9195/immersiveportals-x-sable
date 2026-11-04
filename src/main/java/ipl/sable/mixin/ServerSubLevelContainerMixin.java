package ipl.sable.mixin;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import ipl.sable.IplPerfDiagnostics;
import ipl.sable.transit.IplTransitController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives {@link IplTransitController} at the TAIL of every {@link ServerSubLevelContainer#tick()}.
 * By then Sable has stepped physics ({@code logicalPose} is this tick's pose), processed the
 * removals queued during physics and run every observer, so the tracking system has already sent
 * this tick's bounds and movement. Allocations and removals made by a transit land before the
 * next tick.
 */
@Pseudo
@Mixin(value = ServerSubLevelContainer.class, remap = false)
public abstract class ServerSubLevelContainerMixin {

    /** Start of this container's tick, for {@code -Dipl.perfDiagnostics=true} only. */
    @Unique
    private long ipl$tickStart;

    @Inject(method = "tick", at = @At("HEAD"), require = 1)
    private void ipl$onContainerTickHead(CallbackInfo ci) {
        if (IplPerfDiagnostics.ENABLED) ipl$tickStart = System.nanoTime();
    }

    @Inject(method = "tick", at = @At("TAIL"), require = 1)
    private void ipl$onContainerTickTail(CallbackInfo ci) {
        if (!IplPerfDiagnostics.ENABLED) {
            IplTransitController.onContainerTick((ServerSubLevelContainer) (Object) this);
            return;
        }
        long transitStart = System.nanoTime();
        try {
            IplTransitController.onContainerTick((ServerSubLevelContainer) (Object) this);
        } finally {
            long end = System.nanoTime();
            IplPerfDiagnostics.record(IplPerfDiagnostics.SERVER_TRANSIT, end - transitStart);
            if (ipl$tickStart != 0L) IplPerfDiagnostics.record(IplPerfDiagnostics.SERVER_CONTAINER, end - ipl$tickStart);
            ipl$tickStart = 0L;
        }
    }
}

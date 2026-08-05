package ipl.sable.mixin;

import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DIAGNOSTIC: who flips entity-chunk visibility for plot-range chunks. The ship-end
 * plunger unload chain starts with the plot chunk's entity visibility dropping from
 * TICKING back to HIDDEN (stop-tracking → client remove → queued unload → server
 * UNLOADED_TO_CHUNK ~10s later). {@code IplParentPlotEntityTicking} marks these chunks
 * ENTITY_TICKING; this logs every later transition with a stack so the next repro names
 * the caller that un-marks them. Plot chunks are unmistakable by coordinate magnitude.
 */
@Mixin(PersistentEntitySectionManager.class)
public abstract class IplPlotChunkStatusProbeMixin {

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-plot-chunk-status");

    @Inject(method = "updateChunkStatus(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/server/level/FullChunkStatus;)V",
        at = @At("HEAD"), require = 0)
    private void ipl$logPlotChunkStatus(ChunkPos pos, FullChunkStatus status, CallbackInfo ci) {
        if (Math.abs(pos.x) < 100_000 && Math.abs(pos.z) < 100_000) return;

        StringBuilder stack = new StringBuilder();
        StackTraceElement[] frames = new Throwable().getStackTrace();
        for (int i = 1; i < Math.min(frames.length, 10); i++) {
            stack.append("\n    at ").append(frames[i]);
        }
        IPL$LOG.info("[IPL-PLOT-CHUNK-STATUS] chunk={} -> {}{}", pos, status, stack);
    }

    /** Self-heal: an external downgrade of a plot chunk that still hosts live plot
     * entities gets ENTITY_TICKING restored immediately (no-op recursion: the restore
     * call re-enters with ENTITY_TICKING and is skipped). */
    @Inject(method = "updateChunkStatus(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/server/level/FullChunkStatus;)V",
        at = @At("TAIL"), require = 0)
    private void ipl$reassertPlotChunkTicking(
        ChunkPos pos, FullChunkStatus status, CallbackInfo ci
    ) {
        if (status == FullChunkStatus.ENTITY_TICKING) return;
        if (Math.abs(pos.x) < 100_000 && Math.abs(pos.z) < 100_000) return;
        ipl.sable.dim.IplParentPlotEntityTicking.reassertIfActive(this, pos);
    }
}

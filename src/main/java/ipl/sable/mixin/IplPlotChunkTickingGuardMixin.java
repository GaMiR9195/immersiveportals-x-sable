package ipl.sable.mixin;

import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevent exact live plot entity sections from being downgraded before tracking-end
 * callbacks. Restoring status at TAIL is too late: addon registries already lost actors. */
@Mixin(PersistentEntitySectionManager.class)
public abstract class IplPlotChunkTickingGuardMixin {

    @Inject(method = "updateChunkStatus(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/server/level/FullChunkStatus;)V",
        at = @At("HEAD"), cancellable = true, require = 1)
    private void ipl$reassertPlotChunkTicking(
        ChunkPos pos, FullChunkStatus status, CallbackInfo ci
    ) {
        if (status == FullChunkStatus.ENTITY_TICKING) return;
        if (ipl.sable.dim.IplParentPlotEntityTicking.protects(this, pos)) ci.cancel();
    }
}

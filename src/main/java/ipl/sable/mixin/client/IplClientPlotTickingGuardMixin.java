package ipl.sable.mixin.client;

import ipl.sable.client.IplClientPlotEntityTicking;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.TransientEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An old parent block-chunk unload must not suspend storage-backed plot actors. */
@Mixin(TransientEntitySectionManager.class)
public abstract class IplClientPlotTickingGuardMixin {
    @Inject(method = "stopTicking", at = @At("HEAD"), cancellable = true, require = 1)
    private void ipl$keepLivePlotTicking(ChunkPos chunk, CallbackInfo ci) {
        if (IplClientPlotEntityTicking.protects(this, chunk)) ci.cancel();
    }
}

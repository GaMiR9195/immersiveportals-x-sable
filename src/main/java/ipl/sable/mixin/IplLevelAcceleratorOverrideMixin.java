package ipl.sable.mixin;

import dev.ryanhcode.sable.util.LevelAccelerator;
import ipl.sable.transit.IplTerrainReadOverride;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Route {@code LevelAccelerator} chunk reads to {@link IplTerrainReadOverride}'s level while
 * the override is set (only during hosted-terrain pre-enrollment — see
 * {@code IplHostedTicketManagerMixin}). Injected at the head of {@code getChunk(int,int)} so
 * the accelerator's single-entry chunk cache is fully bypassed: no parent-dim chunk can leak
 * into the cache and be served for a later hosting-dim read at the same coordinates.
 */
@Pseudo
@Mixin(value = LevelAccelerator.class, remap = false)
public abstract class IplLevelAcceleratorOverrideMixin {

    @Inject(method = "getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;",
        at = @At("HEAD"), cancellable = true, require = 0)
    private void ipl$readFromOverrideLevel(int chunkX, int chunkZ, CallbackInfoReturnable<LevelChunk> cir) {
        Level override = IplTerrainReadOverride.get();
        if (override != null) {
            cir.setReturnValue(override.getChunk(chunkX, chunkZ));
        }
    }
}

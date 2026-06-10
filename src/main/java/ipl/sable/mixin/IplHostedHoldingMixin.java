package ipl.sable.mixin;

import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No mid-session holding for hosted sub-levels.
 *
 * <p>Sable's holding lifecycle is driven by the container level's chunk load/unload
 * events: when a chunk intersecting a sub-level unloads, the ship is serialized into a
 * holding chunk and REMOVED from the world. In the hosting dimension those chunk events
 * are physics-ticket churn (terrain enrollment loading/unloading void chunks at
 * parent-frame coordinates), not player proximity — so a ship could be despawned into
 * holding mid-flight by its own ticket turbulence (the likely cause of the rare
 * "sub-level disappears on crossing"). Hosted ships are always-live by design; unload
 * events are ignored. Saving is unaffected ({@code saveAll} serializes all LIVE
 * sub-levels on world save), and load events still process (boot restore feeds them).
 */
@Pseudo
@Mixin(value = SubLevelHoldingChunkMap.class, remap = false)
public abstract class IplHostedHoldingMixin {

    @Shadow(remap = false)
    @Final
    private ServerLevel level;

    @Inject(method = "updateChunkStatus", at = @At("HEAD"), cancellable = true, require = 0)
    private void ipl$noMidSessionHoldsOnHosting(ChunkPos chunkPos, boolean loaded, CallbackInfo ci) {
        if (!loaded && IplDimAgnostic.isEnabled() && IplDimAgnostic.isHostingLevel(this.level)) {
            ci.cancel();
        }
    }
}

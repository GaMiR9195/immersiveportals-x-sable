package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import dev.ryanhcode.sable.network.packets.tcp.ClientboundFinalizeSubLevelPacket;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A repeated finalize refreshes a sub-level in place instead of rebuilding its renderer.
 *
 * <p>Sable's full sync ends with a finalize packet whose handler recreates the render data.
 * The first time, that is the setup. On a re-sync of a sub-level the client already holds
 * (start-tracking deduplicated by {@code StartTrackingPacketMixin}) it threw away every
 * compiled section, so the ship blinked out and rebuilt. The re-sent chunks already re-dirty
 * the sections whose content changed, and those recompile in place.
 */
@Mixin(value = ClientboundFinalizeSubLevelPacket.class, remap = false)
public abstract class FinalizeSubLevelPacketMixin {

    @WrapOperation(
        method = "handle",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/sublevel/ClientSubLevel;setFinalized()V"),
        require = 1
    )
    private void ipl$noteRefinalize(
        ClientSubLevel subLevel, Operation<Void> original, @Share("refinalize") LocalBooleanRef refinalize
    ) {
        refinalize.set(subLevel.isFinalized() && subLevel.getRenderData() != null);
        original.call(subLevel);
    }

    @WrapOperation(
        method = "handle",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/sublevel/ClientSubLevel;updateRenderData()V"),
        require = 1
    )
    private void ipl$keepRenderData(
        ClientSubLevel subLevel, Operation<Void> original, @Share("refinalize") LocalBooleanRef refinalize
    ) {
        if (!refinalize.get()) original.call(subLevel);
    }
}

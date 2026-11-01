package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplParentDimSync;
import ipl.sable.transit.IplEntityFrames;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Encodes the local player's ship-local position in the frame it is attached to (see
 * {@link IplEntityFrames#encodeOutgoingPosition}). Sable rewrites the position field right
 * before this read (and restores it at RETURN); the wrap runs after that rewrite. A ghost
 * left behind by a cross-dimension move has no frame on the server any more, so a player
 * still standing on it is sent in world coordinates.
 */
@Mixin(LocalPlayer.class)
public abstract class IplLocalPlayerFrameEncodeMixin {

    @WrapOperation(
        method = "sendPosition",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getX()D", ordinal = 0),
        require = 1
    )
    private double ipl$encodeInAttachedFrame(LocalPlayer self, Operation<Double> original) {
        boolean ghost = Sable.HELPER.getTrackingSubLevel(self) instanceof ClientSubLevel sub
            && IplParentDimSync.isGhost(sub);
        IplEntityFrames.encodeOutgoingPosition(self, ghost);
        return original.call(self);
    }
}

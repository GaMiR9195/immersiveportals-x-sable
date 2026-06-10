package ipl.sable.mixin.client;

import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bridge block-change predictions across the hosting-dim seam.
 *
 * <p>When a player breaks/places a block on a hosted sub-level, the client-side prediction
 * is recorded in the PLAYER's level ({@code BlockStatePredictionHandler}); the server's
 * confirming block update, however, arrives dim-stamped to {@code ipl_sable:sublevels} and
 * is applied under THAT level. The player's prediction is never marked server-verified, so
 * the following ack packet rolls the block back to its pre-action state — visually undoing
 * the break (and writing the stale state into the shared plot chunk).
 *
 * <p>Fix: when the hosting client level receives a server-verified state at any position,
 * forward it to the player's level's prediction handler so pending predictions there are
 * confirmed instead of rolled back.
 */
@Mixin(ClientLevel.class)
public abstract class IplPredictionBridgeMixin {

    @Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"))
    private void ipl$confirmPredictionOnPlayerLevel(
        BlockPos pos, BlockState state, int flags, CallbackInfo ci
    ) {
        if (!IplDimAgnostic.isEnabled()) return;
        ClientLevel self = (ClientLevel) (Object) this;
        if (!IplDimAgnostic.isHostingLevel(self)) return;

        var player = Minecraft.getInstance().player;
        if (player == null) return;
        ClientLevel playerLevel = (ClientLevel) player.clientLevel;
        if (playerLevel == null || playerLevel == self) return;

        ((IplClientLevelPredictionAccessor) playerLevel)
            .ipl$getBlockStatePredictionHandler()
            .updateKnownServerState(pos, state);
    }
}

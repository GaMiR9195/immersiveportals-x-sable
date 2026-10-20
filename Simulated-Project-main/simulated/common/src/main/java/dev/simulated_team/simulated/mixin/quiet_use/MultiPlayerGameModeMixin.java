package dev.simulated_team.simulated.mixin.quiet_use;

import dev.simulated_team.simulated.util.QuietUse;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
    @Inject(method = "useItemOn", at = @At(value = "INVOKE", target = "Lorg/apache/commons/lang3/mutable/MutableObject;<init>()V"), cancellable = true)
    private void quietUseIntercept(final LocalPlayer player, final InteractionHand hand, final BlockHitResult result, final CallbackInfoReturnable<InteractionResult> cir) {
        final BlockState state = player.level().getBlockState(result.getBlockPos());
        if (state.getBlock() instanceof final QuietUse quietUse) {
            final InteractionResult useResult = quietUse.quietUse(player, hand, result.getBlockPos(), state);
            if (useResult != null) {
                cir.setReturnValue(useResult);
            }
        }
    }
}

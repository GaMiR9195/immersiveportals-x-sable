package ipl.sable.mixin.client;

import ipl.sable.client.IplClientFlywheelReroute;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Removal identity, rather than position, owns a queued Flywheel visual. */
@Mixin(BlockEntity.class)
public abstract class IplHostedVisualRemovalMixin {
    @Inject(method = "setRemoved", at = @At("HEAD"), require = 1)
    private void ipl$retireVisual(CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        if (self.getLevel() != null && self.getLevel().isClientSide) {
            IplClientFlywheelReroute.onBlockEntityRemoved(self.getLevel(), self);
        }
    }
}
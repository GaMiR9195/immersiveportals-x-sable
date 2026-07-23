package ipl.sable.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Invoker for Offroad's private {@code WheelMountBlockEntity.applyBatchedForces}. */
@Pseudo
@Mixin(
    targets = "dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity",
    remap = false
)
public interface IplWheelMountInvoker {

    @Invoker(value = "applyBatchedForces", remap = false)
    void ipl$applyBatchedForces();
}

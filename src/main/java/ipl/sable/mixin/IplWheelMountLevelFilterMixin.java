package ipl.sable.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.Iterator;

/**
 * Offroad's {@code WheelMountBlockEntity.queuedWheelMounts} is a STATIC set
 * accumulated per level but drained globally — {@code applyAllBatchedForces}
 * ignores its {@code level} argument and clears the whole set
 * (WheelMountBlockEntity.java:71,99-107). Pre-atlas that mis-attributed timing
 * across dimensions; under the fused step (atlas M1) whichever dimension's event
 * fires first would drain every dimension's wheel forces. Filter the drain to the
 * invoking level and leave other levels' entries queued for their own pass.
 */
@Pseudo
@Mixin(
    targets = "dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity",
    remap = false
)
public abstract class IplWheelMountLevelFilterMixin {

    @Shadow(remap = false)
    @Final
    private static Collection<?> queuedWheelMounts;

    @Inject(
        method = "applyAllBatchedForces",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private static void ipl$drainOnlyOwnLevel(ServerLevel level, double timeStep, CallbackInfo ci) {
        ci.cancel();
        Iterator<?> it = queuedWheelMounts.iterator();
        while (it.hasNext()) {
            BlockEntity be = (BlockEntity) it.next();
            if (be.getLevel() != level) {
                continue; // another dimension's mount — its own drain pass handles it
            }
            it.remove();
            if (!be.isRemoved()) {
                ((IplWheelMountInvoker) (Object) be).ipl$applyBatchedForces();
            }
        }
    }
}

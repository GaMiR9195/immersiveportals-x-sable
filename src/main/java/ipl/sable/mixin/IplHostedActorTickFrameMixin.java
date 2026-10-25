package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.IplWorldFrameContext;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sable's per-tick block-entity actor hook ({@code BlockEntitySubLevelActor.sable$tick}, run
 * from {@code SubLevelPhysicsSystem.tick}) is ship code like a BE tick or a physics actor:
 * arm the owning ship's world frame for it, so actor-side terrain probes, audiences and
 * broadcasts reach the world the ship is in instead of the storage void.
 */
@Pseudo
@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public abstract class IplHostedActorTickFrameMixin {

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/api/block/BlockEntitySubLevelActor;sable$tick(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;)V"
        ),
        require = 0,
        remap = false
    )
    private void ipl$worldFrameDuringActorTick(
        BlockEntitySubLevelActor actor, ServerSubLevel subLevel, Operation<Void> original
    ) {
        ServerLevel parent = IplDimAgnostic.isHosted(subLevel)
            ? IplDimAgnostic.getServerParentLevel(subLevel) : null;
        if (parent == null) {
            original.call(actor, subLevel);
            return;
        }
        ServerLevel previous = IplWorldFrameContext.push(parent);
        try {
            original.call(actor, subLevel);
        } finally {
            IplWorldFrameContext.pop(previous);
        }
    }
}

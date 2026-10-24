package ipl.sable.mixin;
import dev.ryanhcode.sable.api.entity.EntitySubLevelUtil;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.IplWorldFrameContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Newly assembled plot-local retained entities must actually live with their plot chunks. */
@Mixin(ServerLevel.class)
public abstract class IplHostedRetainedEntityInsertionMixin {
    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true, require = 1)
    private void ipl$storeRetained(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel source = (ServerLevel)(Object)this;
        if (IplDimAgnostic.isHostingLevel(source) || entity.level() != source || EntitySubLevelUtil.shouldKick(entity)) return;
        var hosting = IplDimAgnostic.getHostingContainerFor(source);
        if (hosting == null || !(hosting.getLevel() instanceof ServerLevel storage)) return;
        var parent = IplWorldFrameContext.resolveParentForPlotBe(storage,entity.blockPosition());
        if (parent != source) return; // exact live owner, never a magnitude-only guess
        ((IplEntityLevelInvoker)entity).ipl$invokeSetLevel(storage);
        boolean added = false;
        try {
            added = storage.addFreshEntity(entity);
            cir.setReturnValue(added);
        } finally {
            if (!added) ((IplEntityLevelInvoker)entity).ipl$invokeSetLevel(source);
        }
    }
}

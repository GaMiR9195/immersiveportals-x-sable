package ipl.sable.mixin.client;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.impl.visualization.storage.BlockEntityStorage;
import dev.engine_room.flywheel.impl.visualization.storage.EntityStorage;
import dev.engine_room.flywheel.impl.visualization.storage.Storage;
import ipl.sable.render.IplFlywheelFallback;
import ipl.sable.transit.IplContraptions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flywheel never visualizes a block entity whose renderer draws it now
 * ({@link IplFlywheelFallback#isVetoed}). Flywheel re-adds block entities on its own — at every
 * section compile ({@code VisualizationHelper.tryAddBlockEntity}), at block updates, for Sable's
 * one-block bodies — so taking a visual out once is not enough: the veto holds it out until it
 * is given back. Covers additions queued before the veto too ({@code add} runs at the next frame).
 * Contraption entities handed to Create's renderer are held out the same way, and so is a
 * contraption without its structure: Create's visual of it fails every frame and takes the rest of
 * Flywheel's frame plan (other visuals stop animating) with it.
 */
@Mixin(value = Storage.class, remap = false)
public abstract class FlywheelStorageVetoMixin {

    @Inject(
        method = "add(Ldev/engine_room/flywheel/api/visualization/VisualizationContext;Ljava/lang/Object;F)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void ipl$vetoBlockEntity(VisualizationContext context, Object obj, float partialTick, CallbackInfo ci) {
        if ((Object) this instanceof BlockEntityStorage && obj instanceof BlockEntity be && IplFlywheelFallback.isVetoed(be)
            || (Object) this instanceof EntityStorage && obj instanceof Entity entity
                && (IplFlywheelFallback.isVetoed(entity) || IplContraptions.isHollow(entity))) {
            ci.cancel();
        }
    }
}

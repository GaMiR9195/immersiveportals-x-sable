package ipl.sable.mixin.client;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.impl.visualization.storage.BlockEntityStorage;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.dim.IplDimAgnostic;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Matrix4f;
import org.joml.Quaternionfc;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Patches Sable-merged methods; injection priority must exceed their merger priority. */
@Pseudo
@Mixin(value = BlockEntityStorage.class, remap = false, priority = 1100)
public abstract class IplHostedFlywheelStorageMixin {
    @Dynamic("Merged by Sable BlockEntityStorageMixin")
    @Inject(method = "sable$preFlywheelFrame", at = @At("HEAD"), require = 1)
    private void ipl$retireOrphanedVisuals(CallbackInfo ci) {
        // Inspect the actual storage: visuals created through stock callbacks may
        // never have been registered in our queue bookkeeping. This runs before
        // Flywheel starts executing frame plans, not on an arbitrary client tick.
        var visuals = ((IplFlywheelStorageAccessor)this).ipl$visuals();
        for (Object key : new java.util.ArrayList<>(visuals.keySet())) {
            if (key instanceof BlockEntity be && be.getLevel() != null
                && IplDimAgnostic.isHostingLevel(be.getLevel())
                && !ipl.sable.client.IplHostedBlockEntityLiveness.isLive(be)) {
                ((BlockEntityStorage)(Object)this).remove(be);
            }
        }
    }

    @Inject(method="willAccept(Lnet/minecraft/world/level/block/entity/BlockEntity;)Z",
        at=@At("HEAD"), cancellable=true, require=1)
    private void ipl$rejectStaleIdentity(BlockEntity be,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (be.getLevel()!=null && IplDimAgnostic.isHostingLevel(be.getLevel())
            && !ipl.sable.client.IplHostedBlockEntityLiveness.isLive(be)) cir.setReturnValue(false);
    }

    @Shadow @Final private Long2ObjectMap<BlockEntityVisual<?>> posLookup;

    @Inject(method = "remove(Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
        at = @At("HEAD"), require = 1)
    private void ipl$retireLookup(BlockEntity be, CallbackInfo ci) {
        // Sable overrides remove and calls Storage.remove directly, skipping Flywheel's
        // position index. Never evict a newer BE visual at the same position.
        Object visual = ((IplFlywheelStorageAccessor)this).ipl$visuals().get(be);
        long pos = be.getBlockPos().asLong();
        if (visual != null && posLookup.get(pos) == visual) posLookup.remove(pos);
    }

    @Dynamic("Merged by Sable BlockEntityStorageMixin")
    @WrapOperation(method = "sable$onEmbeddingRemoved", at = @At(value = "INVOKE",
        target = "Ldev/engine_room/flywheel/api/visualization/VisualizationManager;get(Lnet/minecraft/world/level/LevelAccessor;)Ldev/engine_room/flywheel/api/visualization/VisualizationManager;"), require = 1)
    private VisualizationManager ipl$retireFromActualManager(LevelAccessor level, Operation<VisualizationManager> original,
            @Local(argsOnly = true) ClientSubLevel sub) {
        Level parent = IplDimAgnostic.getParentLevel(sub);
        return original.call(level instanceof Level world && IplDimAgnostic.isHostingLevel(world) && parent != null ? parent : level);
    }

    @Dynamic("Merged by Sable BlockEntityStorageMixin")
    @WrapOperation(method = "sable$updateEmbeddingTransforms", at = @At(value = "INVOKE",
        target = "Lorg/joml/Matrix4f;rotate(Lorg/joml/Quaternionfc;)Lorg/joml/Matrix4f;"), require = 1)
    private Matrix4f ipl$includeHostedScale(Matrix4f matrix, Quaternionfc orientation, Operation<Matrix4f> original,
            @Local(argsOnly = true) ClientSubLevel sub) {
        Matrix4f result = original.call(matrix, orientation);
        if (IplDimAgnostic.isHostingLevel(sub.getLevel())) {
            var scale = sub.renderPose().scale();
            result.scale((float)scale.x(), (float)scale.y(), (float)scale.z());
        }
        return result;
    }
    @WrapOperation(method = "recreateAll", at = @At(value = "INVOKE", target = "Ljava/util/Map;clear()V"), require = 1)
    private void ipl$deleteOldEmbeddings(java.util.Map<?,?> map, Operation<Void> original) {
        for (Object entry : map.values()) {
            if (entry instanceof dev.ryanhcode.sable.neoforge.mixinhelper.compatibility.flywheel.SubLevelEmbedding info) {
                info.embedding().delete();
            }
        }
        original.call(map);
    }

}

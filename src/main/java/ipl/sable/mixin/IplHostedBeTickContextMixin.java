package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ipl.sable.dim.IplWorldFrameContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Establish the world-frame context around every plot-chunk block entity tick on the
 * hosting level — BOTH sides.
 *
 * <p>SERVER ({@link IplWorldFrameContext}): drills, deployers, saws and harvesters do their
 * cross-frame terrain work from inside their BE tick (directly or via Sable's compat
 * redirects), addressing parent-frame world coordinates through {@code this.level} — the
 * hosting dimension. The context resolved here (plot → owner sub-level → parent) lets
 * {@code IplHostedWorldFrameRouterMixin} forward that access to the dimension the ship is
 * actually in.
 *
 * <p>CLIENT ({@link ipl.sable.dim.IplClientWorldFrameContext}): IP's remote-world ticking
 * runs hosted plot BE ticks on the hosting {@code ClientLevel} (wheel-mount visual
 * suspension probes, rope strand registration, Create behaviors). The client context lets
 * {@code IplHostedClientWorldFrameRouterMixin} forward their world-frame READS to the
 * parent the same way.
 *
 * <p>require = 1: this is a vanilla single-call-site target; if it ever stops applying we
 * want a load-time failure, not silently dead drills.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class IplHostedBeTickContextMixin {

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/BlockEntityTicker;tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;)V"
        ),
        require = 1
    )
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void ipl$tickWithParentFrameContext(
        BlockEntityTicker ticker, Level level, BlockPos pos, BlockState state,
        BlockEntity blockEntity, Operation<Void> original
    ) {
        if (level.isClientSide()) {
            // Client-only helper class: common-typed signature, loaded only on this branch.
            ipl.sable.client.IplClientBeTickArming.tickArmed(ticker, level, pos, state, blockEntity, original);
            return;
        }
        ServerLevel parent = IplWorldFrameContext.resolveParentForPlotBe(level, pos);
        if (parent == null) {
            original.call(ticker, level, pos, state, blockEntity);
            return;
        }
        ServerLevel prev = IplWorldFrameContext.push(parent);
        try {
            original.call(ticker, level, pos, state, blockEntity);
        } finally {
            IplWorldFrameContext.pop(prev);
        }
    }
}

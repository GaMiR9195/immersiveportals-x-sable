package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import ipl.sable.dim.IplWorldFrameContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Arm the world-frame context around block-USE interactions on hosted ships.
 *
 * <p>The BE-tick and physics-actor arming sites cover code that runs from ticks — but a
 * right-click handler runs synchronously from the interaction packet: Simulated's physics
 * assembler {@code placeIntoWorld} (ground checks, {@code getChunk}, build-height reads,
 * disassembly block placement — all via {@code this.getLevel()} = the hosting void), the
 * swivel bearing's split-into-physics-body action, throttle grips. With the context armed
 * for the click, {@code IplHostedWorldFrameRouterMixin} routes every world-frame access of
 * those handlers to the ship's actual dimension — structurally, for every mod. This is what
 * replaces the per-mod assembler mixin.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class IplHostedInteractionContextMixin {

    @org.spongepowered.asm.mixin.Unique
    private static long ipl$lastArmLogMs = 0;

    @org.spongepowered.asm.mixin.Unique
    private static long ipl$lastMissLogMs = 0;

    @WrapMethod(method = "useItemOn")
    private InteractionResult ipl$armWorldFrameForHostedUse(
        ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
        BlockHitResult hitResult, Operation<InteractionResult> original
    ) {
        ServerLevel parent = level instanceof ServerLevel serverLevel
            ? IplWorldFrameContext.resolveParentForPlotInteraction(serverLevel, hitResult.getBlockPos())
            : null;
        if (parent == null) {
            // Diagnostic: a plot-range click that fails to resolve means the arming is
            // dead for this interaction shape — log throttled so a runtime trace shows it.
            net.minecraft.core.BlockPos pos = hitResult.getBlockPos();
            if ((Math.abs(pos.getX()) >= 1_000_000 || Math.abs(pos.getZ()) >= 1_000_000)) {
                long now = System.currentTimeMillis();
                if (now - ipl$lastMissLogMs > 2000) {
                    ipl$lastMissLogMs = now;
                    org.slf4j.LoggerFactory.getLogger("ipl-hosted-use").warn(
                        "[IPL-USE] plot-range click at {} did NOT resolve a hosted parent (arming skipped)", pos);
                }
            }
            return original.call(player, level, stack, hand, hitResult);
        }
        long now = System.currentTimeMillis();
        if (now - ipl$lastArmLogMs > 2000) {
            ipl$lastArmLogMs = now;
            org.slf4j.LoggerFactory.getLogger("ipl-hosted-use").info(
                "[IPL-USE] armed world-frame {} for hosted ship click at {}",
                parent.dimension().location(), hitResult.getBlockPos());
        }
        ServerLevel prev = IplWorldFrameContext.push(parent);
        try {
            return original.call(player, level, stack, hand, hitResult);
        } finally {
            IplWorldFrameContext.pop(prev);
        }
    }
}

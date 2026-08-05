package ipl.sable.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DIAGNOSTIC: every {@code resetPlunged} with the world-read inputs that justified it.
 * The ship-end plunger's client copy renders unattached (drooping rope spline) while the
 * server stays plunged — the suspicion is the client-side air-check at PLUNGED_BLOCK_POS
 * (plot coords) reading loaded-but-air through the hosted chunk bridge and locally
 * clearing IS_PLUNGED. On paper the bridge can't answer that (missing chunk → not
 * loaded → short-circuit), so this logs, at reset time: side, the plunged pos, what
 * isLoaded/getBlockState actually returned, and the caller stack. If CLIENT resets appear
 * with loaded=true state=air, the bridge has a real gap to fix; if no CLIENT resets
 * appear, the connect state is intact and the spline bug is in the renderer.
 */
@Pseudo
@Mixin(
    targets = "dev.simulated_team.simulated.content.entities.launched_plunger.LaunchedPlungerEntity",
    remap = false)
public abstract class IplPlungerResetProbeMixin {

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-plunger-reset");

    @Unique
    private static volatile EntityDataAccessor<BlockPos> ipl$plungedPos;

    @Unique
    private static volatile boolean ipl$reflectionFailed;

    @SuppressWarnings("unchecked")
    @Unique
    private boolean ipl$resolveAccessor() {
        if (ipl$reflectionFailed) return false;
        if (ipl$plungedPos != null) return true;
        try {
            Class<?> cls = Class.forName(
                "dev.simulated_team.simulated.content.entities.launched_plunger.LaunchedPlungerEntity",
                false, this.getClass().getClassLoader());
            ipl$plungedPos = (EntityDataAccessor<BlockPos>)
                cls.getField("PLUNGED_BLOCK_POS").get(null);
            return true;
        } catch (ReflectiveOperationException | ClassCastException e) {
            ipl$reflectionFailed = true;
            IPL$LOG.warn("[IPL-PLUNGER-RESET] accessor reflection failed", e);
            return false;
        }
    }

    @Inject(method = "resetPlunged", at = @At("HEAD"), require = 0)
    private void ipl$logReset(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        Level level = self.level();

        String plungedPos = "?";
        String loaded = "?";
        String state = "?";
        if (ipl$resolveAccessor()) {
            BlockPos pos = self.getEntityData().get(ipl$plungedPos);
            plungedPos = pos.toShortString();
            boolean isLoaded = level.isLoaded(pos);
            loaded = String.valueOf(isLoaded);
            state = isLoaded ? level.getBlockState(pos).toString() : "(unloaded)";
        }

        StringBuilder stack = new StringBuilder();
        StackTraceElement[] frames = new Throwable().getStackTrace();
        for (int i = 1; i < Math.min(frames.length, 8); i++) {
            stack.append("\n    at ").append(frames[i]);
        }
        IPL$LOG.info("[IPL-PLUNGER-RESET] side={} id={} at=({}, {}, {}) plungedPos=({}) "
            + "loaded={} state={}{}",
            level.isClientSide ? "CLIENT" : "SERVER", self.getId(),
            String.format("%.1f", self.getX()), String.format("%.1f", self.getY()),
            String.format("%.1f", self.getZ()),
            plungedPos, loaded, state, stack);
    }
}

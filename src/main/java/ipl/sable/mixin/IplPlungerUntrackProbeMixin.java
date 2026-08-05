package ipl.sable.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DIAGNOSTIC: server-side tracking unpair for plunger entities. Companion to
 * {@code IplPlungerRemovalProbeMixin} — when the client removal stack points at
 * {@code handleRemoveEntities}, this names the server call site that dropped the
 * player from the tracked entity's seenBy set (chunk-untrack sweep, updatePlayer
 * rejection, section-change re-evaluation, ...).
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class IplPlungerUntrackProbeMixin {

    @Shadow
    @Final
    Entity entity;

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-plunger-untrack");

    @Inject(method = "removePlayer", at = @At("HEAD"), require = 0)
    private void ipl$logPlungerUntrack(ServerPlayer player, CallbackInfo ci) {
        if (!this.entity.getType().getDescriptionId().contains("plunger")) return;

        StringBuilder stack = new StringBuilder();
        StackTraceElement[] frames = new Throwable().getStackTrace();
        for (int i = 1; i < Math.min(frames.length, 10); i++) {
            stack.append("\n    at ").append(frames[i]);
        }
        IPL$LOG.info("[IPL-PLUNGER-UNTRACK] id={} at=({}, {}, {}) player={}{}",
            this.entity.getId(),
            String.format("%.1f", this.entity.getX()),
            String.format("%.1f", this.entity.getY()),
            String.format("%.1f", this.entity.getZ()),
            player.getGameProfile().getName(), stack);
    }
}

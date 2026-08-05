package ipl.sable.mixin;

import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DIAGNOSTIC: who removes a plunger entity, on which side. The ship-end plunger's CLIENT
 * copy vanishes within seconds of every ship attach ([IPL-PLUNGER-TARGET] "no partner"),
 * while the server keeps both ends alive. The removal stack discriminates the two
 * possible paths: a client stack through {@code ClientPacketListener.handleRemoveEntities}
 * means the SERVER untracked it (tracking-pair drop → follow up server-side), while a
 * stack through the entity's own tick means a LOCAL discard (owner/target resolution
 * failing client-side). Removals are rare — always-on, no rate limit.
 */
@Mixin(Entity.class)
public abstract class IplPlungerRemovalProbeMixin {

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-plunger-removal");

    @Inject(method = "setRemoved", at = @At("HEAD"), require = 0)
    private void ipl$logPlungerRemoval(Entity.RemovalReason reason, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!self.getType().getDescriptionId().contains("plunger")) return;

        StringBuilder stack = new StringBuilder();
        StackTraceElement[] frames = new Throwable().getStackTrace();
        for (int i = 1; i < Math.min(frames.length, 10); i++) {
            stack.append("\n    at ").append(frames[i]);
        }
        IPL$LOG.info("[IPL-PLUNGER-REMOVED] side={} id={} reason={} at=({}, {}, {}){}",
            self.level().isClientSide ? "CLIENT" : "SERVER",
            self.getId(), reason,
            String.format("%.1f", self.getX()), String.format("%.1f", self.getY()),
            String.format("%.1f", self.getZ()), stack);
    }
}

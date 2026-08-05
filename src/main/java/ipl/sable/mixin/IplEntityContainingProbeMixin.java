package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * DIAGNOSTIC: what does the entity→sub-level resolution return for plunger entities,
 * and with WHICH pose? The plunger renders at ~world origin client-side while server
 * physics is correct — the signature of the render transform projecting through a
 * ClientSubLevel whose pose never syncs (position ≈ 0 with rotationPoint ≈ plot COM
 * maps plot coords to ≈ origin). Sable's render mixin resolves that object through
 * {@code Sable.HELPER.getContaining(entity)}; this logs the resolved sub's identity
 * and pose for plunger entities, rate-limited per side.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.ActiveSableCompanion", remap = false)
public abstract class IplEntityContainingProbeMixin {

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-containing-probe");

    @Unique
    private static long ipl$lastClientLogMs = 0;

    @Unique
    private static long ipl$lastServerLogMs = 0;

    @ModifyReturnValue(
        method = "getContaining(Lnet/minecraft/world/entity/Entity;)Ldev/ryanhcode/sable/companion/SubLevelAccess;",
        at = @At("RETURN"), remap = false, require = 0)
    private SubLevelAccess ipl$probePlungerContaining(SubLevelAccess original, Entity entity) {
        if (!entity.getType().getDescriptionId().contains("plunger")) return original;
        boolean client = entity.level().isClientSide;
        long now = System.currentTimeMillis();
        if (client) {
            if (now - ipl$lastClientLogMs < 500) return original;
            ipl$lastClientLogMs = now;
        } else {
            if (now - ipl$lastServerLogMs < 500) return original;
            ipl$lastServerLogMs = now;
        }
        if (original == null) {
            IPL$LOG.info("[IPL-CONTAINING] side={} plunger id={} at=({}, {}, {}) -> NULL",
                client ? "CLIENT" : "SERVER", entity.getId(),
                String.format("%.1f", entity.getX()), String.format("%.1f", entity.getY()),
                String.format("%.1f", entity.getZ()));
            return original;
        }
        var pose = original.logicalPose();
        IPL$LOG.info("[IPL-CONTAINING] side={} plunger id={} at=({}, {}, {}) -> sub={} "
            + "class={} posePos=({}, {}, {}) rotPoint=({}, {}, {})",
            client ? "CLIENT" : "SERVER", entity.getId(),
            String.format("%.1f", entity.getX()), String.format("%.1f", entity.getY()),
            String.format("%.1f", entity.getZ()),
            original.getUniqueId(), original.getClass().getSimpleName(),
            String.format("%.1f", pose.position().x()),
            String.format("%.1f", pose.position().y()),
            String.format("%.1f", pose.position().z()),
            String.format("%.1f", pose.rotationPoint().x()),
            String.format("%.1f", pose.rotationPoint().y()),
            String.format("%.1f", pose.rotationPoint().z()));
        return original;
    }
}

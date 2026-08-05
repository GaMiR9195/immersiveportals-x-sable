package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.ryanhcode.sable.sublevel.SubLevel;
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

    /** Rate-limit per (entity, side) — a per-side limit lets the lowest-id plunger
     * monopolize the budget and hide every other plunger from the log entirely. */
    @Unique
    private static final java.util.Map<Long, Long> IPL$LAST_LOG_MS =
        new java.util.concurrent.ConcurrentHashMap<>();

    @ModifyReturnValue(
        method = "getContaining(Lnet/minecraft/world/entity/Entity;)Ldev/ryanhcode/sable/sublevel/SubLevel;",
        at = @At("RETURN"), remap = false, require = 0)
    private SubLevel ipl$probePlungerContaining(SubLevel original, Entity entity) {
        if (!entity.getType().getDescriptionId().contains("plunger")) return original;
        boolean client = entity.level().isClientSide;
        long now = System.currentTimeMillis();
        long key = ((long) entity.getId() << 1) | (client ? 1 : 0);
        Long last = IPL$LAST_LOG_MS.get(key);
        if (last != null && now - last < 500) return original;
        IPL$LAST_LOG_MS.put(key, now);
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

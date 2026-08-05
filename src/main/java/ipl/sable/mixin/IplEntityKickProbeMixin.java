package ipl.sable.mixin;

import dev.ryanhcode.sable.api.entity.EntitySubLevelUtil;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DIAGNOSTIC: name the caller and the pose whenever a plot-space entity is kicked out
 * of its sub-level. The plunger-to-origin teleport has the signature of
 * {@code kickEntity} projecting through a DEFAULT pose (position 0,0,0 maps a plot
 * point to ≈ plotPos − rotationPoint ≈ world origin) — this logs every kick with the
 * sub's pose and a caller stack so one reproduction identifies the misfiring site.
 * Kicks are rare (detach events), so always-on is free.
 */
@Pseudo
@Mixin(value = EntitySubLevelUtil.class, remap = false)
public abstract class IplEntityKickProbeMixin {

    @org.spongepowered.asm.mixin.Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-kick-probe");

    @Inject(method = "kickEntity", at = @At("HEAD"), remap = false, require = 0)
    private static void ipl$logKick(SubLevel subLevel, Entity entity, CallbackInfo ci) {
        var pose = subLevel.logicalPose();
        StringBuilder stack = new StringBuilder();
        StackTraceElement[] frames = new Throwable().getStackTrace();
        for (int i = 1; i < Math.min(frames.length, 7); i++) {
            stack.append("\n    at ").append(frames[i]);
        }
        IPL$LOG.info("[IPL-KICK] side={} entity={} id={} at=({}, {}, {}) sub={} removed={} "
            + "posePos=({}, {}, {}) rotPoint=({}, {}, {}) scale={}{}",
            entity.level().isClientSide ? "CLIENT" : "SERVER",
            entity.getType().getDescriptionId(), entity.getId(),
            String.format("%.1f", entity.getX()), String.format("%.1f", entity.getY()),
            String.format("%.1f", entity.getZ()),
            subLevel.getUniqueId(), subLevel.isRemoved(),
            String.format("%.1f", pose.position().x()),
            String.format("%.1f", pose.position().y()),
            String.format("%.1f", pose.position().z()),
            String.format("%.1f", pose.rotationPoint().x()),
            String.format("%.1f", pose.rotationPoint().y()),
            String.format("%.1f", pose.rotationPoint().z()),
            pose.scale(), stack);
    }
}

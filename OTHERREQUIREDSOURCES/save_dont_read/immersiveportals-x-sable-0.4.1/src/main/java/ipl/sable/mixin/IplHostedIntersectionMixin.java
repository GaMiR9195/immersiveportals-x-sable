package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.client.IplClientHostedLookup;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.SableSubLevelDimension;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;

/**
 * Splice hosted sub-levels into Sable's central spatial-query API.
 *
 * <p>Everything that asks "which airships are HERE?" — entity/sub-level collision, raycasts,
 * camera attachment, seat mounting, game tests — funnels through
 * {@code Sable.HELPER.getAllIntersecting(level, bounds)}, which delegates to the per-level
 * physics system / container. A hosted sub-level is registered in the
 * {@code ipl_sable:sublevels} container, so a query against its PARENT level (where the
 * airship visually exists and entities walk on it) would not find it. This mixin appends
 * hosted sub-levels whose {@code parentLevel} matches the queried level and whose world
 * bounding box (parent-frame coordinates) intersects the query bounds.
 *
 * <p>Also fixes {@code getVelocity(level, subLevel, ...)}: it resolves the physics handle via
 * the queried level's container, but a hosted sub-level's Rapier body lives in the hosting
 * container's pipeline — resolve via the sub-level's own level instead.
 */
@Pseudo
@Mixin(value = ActiveSableCompanion.class, remap = false)
public abstract class IplHostedIntersectionMixin {

    @ModifyReturnValue(method = "getAllIntersecting", at = @At("RETURN"), require = 1)
    private Iterable<SubLevel> ipl$includeHostedSubLevels(
        Iterable<SubLevel> original, Level level, BoundingBox3dc bounds
    ) {
        if (!IplDimAgnostic.isEnabled() || IplDimAgnostic.isHostingLevel(level)) {
            return original;
        }

        SubLevelContainer hostingContainer = ipl$resolveHostingContainer(level);
        if (hostingContainer == null) {
            return original;
        }

        List<SubLevel> extra = null;
        for (SubLevel sub : hostingContainer.getAllSubLevels()) {
            if (sub.isRemoved()) continue;

            if (IplDimAgnostic.getParentLevel(sub) != level) {
                // Foreign ship straddling INTO this dimension: include it when its
                // portal-MAPPED bounds intersect the query. Pose mapping for the collision
                // math happens in IplStraddleCollisionPoseMixin.
                net.minecraft.core.BlockPos offset =
                    ipl.sable.transit.IplStraddlePoseMap.getOffsetInto(sub, level);
                if (offset == null) continue;
                dev.ryanhcode.sable.companion.math.BoundingBox3d mapped =
                    new dev.ryanhcode.sable.companion.math.BoundingBox3d();
                mapped.set(sub.boundingBox());
                mapped.move(offset.getX(), offset.getY(), offset.getZ());
                if (!mapped.intersects(bounds)) continue;
                if (extra == null) extra = new ArrayList<>(4);
                extra.add(sub);
                continue;
            }

            if (!sub.boundingBox().intersects(bounds)) continue;
            if (extra == null) extra = new ArrayList<>(4);
            extra.add(sub);
        }
        if (extra == null) {
            return original;
        }

        List<SubLevel> combined = new ArrayList<>(extra.size() + 4);
        for (SubLevel sub : original) combined.add(sub);
        combined.addAll(extra);
        return combined;
    }

    /** Hosting container on the matching side, without creating client worlds in a hot path. */
    private static SubLevelContainer ipl$resolveHostingContainer(Level queriedLevel) {
        if (queriedLevel instanceof ServerLevel serverLevel) {
            ServerLevel hosting =
                SableSubLevelDimension.getSableSubLevelsOrNull(serverLevel.getServer());
            return hosting == null ? null : SubLevelContainer.getContainer((Level) hosting);
        }
        if (queriedLevel.isClientSide) {
            // Client-only class; only loaded when this branch actually executes.
            return IplClientHostedLookup.getHostingContainerOrNull();
        }
        return null;
    }

    /**
     * THE projection keystone: {@code projectOutOfSubLevel} maps plot-space positions back
     * to world space and feeds every frame-aware distance in Sable —
     * {@code distanceSquaredWithSubLevels} (all overloads), and through it
     * {@code GameRenderer.pick}'s reach clamp and {@code filterHitResult}. With the
     * UNMAPPED pose, a hit on a foreign straddler's through-part projects to the
     * SOURCE-frame world position: the reach clamp sees a huge distance and discards the
     * pick (no outline, no break; the staff locked "far below" because its beam used the
     * same wrong projection). Substituting the portal-mapped pose for foreign straddlers
     * fixes the whole family at once. Both pose sources are wrapped: the level pose
     * provider (client) and the {@code logicalPose()} fallback (server).
     */
    @WrapOperation(
        method = "projectOutOfSubLevel",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/mixinterface/clip_overwrite/LevelPoseProviderExtension;sable$getPose(Ldev/ryanhcode/sable/sublevel/SubLevel;)Ldev/ryanhcode/sable/companion/math/Pose3dc;"
        ),
        require = 0
    )
    private dev.ryanhcode.sable.companion.math.Pose3dc ipl$mapProjectionPoseClient(
        dev.ryanhcode.sable.mixinterface.clip_overwrite.LevelPoseProviderExtension ext,
        SubLevel sub,
        Operation<dev.ryanhcode.sable.companion.math.Pose3dc> original,
        @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) Level level
    ) {
        dev.ryanhcode.sable.companion.math.Pose3dc pose = original.call(ext, sub);
        net.minecraft.core.BlockPos offset =
            ipl.sable.transit.IplStraddlePoseMap.getOffsetInto(sub, level);
        return offset != null ? ipl.sable.transit.IplStraddlePoseMap.mapped(pose, offset) : pose;
    }

    @WrapOperation(
        method = "projectOutOfSubLevel",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/SubLevel;logicalPose()Ldev/ryanhcode/sable/companion/math/Pose3d;"
        ),
        require = 0
    )
    private dev.ryanhcode.sable.companion.math.Pose3d ipl$mapProjectionPoseFallback(
        SubLevel sub,
        Operation<dev.ryanhcode.sable.companion.math.Pose3d> original,
        @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) Level level
    ) {
        dev.ryanhcode.sable.companion.math.Pose3d pose = original.call(sub);
        net.minecraft.core.BlockPos offset =
            ipl.sable.transit.IplStraddlePoseMap.getOffsetInto(sub, level);
        return offset != null ? ipl.sable.transit.IplStraddlePoseMap.mapped(pose, offset) : pose;
    }

    @WrapOperation(
        method = "getVelocity(Lnet/minecraft/world/level/Level;Ldev/ryanhcode/sable/companion/SubLevelAccess;Lorg/joml/Vector3dc;Lorg/joml/Vector3d;)Lorg/joml/Vector3d;",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;getContainer(Lnet/minecraft/server/level/ServerLevel;)Ldev/ryanhcode/sable/api/sublevel/ServerSubLevelContainer;"
        ),
        require = 0
    )
    private ServerSubLevelContainer ipl$velocityFromOwningContainer(
        ServerLevel level, Operation<ServerSubLevelContainer> original,
        @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) SubLevelAccess subLevel
    ) {
        if (IplDimAgnostic.isEnabled()
            && subLevel instanceof ServerSubLevel serverSubLevel
            && IplDimAgnostic.isHosted(serverSubLevel)) {
            return original.call(serverSubLevel.getLevel());
        }
        return original.call(level);
    }
}

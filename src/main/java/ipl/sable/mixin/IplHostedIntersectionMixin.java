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
            if (IplDimAgnostic.getParentLevel(sub) != level) continue;
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

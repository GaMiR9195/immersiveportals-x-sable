package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.IplPerfDiagnostics;
import ipl.sable.atlas.IplScenes;
import ipl.sable.client.IplClientProjections;
import ipl.sable.client.IplParentDimSync;
import ipl.sable.transit.IplAtlasStraddleSession;
import ipl.sable.transit.IplStraddlePoseMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Splices portal IMAGES of ships into Sable's central spatial-query API.
 *
 * <p>Everything that asks "which ships are HERE?" — entity/sub-level collision, raycasts,
 * camera attachment, seat mounting — funnels through
 * {@code Sable.HELPER.getAllIntersecting(level, bounds)}, which only knows the queried
 * level's own ships at their own poses. A ship straddling a portal also exists, as a
 * projection, beyond the destination plane — in another dimension, or elsewhere in the same
 * one. This mixin appends every ship with a session image into the queried level whose
 * MAPPED bounds intersect the query; pose mapping for the collision math itself happens in
 * {@code SubLevelEntityCollisionMixin}. Client-side, visual-only ghosts left behind by a
 * dimension change are removed from the result.
 *
 * <p>Also fixes {@code getVelocity(level, subLevel, ...)}: it resolves the physics handle via
 * the queried level's container, while a projected ship's body lives in its own level.
 */
@Pseudo
@Mixin(value = ActiveSableCompanion.class, remap = false)
public abstract class CompanionIntersectMixin {

    @ModifyReturnValue(method = "getAllIntersecting", at = @At("RETURN"), require = 1)
    private Iterable<SubLevel> ipl$includeProjectedSubLevels(
        Iterable<SubLevel> original, Level level, BoundingBox3dc bounds
    ) {
        if (!IplPerfDiagnostics.ENABLED) return ipl$splice(original, level, bounds);
        long start = System.nanoTime();
        try {
            return ipl$splice(original, level, bounds);
        } finally {
            IplPerfDiagnostics.record(level.isClientSide
                ? IplPerfDiagnostics.CLIENT_SPLICE : IplPerfDiagnostics.SERVER_SPLICE, System.nanoTime() - start);
        }
    }

    /** Runs on EVERY Sable spatial query (particles, collisions, raycasts, entity lookups). */
    @Unique
    private static Iterable<SubLevel> ipl$splice(Iterable<SubLevel> original, Level level, BoundingBox3dc bounds) {
        List<SubLevel> extra = null;
        boolean dropGhosts = false;
        if (level instanceof ServerLevel serverLevel) {
            if (IplAtlasStraddleSession.isIdle()) return original;
            // Ships with at least one image INTO this level, in session order (precomputed per
            // session-set change): no key copy or set per query, and ships whose images all
            // live in other levels are not even looked up.
            for (UUID shipId : IplAtlasStraddleSession.shipsWithImagesInto(serverLevel)) {
                ServerSubLevel sub = IplScenes.findSubLevel(serverLevel.getServer(), shipId);
                if (sub == null || !ipl$imageHits(sub, level, bounds)) continue;
                if (extra == null) extra = new ArrayList<>(2);
                extra.add(sub);
            }
        } else if (level.isClientSide) {
            // Client-only classes; only loaded when this branch actually executes.
            extra = IplClientProjections.intersectingImages(level, bounds);
            dropGhosts = IplParentDimSync.hasGhosts();
        }
        if (extra == null && !dropGhosts) return original;

        List<SubLevel> combined = new ArrayList<>(4);
        for (SubLevel sub : original) {
            if (dropGhosts && sub instanceof ClientSubLevel client
                && IplParentDimSync.isGhost(client)) continue;
            combined.add(sub);
        }
        if (extra != null) {
            for (SubLevel sub : extra) if (!combined.contains(sub)) combined.add(sub);
        }
        return combined;
    }

    @Unique
    private static boolean ipl$imageHits(SubLevel sub, Level level, BoundingBox3dc bounds) {
        boolean[] hit = {false};
        IplStraddlePoseMap.forEachStraddleInto(sub, level, (portal, mapping) -> {
            if (!hit[0] && mapping.mappedIntersects(sub.boundingBox(), bounds)) hit[0] = true;
        });
        return hit[0];
    }

    /*
     * projectOutOfSubLevel здесь не оборачивается: он всегда считает родную позу, а
     * CompanionProjectOutMixin (RETURN) переводит результат в кадр того куска, которому
     * принадлежит точка (IplProjectionTree.worldPointIn) — одна истина для пика, досягаемости
     * и звуков. Подмена позы «первым отображением в уровень» дала бы неверную точку для
     * нескольких порталов и цепочек: кусок корабля может быть за ДРУГИМ порталом.
     */

    @WrapOperation(
        method = "getVelocity(Lnet/minecraft/world/level/Level;Ldev/ryanhcode/sable/companion/SubLevelAccess;Lorg/joml/Vector3dc;Lorg/joml/Vector3d;)Lorg/joml/Vector3d;",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;getContainer(Lnet/minecraft/server/level/ServerLevel;)Ldev/ryanhcode/sable/api/sublevel/ServerSubLevelContainer;"
        )
    )
    private ServerSubLevelContainer ipl$velocityFromOwningContainer(
        ServerLevel level, Operation<ServerSubLevelContainer> original,
        @Local(argsOnly = true) SubLevelAccess subLevel
    ) {
        if (subLevel instanceof ServerSubLevel serverSubLevel && serverSubLevel.getLevel() != level) {
            return original.call(serverSubLevel.getLevel());
        }
        return original.call(level);
    }
}

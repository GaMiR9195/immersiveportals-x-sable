package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.atlas.IplScenes;
import ipl.sable.transit.IplAtlasStraddleSession;
import ipl.sable.transit.IplStraddlePoseMap;
import ipl.sable.transit.StraddleKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * {@code IplStraddleCollisionPoseMixin}. Client-side, visual-only ghosts left behind by a
 * dimension change are removed from the result.
 *
 * <p>Also fixes {@code getVelocity(level, subLevel, ...)}: it resolves the physics handle via
 * the queried level's container, while a projected ship's body lives in its own level.
 */
@Pseudo
@Mixin(value = ActiveSableCompanion.class, remap = false)
public abstract class IplProjectionIntersectionMixin {

    @ModifyReturnValue(method = "getAllIntersecting", at = @At("RETURN"), require = 1)
    private Iterable<SubLevel> ipl$includeProjectedSubLevels(
        Iterable<SubLevel> original, Level level, BoundingBox3dc bounds
    ) {
        List<SubLevel> extra = null;
        boolean dropGhosts = false;
        if (level instanceof ServerLevel serverLevel) {
            if (IplAtlasStraddleSession.isIdle()) return original;
            Set<UUID> seen = new HashSet<>();
            for (StraddleKey key : IplAtlasStraddleSession.sessionKeys()) {
                if (!seen.add(key.subLevelUuid())) continue;
                ServerSubLevel sub = IplScenes.findSubLevel(serverLevel.getServer(), key.subLevelUuid());
                if (sub == null || !ipl$imageHits(sub, level, bounds)) continue;
                if (extra == null) extra = new ArrayList<>(2);
                extra.add(sub);
            }
        } else if (level.isClientSide) {
            // Client-only classes; only loaded when this branch actually executes.
            extra = ipl.sable.client.IplClientProjections.intersectingImages(level, bounds);
            dropGhosts = ipl.sable.client.IplParentDimSync.hasGhosts();
        }
        if (extra == null && !dropGhosts) return original;

        List<SubLevel> combined = new ArrayList<>(4);
        for (SubLevel sub : original) {
            if (dropGhosts && sub instanceof dev.ryanhcode.sable.sublevel.ClientSubLevel client
                && ipl.sable.client.IplParentDimSync.isGhost(client)) continue;
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
            if (!hit[0] && mapping.mapAabb(sub.boundingBox()).intersects(bounds)) hit[0] = true;
        });
        return hit[0];
    }

    /*
     * Русский комментарий: здесь раньше были две обёртки позы в projectOutOfSubLevel
     * ("первое отображение в уровень" для чужих кораблей). Для нескольких порталов и цепочек
     * это давало неверную точку: кусок корабля мог быть за ДРУГИМ порталом. Теперь
     * projectOutOfSubLevel всегда считает родную позу, а IplStraddleProjectOutMixin
     * (RETURN) переводит результат в кадр того куска, которому принадлежит точка
     * (IplProjectionTree.worldPointIn) — одна истина для пика, досягаемости и звуков.
     */

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
        if (subLevel instanceof ServerSubLevel serverSubLevel
            && serverSubLevel.getLevel() instanceof ServerLevel own && own != level) {
            return original.call(own);
        }
        return original.call(level);
    }
}

package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.util.SubLevelInclusiveLevelEntityGetter;
import ipl.sable.transit.IplProjectionEntities;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Piece-aware entity lookup for plot boxes of straddling ships (pressure plates, tripwires,
 * buttons pressed by arrows, hoppers, Create machines, ...).
 *
 * <p>Sable's {@link SubLevelInclusiveLevelEntityGetter} maps a plot-space query box to the
 * world with the ship's NATIVE pose and searches only the queried level. A block of a
 * straddling ship that lives in a portal image (other side of a same-dimension portal, or
 * another dimension) therefore never saw the entities standing on it. The HEAD hooks hand
 * such queries to {@link IplProjectionEntities}, which searches every piece the box touches
 * in the level that piece lives in. The loop hooks fix Sable's "other intersecting ships"
 * pass for projected ships (wrong pose and wrong level for their plot-space entities).
 *
 * <p>Русский комментарий: перехватываем только запросы, где это действительно нужно
 * (корабль с живыми сессиями или чужой образ); обычные корабли идут стоковым путём, так
 * что оптимизации других модов (Pocket обрезает тот же цикл) продолжают работать.
 */
@Pseudo
@Mixin(value = SubLevelInclusiveLevelEntityGetter.class, remap = false)
public abstract class IplProjectionEntityGetterMixin {

    @Shadow
    @Final
    private Level level;

    @Shadow
    @Final
    private LevelEntityGetter<?> delegate;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(
        method = "get(Lnet/minecraft/world/phys/AABB;Ljava/util/function/Consumer;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void ipl$pieceAwarePlotQuery(AABB box, Consumer consumer, CallbackInfo ci) {
        if (IplProjectionEntities.plotQuery(this.level, (LevelEntityGetter<Entity>) (Object) this.delegate, box,
            (Consumer<Entity>) (Object) consumer)) {
            ci.cancel();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(
        method = "get(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void ipl$pieceAwareTypedPlotQuery(EntityTypeTest test, AABB box, AbortableIterationConsumer consumer,
                                              CallbackInfo ci) {
        if (IplProjectionEntities.typedPlotQuery(this.level, (LevelEntityGetter<Entity>) (Object) this.delegate,
            (EntityTypeTest<Entity, Entity>) test, box, (AbortableIterationConsumer<Entity>) consumer)) {
            ci.cancel();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @WrapOperation(
        method = "get(Lnet/minecraft/world/phys/AABB;Ljava/util/function/Consumer;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/LevelEntityGetter;get(Lnet/minecraft/world/phys/AABB;Ljava/util/function/Consumer;)V",
            ordinal = 2
        ),
        require = 0
    )
    private void ipl$pieceAwareOtherShip(LevelEntityGetter getter, AABB localBounds, Consumer consumer,
                                         Operation<Void> original,
                                         @Local(ordinal = 1) SubLevel other,
                                         @Local(argsOnly = true) AABB worldBox) {
        if (!IplProjectionEntities.otherShipQuery(this.level, other, worldBox, (Consumer<Entity>) (Object) consumer)) {
            original.call(getter, localBounds, consumer);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @WrapOperation(
        method = "get(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/LevelEntityGetter;get(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V",
            ordinal = 2
        ),
        require = 0
    )
    private void ipl$pieceAwareTypedOtherShip(LevelEntityGetter getter, EntityTypeTest test, AABB localBounds,
                                              AbortableIterationConsumer consumer, Operation<Void> original,
                                              @Local(ordinal = 1) SubLevel other,
                                              @Local(argsOnly = true) AABB worldBox) {
        if (!IplProjectionEntities.typedOtherShipQuery(this.level, other, worldBox,
            (EntityTypeTest<Entity, Entity>) test, (AbortableIterationConsumer<Entity>) consumer)) {
            original.call(getter, test, localBounds, consumer);
        }
    }
}

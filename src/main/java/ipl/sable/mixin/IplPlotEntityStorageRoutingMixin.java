package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.util.SubLevelInclusiveLevelEntityGetter;
import ipl.sable.dim.IplPlotEntityStorage;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Consumer;

/**
 * Teaches Sable's {@link SubLevelInclusiveLevelEntityGetter} that plot storage and world
 * space may be different levels (see {@link IplPlotEntityStorage}).
 *
 * <p>The getter answers a spatial query in three parts; each part is now addressed to the
 * storage that actually holds those entities instead of always {@code this.level}:
 * <ol>
 *   <li>the query's own bounds — additionally read from the containing sub-level's real
 *       storage (a parent-level query at plot coordinates of a hosted ship), or from the
 *       armed world for a world-frame query issued on the hosting level;</li>
 *   <li>the containing sub-level's world-frame bounds — read from the level the sub-level
 *       flies through (its parent), not from the void hosting level;</li>
 *   <li>the local bounds of every intersecting sub-level — the intersecting set is taken
 *       in that same world level and each sub-level's plot is read from its own storage.</li>
 * </ol>
 * This is what makes plot-resident entities (Honey Glue, Super Glue, seats, item entities,
 * contraption entities, ...) visible to every mod that asks the parent world "which entities
 * are here?", and world entities visible to hosted ship code, with no per-mod patches.
 * Receiver swaps are done on the call's own {@link Operation}, so other mods' wrappers of the
 * same calls (e.g. Pocket Sized' plot-bounds clipping) still apply.
 */
@Mixin(value = SubLevelInclusiveLevelEntityGetter.class, remap = false)
public abstract class IplPlotEntityStorageRoutingMixin {

    @Shadow
    @Final
    private Level level;

    // ------------------------------------------------------------------
    // get(AABB, Consumer)
    // ------------------------------------------------------------------

    @WrapOperation(
        method = "get(Lnet/minecraft/world/phys/AABB;Ljava/util/function/Consumer;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/LevelEntityGetter;get(Lnet/minecraft/world/phys/AABB;Ljava/util/function/Consumer;)V",
            ordinal = 0),
        require = 1
    )
    private void ipl$ownBoundsStorage(
        LevelEntityGetter<?> delegate, AABB bounds, Consumer<?> consumer,
        Operation<Void> original, @Local(ordinal = 0) SubLevel containing
    ) {
        original.call(delegate, bounds, consumer);
        Level extra = IplPlotEntityStorage.additionalPrimaryStorage(this.level, containing);
        if (extra != null) original.call(IplPlotEntityStorage.rawGetter(extra), bounds, consumer);
    }

    @WrapOperation(
        method = "get(Lnet/minecraft/world/phys/AABB;Ljava/util/function/Consumer;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/LevelEntityGetter;get(Lnet/minecraft/world/phys/AABB;Ljava/util/function/Consumer;)V",
            ordinal = 1),
        require = 1
    )
    private void ipl$worldFrameStorage(
        LevelEntityGetter<?> delegate, AABB bounds, Consumer<?> consumer,
        Operation<Void> original, @Local(ordinal = 0) SubLevel containing
    ) {
        Level world = IplPlotEntityStorage.worldLevel(this.level, containing);
        original.call(world == this.level ? delegate : IplPlotEntityStorage.rawGetter(world), bounds, consumer);
    }

    @WrapOperation(
        method = "get(Lnet/minecraft/world/phys/AABB;Ljava/util/function/Consumer;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/LevelEntityGetter;get(Lnet/minecraft/world/phys/AABB;Ljava/util/function/Consumer;)V",
            ordinal = 2),
        require = 1
    )
    private void ipl$intersectingPlotStorage(
        LevelEntityGetter<?> delegate, AABB bounds, Consumer<?> consumer,
        Operation<Void> original, @Local(ordinal = 1) SubLevel other
    ) {
        Level storage = IplPlotEntityStorage.plotStorage(this.level, other);
        original.call(storage == this.level ? delegate : IplPlotEntityStorage.rawGetter(storage), bounds, consumer);
    }

    // ------------------------------------------------------------------
    // get(EntityTypeTest, AABB, AbortableIterationConsumer)
    // ------------------------------------------------------------------

    @WrapOperation(
        method = "get(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/LevelEntityGetter;get(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V",
            ordinal = 0),
        require = 1
    )
    private void ipl$ownBoundsStorageTyped(
        LevelEntityGetter<?> delegate, EntityTypeTest<?, ?> test, AABB bounds,
        AbortableIterationConsumer<?> consumer,
        Operation<Void> original, @Local(ordinal = 0) SubLevel containing
    ) {
        original.call(delegate, test, bounds, consumer);
        Level extra = IplPlotEntityStorage.additionalPrimaryStorage(this.level, containing);
        if (extra != null) original.call(IplPlotEntityStorage.rawGetter(extra), test, bounds, consumer);
    }

    @WrapOperation(
        method = "get(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/LevelEntityGetter;get(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V",
            ordinal = 1),
        require = 1
    )
    private void ipl$worldFrameStorageTyped(
        LevelEntityGetter<?> delegate, EntityTypeTest<?, ?> test, AABB bounds,
        AbortableIterationConsumer<?> consumer,
        Operation<Void> original, @Local(ordinal = 0) SubLevel containing
    ) {
        Level world = IplPlotEntityStorage.worldLevel(this.level, containing);
        original.call(world == this.level ? delegate : IplPlotEntityStorage.rawGetter(world), test, bounds, consumer);
    }

    @WrapOperation(
        method = "get(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/LevelEntityGetter;get(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V",
            ordinal = 2),
        require = 1
    )
    private void ipl$intersectingPlotStorageTyped(
        LevelEntityGetter<?> delegate, EntityTypeTest<?, ?> test, AABB bounds,
        AbortableIterationConsumer<?> consumer,
        Operation<Void> original, @Local(ordinal = 1) SubLevel other
    ) {
        Level storage = IplPlotEntityStorage.plotStorage(this.level, other);
        original.call(storage == this.level ? delegate : IplPlotEntityStorage.rawGetter(storage), test, bounds, consumer);
    }

    // ------------------------------------------------------------------
    // Intersecting set: taken in the level the query's world-frame bounds belong to.
    // ------------------------------------------------------------------

    @WrapOperation(
        method = {
            "get(Lnet/minecraft/world/phys/AABB;Ljava/util/function/Consumer;)V",
            "get(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V"
        },
        at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/ActiveSableCompanion;getAllIntersecting(Lnet/minecraft/world/level/Level;Ldev/ryanhcode/sable/companion/math/BoundingBox3dc;)Ljava/lang/Iterable;"),
        require = 1
    )
    private Iterable<SubLevel> ipl$intersectInWorldLevel(
        ActiveSableCompanion helper, Level queried, BoundingBox3dc bounds,
        Operation<Iterable<SubLevel>> original, @Local(ordinal = 0) SubLevel containing
    ) {
        return original.call(helper, IplPlotEntityStorage.worldLevel(queried, containing), bounds);
    }
}

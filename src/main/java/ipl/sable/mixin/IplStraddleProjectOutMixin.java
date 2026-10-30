package ipl.sable.mixin;

import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.transit.IplProjectionTree;
import net.minecraft.core.Position;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Piece-aware {@code projectOutOfSubLevel}: Sable maps a plot point to world space with
 * the ship's own (native) pose, but a point of a straddling ship physically lives in ONE
 * piece -- the native piece, or the image of one portal session (chains included). The
 * RETURN remap moves the native-pose result into the frame of the piece that owns it, as
 * seen from the queried level ({@link IplProjectionTree#worldPointIn}).
 *
 * <p>This single remap heals every distance consumer at once on BOTH sides: pick reach
 * clamp ({@code GameRenderer.pick}/{@code filterHitResult}), interaction distance, Jade's
 * ray, sound positions, rider eye positions, IP's cross-portal reach validation.
 *
 * <p>Русский комментарий: раньше решение "через какую половину" принималось по одной
 * плоскости первого портала, а для чужих кораблей поза подменялась "первым отображением в
 * уровень" (IplProjectionIntersectionMixin). Для нескольких порталов и цепочек это давало
 * точку не того куска — отсюда "слишком далеко" и отказ взаимодействия после выхода из
 * второго портала. Теперь кадр выбирает дерево проекций — та же геометрия, что и в физике.
 */
@Pseudo
@Mixin(value = ActiveSableCompanion.class, remap = false)
public abstract class IplStraddleProjectOutMixin {

    @Inject(
        method = "distanceSquaredWithSubLevels(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/Position;Lnet/minecraft/core/Position;)D",
        at = @At("RETURN"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private void ipl$frameAwareDistance(
        Level level, Position a, Position b, CallbackInfoReturnable<Double> cir
    ) {
        boolean plotA = ipl$isPlotPosition(level, a.x(), a.z());
        boolean plotB = ipl$isPlotPosition(level, b.x(), b.z());
        if (!plotA && !plotB) return;

        // The runtime companion's overload delegation does not reliably route this pair
        // through the (remapping) 3-arg projection - recompute both endpoints through it
        // DIRECTLY. The recomputed value is authoritative (the stock one may have measured
        // a foreign ship's point in its own dimension's coordinates).
        ActiveSableCompanion self = (ActiveSableCompanion) (Object) this;
        Vector3d worldA = self.projectOutOfSubLevel(
            level, new Vector3d(a.x(), a.y(), a.z()), new Vector3d());
        Vector3d worldB = self.projectOutOfSubLevel(
            level, new Vector3d(b.x(), b.y(), b.z()), new Vector3d());
        cir.setReturnValue(worldA.distanceSquared(worldB));
    }

    @org.spongepowered.asm.mixin.Unique
    private static boolean ipl$isPlotPosition(Level level, double x, double z) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) return true;
        var hosting = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(serverLevel);
        if (hosting == null) return false;
        int chunkX = net.minecraft.core.SectionPos.blockToSectionCoord((int) Math.floor(x));
        int chunkZ = net.minecraft.core.SectionPos.blockToSectionCoord((int) Math.floor(z));
        return hosting.inBounds(chunkX, chunkZ) && hosting.getPlot(chunkX, chunkZ) != null;
    }

    @Inject(
        method = "projectOutOfSubLevel(Lnet/minecraft/world/level/Level;Lorg/joml/Vector3dc;Lorg/joml/Vector3d;)Lorg/joml/Vector3d;",
        at = @At("RETURN"),
        remap = false,
        require = 0
    )
    private void ipl$remapToPiece(
        Level level, Vector3dc pos, Vector3d dest, CallbackInfoReturnable<Vector3d> cir
    ) {
        SubLevel owner = ((ActiveSableCompanion) (Object) this).getContaining(level, pos);
        if (owner == null) return;
        Vector3d out = cir.getReturnValue();
        Vec3 remapped = IplProjectionTree.worldPointIn(owner, level, new Vec3(out.x, out.y, out.z));
        if (remapped != null) {
            out.set(remapped.x, remapped.y, remapped.z);
        }
    }

    @Inject(
        method = "projectOutOfSubLevel(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/Position;)Lnet/minecraft/world/phys/Vec3;",
        at = @At("RETURN"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private void ipl$remapToPieceVec(
        Level level, Position pos, CallbackInfoReturnable<Vec3> cir
    ) {
        SubLevel owner = ((ActiveSableCompanion) (Object) this).getContaining(level, pos);
        if (owner == null) return;
        Vec3 remapped = IplProjectionTree.worldPointIn(owner, level, cir.getReturnValue());
        if (remapped != null) {
            cir.setReturnValue(remapped);
        }
    }
}

package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.foundation.collision.CollisionList;
import ipl.sable.transit.IplContraptionCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.function.Predicate;

/**
 * Create's entity collision of a contraption on a straddling body runs once per piece
 * ({@link IplContraptionCollision}): entities of the piece's level, blocks of the piece.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.ContraptionCollider", remap = false)
public abstract class ContraptionColliderPiecesMixin {

    @WrapMethod(method = "collideEntities")
    private static void ipl$collideEachPiece(AbstractContraptionEntity contraption, Operation<Void> original) {
        IplContraptionCollision.collide(contraption, () -> original.call(contraption));
    }

    @WrapOperation(
        method = "collideEntities",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;")
    )
    private static List<Entity> ipl$pieceLevelEntities(Level world, Class<Entity> type, AABB box, Predicate<Entity> filter,
                                                       Operation<List<Entity>> original) {
        return original.call(IplContraptionCollision.level(world), type, box, filter);
    }

    @WrapOperation(
        method = "collideEntities",
        at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/contraptions/Contraption;getSimplifiedEntityColliders()Lcom/simibubi/create/foundation/collision/CollisionList;")
    )
    private static CollisionList ipl$blocksOfPiece(Contraption contraption, Operation<CollisionList> original) {
        return IplContraptionCollision.splitting() ? null : original.call(contraption);
    }

    @WrapOperation(
        method = "getPotentiallyCollidedShapes",
        at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/contraptions/Contraption;isHiddenInPortal(Lnet/minecraft/core/BlockPos;)Z")
    )
    private static boolean ipl$hideOtherPieces(Contraption contraption, BlockPos pos, Operation<Boolean> original) {
        return original.call(contraption, pos) || IplContraptionCollision.hides(pos);
    }
}

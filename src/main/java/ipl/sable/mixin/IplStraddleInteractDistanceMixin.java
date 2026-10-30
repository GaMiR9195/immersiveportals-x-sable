package ipl.sable.mixin;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.transit.IplProjectionTree;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Interaction distance for blocks of straddling ships, measured in the frame of the PIECE
 * the block physically belongs to.
 *
 * <p>Sable's interaction-distance fix ({@code interaction_distance.PlayerMixin}) measures
 * plot-position interactions by inverse-transforming the player's eye with the ship's
 * NATIVE pose -- wrong for a block whose piece is a portal image (foreign ship, or a
 * same-dimension portal, or any chain), so the server rejected breaks/uses on the
 * through-part as "too far" (silently; the client pick was correct). The block's piece
 * is resolved by the projection tree; when that piece is present in the player's level
 * the check uses exactly its pose. A block whose piece is not present in the player's
 * level is judged by every frame the ship has here (the nearest one), which keeps
 * interaction continuous while a session flips.
 *
 * <p>Composition is safe in ANY callback order: both Sable's handler and this one only
 * cancel on SUCCESS, falling through otherwise. Runs on both sides: the server validates
 * real interactions; the client gates swing prediction.
 */
@Mixin(value = Player.class, priority = 1200)
public abstract class IplStraddleInteractDistanceMixin {

    @Shadow
    public abstract double blockInteractionRange();

    @Inject(method = "canInteractWithBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private void ipl$canInteractWithPieceBlock(
        BlockPos pos, double slop, CallbackInfoReturnable<Boolean> cir
    ) {
        Player self = (Player) (Object) this;
        SubLevel owner = dev.ryanhcode.sable.Sable.HELPER.getContaining(self.level(), pos);
        if (owner == null) return;
        IplProjectionTree tree = IplProjectionTree.of(owner);
        if (tree.isEmpty() && owner.getLevel() == self.level()) return; // plain native ship: Sable's own check

        Pose3dc real = owner.logicalPose();
        Vec3 eye = self.getEyePosition();
        double range = this.blockInteractionRange() + slop;
        double rangeSq = range * range;
        AABB box = new AABB(pos);

        Pose3dc piecePose = IplProjectionTree.piecePoseIn(owner, self.level(), real,
            real.transformPosition(Vec3.atCenterOf(pos)));
        if (piecePose != null) {
            if (box.distanceToSqr(piecePose.transformPositionInverse(eye)) < rangeSq) cir.setReturnValue(true);
            return;
        }
        boolean[] ok = {false};
        IplProjectionTree.forEachFrameIn(owner, self.level(), node -> {
            if (ok[0]) return;
            Pose3dc pose = node == null ? real : node.mapping.mapPose(real);
            if (box.distanceToSqr(pose.transformPositionInverse(eye)) < rangeSq) ok[0] = true;
        });
        if (ok[0]) cir.setReturnValue(true);
    }
}

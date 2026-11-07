package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.client.IplClientProjections;
import ipl.sable.client.IplStraddleRenderState;
import ipl.sable.transit.IplProjectionTree;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.List;

/**
 * Block hit-outline drawn on the PIECE of a straddling ship that the targeted block
 * physically belongs to, in whichever world pass shows that piece.
 *
 * <p>Sable's outline wrap (priority 2000) transforms the outline by
 * {@code subLevel.renderPose()} -- the native pose. This wrap sits OUTSIDE Sable's
 * (priority 2100; last-applied wraps are outermost in the MixinExtras chain). It
 * resolves the block's piece with the projection tree; when that piece is an image
 * present in this pass's level, the highlight call is bracketed with
 * {@link IplStraddleRenderState} for that exact image (chains included), and our
 * {@code renderPose()} override feeds Sable's own outline math the image pose. A piece
 * that is not present in this pass's level draws no outline here at all (it is drawn in
 * the pass that shows it), so no phantom outline appears on a cut-away twin, after a second
 * portal included.
 */
@Mixin(value = LevelRenderer.class, priority = 2100)
public abstract class LevelRendererOutlineMixin {

    @Shadow
    @Nullable
    private ClientLevel level;

    @WrapOperation(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/neoforged/neoforge/client/ClientHooks;onDrawHighlight(Lnet/minecraft/client/renderer/LevelRenderer;Lnet/minecraft/client/Camera;Lnet/minecraft/world/phys/HitResult;Lnet/minecraft/client/DeltaTracker;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)Z",
            remap = false
        )
    )
    private boolean ipl$outlineOnPiece(
        LevelRenderer context, Camera camera, HitResult target, DeltaTracker deltaTracker,
        PoseStack poseStack, MultiBufferSource bufferSource, Operation<Boolean> original
    ) {
        if (this.level == null || !(target instanceof BlockHitResult blockTarget)
            || target.getType() != HitResult.Type.BLOCK) {
            return original.call(context, camera, target, deltaTracker, poseStack, bufferSource);
        }
        SubLevel owner = Sable.HELPER.getContaining(this.level, blockTarget.getBlockPos());
        if (!(owner instanceof ClientSubLevel clientOwner)) {
            return original.call(context, camera, target, deltaTracker, poseStack, bufferSource);
        }
        IplProjectionTree tree = IplProjectionTree.of(owner);
        if (tree.isEmpty()) {
            return original.call(context, camera, target, deltaTracker, poseStack, bufferSource);
        }

        Pose3dc real = clientOwner.renderPose();
        var pos = blockTarget.getBlockPos();
        IplProjectionTree.Node piece = tree.node(tree.pieceOfPlot(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
        if (piece == null) {
            // Native piece: only the ship's own world shows it.
            if (owner.getLevel() == this.level) {
                return original.call(context, camera, target, deltaTracker, poseStack, bufferSource);
            }
            return true; // cancel: not physically in this pass's world
        }
        if (piece.destDim() != this.level.dimension()) {
            return true;
        }

        IplClientProjections.StraddleProjection projection = ipl$projectionFor(clientOwner, piece);
        Pose3dc pose = projection != null ? projection.mappedPose() : piece.mapping.mapPose(real);
        IplStraddleRenderState.set(clientOwner, pose, piece.id);
        try {
            return original.call(context, camera, target, deltaTracker, poseStack, bufferSource);
        } finally {
            IplStraddleRenderState.clear();
        }
    }

    /** The drawn projection of {@code sub} whose portal path equals the node's path. */
    @Unique
    @Nullable
    private IplClientProjections.StraddleProjection ipl$projectionFor(ClientSubLevel sub, IplProjectionTree.Node node) {
        List<Portal> path = node.pathPortals();
        for (IplClientProjections.StraddleProjection projection :
                IplClientProjections.getStraddleProjectionsInto(this.level)) {
            if (projection.sub() != sub || projection.via().size() != path.size()) continue;
            boolean same = true;
            for (int i = 0; i < path.size() && same; i++) {
                same = projection.via().get(i).getUUID().equals(path.get(i).getUUID());
            }
            if (same) return projection;
        }
        return null;
    }
}

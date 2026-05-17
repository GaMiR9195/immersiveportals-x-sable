package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher;
import dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher;
import ipl.sable.render.SourceClipPortalFinder;
import ipl.sable.render.SubLevelClipUniformPatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import qouteall.imm_ptl.core.render.FrontClipping;

import java.util.Collection;

/**
 * Extend our per-sub-level clip (gl_ClipDistance[1] / ipl_subLevelClipEquation)
 * to Sable's block-entity render pass.
 *
 * <p><b>Why this is needed:</b> Sable renders sub-level block entities (chests
 * on the airship, signs, banners, brewing stands, etc.) through
 * {@link VanillaSubLevelRenderDispatcher#renderBlockEntities}, which iterates
 * sub-levels and dispatches each one's BEs to the active
 * {@link SubLevelRenderDispatcher.BlockEntityRenderer}. This is a separate
 * render pass from chunked-terrain rendering, so the brackets in
 * {@link SableSourceClipMixin} (which only fire inside {@code renderChunkedSubLevel})
 * don't cover it. Without intervention:
 *
 * <ul>
 *   <li>In main-dim render of source dim, our slot 1 is disabled (or has a
 *       stale equation) -- block entities render unclipped, so source-side
 *       chests visible past the portal plane in the dest-side half show up
 *       where they shouldn't.</li>
 *   <li>In portal-through render of the mirror, IP's slot 0 may apply but its
 *       direction doesn't include our mirror-flip via MirrorRegistry, so the
 *       mirror's block entities clip on the OPPOSITE side from where the
 *       chunks clip -- the "parity backwards" symptom the user reported.</li>
 * </ul>
 *
 * <p>Wrapping the per-sub-level call inside the loop lets us install the same
 * per-sub-level equation we use for chunks (with mirror flip applied for
 * kinematic mirrors via {@link SourceClipPortalFinder}), so block entities
 * follow the chunks visually.
 *
 * <p>{@code @Pseudo} because Sable's class isn't on the compile classpath at
 * mixin-validation time.
 *
 * <p>We wrap both {@code renderBlockEntities(Collection,...)} and
 * {@code renderSingleBE(...)} since Sable dispatches to either depending on the
 * sub-level's render data type.
 */
@Pseudo
@Mixin(value = VanillaSubLevelRenderDispatcher.class, remap = false)
public abstract class SableSubLevelBlockEntityClipMixin {

    @WrapOperation(
        method = "renderBlockEntities",
        at = @org.spongepowered.asm.mixin.injection.At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/render/dispatcher/SubLevelRenderDispatcher$BlockEntityRenderer;renderBlockEntities(Ljava/util/Collection;Lcom/mojang/blaze3d/vertex/PoseStack;FDDD)V",
            remap = false
        ),
        remap = false,
        require = 0
    )
    private void ipl$wrapRenderBEs(
        SubLevelRenderDispatcher.BlockEntityRenderer renderer,
        Collection<BlockEntity> entities,
        PoseStack pose,
        float partialTick,
        double x, double y, double z,
        Operation<Void> original,
        @Local(type = ClientSubLevel.class) ClientSubLevel sub
    ) {
        ipl$withClip(sub, () -> original.call(renderer, entities, pose, partialTick, x, y, z));
    }

    @WrapOperation(
        method = "renderBlockEntities",
        at = @org.spongepowered.asm.mixin.injection.At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/render/dispatcher/SubLevelRenderDispatcher$BlockEntityRenderer;renderSingleBE(Lnet/minecraft/world/level/block/entity/BlockEntity;Lcom/mojang/blaze3d/vertex/PoseStack;FDDD)V",
            remap = false
        ),
        remap = false,
        require = 0
    )
    private void ipl$wrapRenderSingleBE(
        SubLevelRenderDispatcher.BlockEntityRenderer renderer,
        BlockEntity be,
        PoseStack pose,
        float partialTick,
        double x, double y, double z,
        Operation<Void> original,
        @Local(type = ClientSubLevel.class) ClientSubLevel sub
    ) {
        ipl$withClip(sub, () -> original.call(renderer, be, pose, partialTick, x, y, z));
    }

    /**
     * Install our per-sub-level clip plane for the duration of {@code body.run()}.
     * Resolves the straddling portal for {@code sub}, writes the equation to our
     * slot-1 uniform, runs the operation, then restores state. Skips bracketing if
     * the sub-level isn't straddling any portal (no clip needed) so we don't pay
     * the cost on non-portal-adjacent sub-levels.
     */
    private static void ipl$withClip(ClientSubLevel sub, Runnable body) {
        SourceClipPortalFinder.ClipDecision decision =
            SourceClipPortalFinder.findStraddlingPortalPlane(sub);
        if (decision == null) {
            body.run();
            return;
        }

        SubLevelClipUniformPatcher.patchForSubLevel(sub, decision.plane());

        // Manage GL_CLIP_DISTANCE1 carefully: respect IP's portal-through state
        // when restoring on cleanup, since IP may want it on for subsequent
        // entity / vanilla terrain draws in the same pass.
        GL11.glEnable(GL30.GL_CLIP_DISTANCE1);

        try {
            body.run();
        } finally {
            if (!FrontClipping.isClippingEnabled) {
                GL11.glDisable(GL30.GL_CLIP_DISTANCE1);
            }
            SubLevelClipUniformPatcher.clearAndUpload();
        }
    }
}

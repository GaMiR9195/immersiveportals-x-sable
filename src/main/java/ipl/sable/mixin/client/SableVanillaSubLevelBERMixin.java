package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinhelpers.sublevel_render.vanilla.VanillaSubLevelBlockEntityRenderer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.render.SourceClipPortalFinder;
import ipl.sable.render.SubLevelClipUniformPatcher;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.render.FrontClipping;

/**
 * Bracket each per-BE render call inside {@link VanillaSubLevelBlockEntityRenderer#renderSingleBE}
 * with a sub-level clip patch, so Create cogs and other animated block
 * entities rendered through this path get the right
 * {@code ipl_subLevelClipEquation} and {@code GL_CLIP_DISTANCE1} enable.
 *
 * <p><b>Why this exists (separate from {@link SableSubLevelBlockEntityClipMixin}):</b>
 * Sable has <em>two</em> sub-level BE render paths and they go through
 * different call sites:
 *
 * <ol>
 *   <li>Sable's {@code LevelRendererMixin.sable$preRenderBEs} calls
 *       {@code dispatcher.renderBlockEntities(sublevels, ...)} which iterates
 *       chunk sections and dispatches collections of BEs. This is what
 *       {@link SableSubLevelBlockEntityClipMixin} wraps via
 *       {@code VanillaSubLevelRenderDispatcher.renderBlockEntities}'s inner
 *       calls. Used for the chunk-section-list BE batch.</li>
 *   <li>Sable's {@code LevelRendererMixin.sable$renderBlockEntities} wraps
 *       Mojang's vanilla {@code LevelRenderer.renderLevel} call to
 *       {@code BlockEntityRenderDispatcher.render(blockEntity, ...)} and,
 *       when the BE belongs to a sub-level, calls
 *       {@link VanillaSubLevelBlockEntityRenderer#renderSingleBE} directly
 *       BYPASSING the dispatcher's renderBlockEntities path. This is the
 *       path Create's {@code KineticBlockEntityRenderer} (cog rendering)
 *       lands in -- confirmed via RenderDoc cog_leak5.rdc EID 2294: the cog
 *       drew with {@code ipl_subLevelClipEquation = (0,0,0,1)} and
 *       {@code CLIP_DISTANCE1} disabled, because the dispatcher-wrap
 *       bracket never fired for it.</li>
 * </ol>
 *
 * <p>By mixing into {@code VanillaSubLevelBlockEntityRenderer.renderSingleBE}
 * directly, we catch both paths: the dispatcher path's per-BE call inside
 * its default-method loop AND the per-BE mixin path's direct call. The
 * brackets nest harmlessly (idempotent patch + idempotent enable + idempotent
 * clear) when both apply to the same BE render.
 *
 * <p>{@code @Pseudo} because the target class isn't on the compile classpath
 * at mixin-validation time -- it's a Sable mixinhelper.
 */
@Pseudo
@Mixin(value = VanillaSubLevelBlockEntityRenderer.class, remap = false)
public abstract class SableVanillaSubLevelBERMixin {

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-sable-be-bracket");

    @Unique
    private static final java.util.concurrent.atomic.AtomicBoolean IPL$LOGGED_FIRED =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    @Unique
    private static final java.util.concurrent.atomic.AtomicLong IPL$LAST_LOG_NS =
        new java.util.concurrent.atomic.AtomicLong(0L);

    /** Per-class "have we logged the post-draw program for this BE class yet" set. */
    @Unique
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> IPL$POST_DRAW_LOGGED =
        new java.util.concurrent.ConcurrentHashMap<>();

    @Unique
    private static void ipl$logPostDraw(String beClass, int progBefore, int progAfter) {
        // Log once per (class, progAfter) pair so we see every distinct
        // program any BE class binds. Capped to keep logs manageable.
        Integer prev = IPL$POST_DRAW_LOGGED.get(beClass);
        if (prev != null && prev == progAfter) return;
        if (IPL$POST_DRAW_LOGGED.size() > 30) return;
        IPL$POST_DRAW_LOGGED.put(beClass, progAfter);
        IPL$LOG.info("[IPL-BE-POST-DRAW] class={} progBefore={} progAfter={}",
            beClass, progBefore, progAfter);
    }

    /**
     * Wrap the inner {@code BlockEntityRenderDispatcher.render(...)} call so
     * we can bracket exactly the BE's render call with patch/clear. Going
     * through @WrapOperation instead of HEAD/RETURN inject pairs guarantees
     * the cleanup runs even if the inner render throws.
     */
    @WrapOperation(
        method = "renderSingleBE",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V"
        ),
        remap = false,
        require = 0
    )
    private void ipl$wrapBERender(
        BlockEntityRenderDispatcher dispatcher,
        BlockEntity be,
        float partialTick,
        PoseStack pose,
        MultiBufferSource source,
        Operation<Void> original
    ) {
        // DIAGNOSTIC: log on first fire + rate-limited thereafter, to confirm
        // this wrap fires for cog scenes. cog_leak8 EID 2265 showed the cog
        // drawing outside any bracket -- this log will confirm whether
        // SableVanillaSubLevelBlockEntityRenderer.renderSingleBE is even on
        // the cog's call path.
        if (IPL$LOGGED_FIRED.compareAndSet(false, true)) {
            IPL$LOG.info("[IPL-BE-BRACKET-FIRED] FIRST-FIRE be={} class={}",
                be.getBlockPos(), be.getClass().getSimpleName());
        } else {
            long now = System.nanoTime();
            long last = IPL$LAST_LOG_NS.get();
            if (now - last > 5_000_000_000L) {
                IPL$LAST_LOG_NS.set(now);
                IPL$LOG.info("[IPL-BE-BRACKET-FIRED] (rate-limited) be={} class={}",
                    be.getBlockPos(), be.getClass().getSimpleName());
            }
        }

        ClientSubLevel sub = Sable.HELPER.getContainingClient(be);
        if (sub == null) {
            // Shouldn't happen in practice -- this method's callers only invoke
            // it for sub-level BEs -- but be defensive.
            original.call(dispatcher, be, partialTick, pose, source);
            return;
        }

        SourceClipPortalFinder.ClipDecision decision =
            SourceClipPortalFinder.findStraddlingPortalPlane(sub);
        if (decision == null) {
            // Sub-level not straddling a portal -- no clip needed.
            original.call(dispatcher, be, partialTick, pose, source);
            return;
        }

        // Pre-drain: flush anything queued BEFORE we set up our clip state,
        // so e.g. slime outer-shell vertices queued earlier in the frame
        // get drawn with their normal (un-clipped) state. Without this,
        // the post-bracket global flush would catch them too and clip them
        // against the airship's plane ("naked slime on the hidden half"
        // regression).
        if (source instanceof BufferSource bs) {
            bs.endBatch();
        }

        SubLevelClipUniformPatcher.patchForSubLevel(sub, decision.plane());
        GL11.glEnable(GL30.GL_CLIP_DISTANCE1);

        int progBefore = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        try {
            original.call(dispatcher, be, partialTick, pose, source);

            // Post-drain: now that the cog has queued its vertices, flush
            // them globally. The queue was empty when we entered (pre-drain
            // above), so this drains ONLY the cog's contribution -- meaning
            // our active clip state (CD1 enabled, slot-1 set) applies to
            // the cog's draws and nothing else.
            if (source instanceof BufferSource bs) {
                bs.endBatch();
            }

            int progAfter = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            ipl$logPostDraw(be.getClass().getSimpleName(), progBefore, progAfter);
        } finally {
            // Always disable CD1 on bracket exit (see SableSourceClipMixin
            // for the same fix + rationale).
            GL11.glDisable(GL30.GL_CLIP_DISTANCE1);
            SubLevelClipUniformPatcher.clearAndUpload();
        }
    }

}

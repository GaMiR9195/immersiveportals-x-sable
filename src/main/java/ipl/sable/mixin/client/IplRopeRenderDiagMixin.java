package ipl.sable.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;
import dev.simulated_team.simulated.content.blocks.rope.strand.client.ClientRopeStrand;
import dev.simulated_team.simulated.content.blocks.rope.strand.client.RopeStrandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TEMPORARY DIAGNOSTIC — [IPL-ROPE] render-gate probe.
 *
 * <p>The strand renderer silently draws nothing when the holder does not own a rope, the
 * client strand was never created (no packet processed), or the strand has ≤1 points.
 * This throttled probe reports which gate fires, closing the diagnostic chain:
 * server create → server active/tracking → packet arrival → client receive → render gate.
 * Remove once the rope chain is verified end-to-end.
 */
@Pseudo
@Mixin(value = RopeStrandRenderer.class, remap = false)
public abstract class IplRopeRenderDiagMixin {

    @Unique
    private static long ipl$lastRenderLogMs = 0;

    @Inject(method = "render", at = @At("HEAD"), remap = false, require = 0)
    private static void ipl$probeRenderGate(
        SmartBlockEntity be, RopeStrandHolderBehavior ropeHolder, float partialTick,
        PoseStack ps, MultiBufferSource buffer, CallbackInfo ci
    ) {
        long now = System.currentTimeMillis();
        if (now - ipl$lastRenderLogMs < 2000) return;
        ipl$lastRenderLogMs = now;

        ClientRopeStrand strand = ropeHolder != null ? ropeHolder.getClientStrand() : null;
        org.slf4j.LoggerFactory.getLogger("ipl-rope").info(
            "[IPL-ROPE] render probe at {} level={} ownsRope={} clientStrand={} points={}",
            be.getBlockPos(),
            be.getLevel() == null ? "null" : be.getLevel().dimension().location(),
            ropeHolder != null && ropeHolder.ownsRope(),
            strand != null,
            strand != null ? strand.getPoints().size() : -1);
    }
}

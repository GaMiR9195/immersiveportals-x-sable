package ipl.sable.client;

import foundry.veil.api.event.VeilRenderLevelStageEvent;
import foundry.veil.platform.VeilEventPlatform;
import ipl.sable.mixin.client.IplStaffPortalBeamPassMixin;

/** Draw where Simulated normally draws: active world matrix, active portal camera, live stage. */
public final class IplStaffPortalBeamStage {

    private static boolean initialized;

    private IplStaffPortalBeamStage() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        VeilEventPlatform.INSTANCE.onVeilRenderLevelStage((stage, renderer, buffer, matrixStack,
            frustumMatrix, projectionMatrix, renderTick, deltaTracker, camera, frustum) -> {
            if (stage != VeilRenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
            IplStaffPortalBeamRenderer.render(
                matrixStack.toPoseStack(), camera,
                ((IplStaffPortalBeamPassMixin) (Object) renderer).ipl$getLevel()
            );
        });
    }
}

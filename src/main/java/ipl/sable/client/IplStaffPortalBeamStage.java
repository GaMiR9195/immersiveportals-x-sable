package ipl.sable.client;

import ipl.sable.mixin.client.LevelRendererAccessor;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Vanilla NeoForge stage, independent from Veil's shader and buffer pipeline. */
public final class IplStaffPortalBeamStage {

    private static boolean initialized;

    private IplStaffPortalBeamStage() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, event -> {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
            var level = ((LevelRendererAccessor) (Object) event.getLevelRenderer()).ipl$getLevel();
            IplStaffPortalBeamRenderer.render(event.getPoseStack(), event.getCamera(), level);
        });
    }
}

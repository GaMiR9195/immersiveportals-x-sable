package ipl.sable.mixin.client;

import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import ipl.sable.render.IplFlywheelPass;
import ipl.sable.render.IplSubLevelBlockEntityPass;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

/**
 * Flywheel and multiple client levels.
 *
 * <ul>
 *   <li>Flywheel serves a level only while it is {@code Minecraft.level}. Immersive Portals swaps
 *       that field for every portal pass and remote tick, and section compiles run on worker
 *       threads that read it at an arbitrary moment: a block entity then went to Flywheel or to
 *       the vanilla list depending on timing, giving block entities drawn twice (z-fighting,
 *       flickering parts) or by nobody (missing parts), in the player's level and in levels
 *       seen through portals alike. Every client level is served, always; each level's visuals
 *       are drawn by its own manager in its own passes.</li>
 *   <li>While the BER of a straddling body is drawn ({@link IplSubLevelBlockEntityPass}) Flywheel
 *       is "unsupported", so Create draws the parts it would otherwise leave to instancing.</li>
 *   <li>Whether the frame being planned belongs to a portal pass is captured on the render thread
 *       for {@code FlywheelEngineOriginMixin}.</li>
 * </ul>
 */
@Mixin(value = VisualizationManagerImpl.class, remap = false)
public abstract class FlywheelVisualizationManagerMixin {

    @Inject(
        method = "supportsVisualization(Lnet/minecraft/world/level/LevelAccessor;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void ipl$supportsVisualization(
        LevelAccessor level, CallbackInfoReturnable<Boolean> cir
    ) {
        if (IplSubLevelBlockEntityPass.isForcingVanillaRender()) {
            cir.setReturnValue(false);
            return;
        }
        if (level instanceof ClientLevel && BackendManager.isBackendOn()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "beginFrame", at = @At("HEAD"))
    private void ipl$capturePass(RenderContext context, CallbackInfo ci) {
        IplFlywheelPass.planningPortalPass = WorldRenderInfo.isRendering();
    }
}

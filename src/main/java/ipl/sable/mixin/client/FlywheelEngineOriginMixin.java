package ipl.sable.mixin.client;

import ipl.sable.render.IplFlywheelPass;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Flywheel keeps instance positions relative to a render origin; when the camera gets a few
 * hundred blocks away from it, the origin moves and every visual is recreated. A portal pass whose
 * camera is far from the player's would move the origin back and forth twice per frame.
 *
 * <p>Portal passes keep the origin unless their camera is so far that float precision would
 * suffer ({@link #PASS_KEEP_DISTANCE}); only the player's own view moves it.
 */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.backend.engine.EngineImpl", remap = false)
public abstract class FlywheelEngineOriginMixin {
    private static final double PASS_KEEP_DISTANCE = 4096.0;

    @Shadow
    private BlockPos renderOrigin;

    @Inject(method = "updateRenderOrigin", at = @At("HEAD"), cancellable = true)
    private void ipl$keepOriginInPortalPasses(Camera camera, CallbackInfoReturnable<Boolean> cir) {
        if (!IplFlywheelPass.planningPortalPass) return;
        Vec3 p = camera.getPosition();
        double dx = renderOrigin.getX() - p.x;
        double dy = renderOrigin.getY() - p.y;
        double dz = renderOrigin.getZ() - p.z;
        if (dx * dx + dy * dy + dz * dz <= PASS_KEEP_DISTANCE * PASS_KEEP_DISTANCE) {
            cir.setReturnValue(false);
        }
    }
}

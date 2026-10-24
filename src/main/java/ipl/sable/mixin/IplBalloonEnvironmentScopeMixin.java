package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import ipl.sable.dim.IplWorldFrameContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Aeronautics applies balloon forces from a level-wide event, outside Sable's
 * actor callback. Each balloon resolves its controller plot before sampling
 * pressure, so a deferred frame can identify its owner unambiguously.
 */
@Pseudo
@Mixin(targets = "dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.ServerBalloon", remap = false)
public abstract class IplBalloonEnvironmentScopeMixin {
    @WrapMethod(method = "applyForces")
    private void ipl$environmentForBalloon(double timeStep, Operation<Void> original) {
        // This balloon is a new owner, even if invoked from another actor's
        // callback. Do not inherit an unrelated outer ship's environment.
        var previous = IplWorldFrameContext.push(null);
        var frame = IplWorldFrameContext.beginDeferredFrame();
        try {
            original.call(timeStep);
        } finally {
            IplWorldFrameContext.endDeferredFrame(frame);
            IplWorldFrameContext.pop(previous);
        }
    }
}
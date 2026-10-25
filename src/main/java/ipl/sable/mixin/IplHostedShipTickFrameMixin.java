package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.IplWorldFrameContext;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * A hosted ship's own per-tick work ({@code ServerSubLevel.tick}: plot tick, bounds, split
 * heat map, and anything addons attach to it) runs in the frame of the world the ship flies
 * through — the same seam {@link IplHostedPhysicsTickContextMixin} provides for physics
 * actors. Storage identity is untouched; only explicit world-frame probes are bridged.
 */
@Pseudo
@Mixin(value = ServerSubLevel.class, remap = false)
public abstract class IplHostedShipTickFrameMixin {

    @WrapMethod(method = "tick", remap = false)
    private void ipl$worldFrameDuringShipTick(Operation<Void> original) {
        ServerSubLevel self = (ServerSubLevel) (Object) this;
        ServerLevel parent = IplDimAgnostic.isHosted(self)
            ? IplDimAgnostic.getServerParentLevel(self) : null;
        if (parent == null) {
            original.call();
            return;
        }
        ServerLevel previous = IplWorldFrameContext.push(parent);
        try {
            original.call();
        } finally {
            IplWorldFrameContext.pop(previous);
        }
    }
}

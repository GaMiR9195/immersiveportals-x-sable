package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.IplWorldFrameContext;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Arm the world-frame context for the PHYSICS actor pass of hosted ships.
 *
 * <p>{@code IplHostedBeTickContextMixin} arms {@link IplWorldFrameContext} around
 * regular chunk BE ticks — but {@code sable$physicsTick} actors (Offroad wheel
 * mounts, lift providers, contraptions) run from {@code ServerSubLevel.prePhysicsTick}
 * inside the physics step, OUTSIDE that wrap. An Offroad wheel's suspension cast is
 * a plot-space ray that Sable's clip overlay pose-projects into WORLD coordinates
 * and traverses via the BE's own level — hosted, that's the void
 * {@code ipl_sable:sublevels}, so wheels never found the ground. With the context
 * armed, {@code IplHostedWorldFrameRouterMixin} routes the world-frame traversal
 * (and the friction {@code getBlockState} on the hit block) to the ship's parent
 * dimension — the terrain the wheel is actually rolling on.
 */
@Pseudo
@Mixin(value = ServerSubLevel.class, remap = false)
public abstract class IplHostedPhysicsTickContextMixin {

    @WrapMethod(method = "prePhysicsTick", remap = false)
    private void ipl$worldFrameDuringPhysicsActors(
        SubLevelPhysicsSystem physicsSystem, RigidBodyHandle handle, double timeStep,
        Operation<Void> original
    ) {
        ServerSubLevel self = (ServerSubLevel) (Object) this;
        ServerLevel parent = IplDimAgnostic.isHosted(self)
            ? IplDimAgnostic.getServerParentLevel(self) : null;
        if (parent == null) {
            original.call(physicsSystem, handle, timeStep);
            return;
        }
        ServerLevel prev = IplWorldFrameContext.push(parent);
        try {
            original.call(physicsSystem, handle, timeStep);
        } finally {
            IplWorldFrameContext.pop(prev);
        }
    }
}

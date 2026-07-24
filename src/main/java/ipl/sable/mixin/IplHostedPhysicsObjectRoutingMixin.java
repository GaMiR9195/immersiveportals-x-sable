package ipl.sable.mixin;

import dev.ryanhcode.sable.api.physics.object.ArbitraryPhysicsObject;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.IplHostedPhysicsObjects;
import ipl.sable.dim.IplWorldFrameContext;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Route ARBITRARY PHYSICS OBJECTS (rope strands, joints/constraints, buoyancy boxes — every
 * two-point "connection" mods build) of hosted ships into the PARENT dimension's physics
 * system.
 *
 * <p>Mods resolve the target system from {@code be.getLevel()} — hosted, the void hosting
 * dimension. A world-frame object added there never activates: the hosting ticket manager's
 * {@code wouldBeLoaded} sees void terrain (Simulated rope strands stayed inactive forever —
 * no points, invisible ropes, plunger pairs and swivel tops without their connection,
 * springs without the coil), and even when force-added it would tick against the wrong
 * chart's terrain. With the world-frame context armed (BE tick / physics actor pass /
 * interaction), the add is redirected to the parent's system — where the ship's body and
 * terrain actually live. Removals follow a recorded object → system map, so unload paths
 * (not armed) still clean up in the right system.
 */
@Pseudo
@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public abstract class IplHostedPhysicsObjectRoutingMixin {

    @Inject(method = "addObject", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$routeAddToParentSystem(ArbitraryPhysicsObject object, CallbackInfo ci) {
        SubLevelPhysicsSystem self = (SubLevelPhysicsSystem) (Object) this;
        if (!IplDimAgnostic.isHostingLevel(self.getLevel())) return;
        ServerLevel parent = IplWorldFrameContext.current();
        if (parent == null || parent == self.getLevel()) return;

        SubLevelContainer parentContainer = SubLevelContainer.getContainer(parent);
        if (!(parentContainer instanceof dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer serverContainer)) return;
        SubLevelPhysicsSystem parentSystem = serverContainer.physicsSystem();
        if (parentSystem == null || parentSystem == self) return;

        IplHostedPhysicsObjects.recordOwner(object, parentSystem);
        parentSystem.addObject(object);
        ci.cancel();
    }

    @Inject(method = "removeObject", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$routeRemoveToOwningSystem(ArbitraryPhysicsObject object, CallbackInfo ci) {
        SubLevelPhysicsSystem self = (SubLevelPhysicsSystem) (Object) this;
        SubLevelPhysicsSystem owner = IplHostedPhysicsObjects.takeOwner(object);
        if (owner != null && owner != self) {
            owner.removeObject(object);
            ci.cancel();
        }
    }
}

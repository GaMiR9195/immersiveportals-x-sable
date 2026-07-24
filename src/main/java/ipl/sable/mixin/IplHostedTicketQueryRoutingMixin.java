package ipl.sable.mixin;

import dev.ryanhcode.sable.api.physics.object.ArbitraryPhysicsObject;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.IplWorldFrameContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Companion to {@code IplHostedPhysicsObjectRoutingMixin}: the "may this physics object be
 * simulated" query. Mods gate their {@code addObject} on
 * {@code system.getTicketManager().wouldBeLoaded(level, object)} — asked of the HOSTING
 * dimension for a WORLD-FRAME object (Simulated rope strands), it always said no (void
 * terrain is never loaded there), so the object was never added at all. With the context
 * armed, answer from the PARENT's ticket manager against the parent level — the terrain
 * the object actually spans.
 */
@Pseudo
@Mixin(value = PhysicsChunkTicketManager.class, remap = false)
public abstract class IplHostedTicketQueryRoutingMixin {

    @Inject(method = "wouldBeLoaded", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$answerFromParentTickets(
        Level level, ArbitraryPhysicsObject object, CallbackInfoReturnable<Boolean> cir
    ) {
        if (!IplDimAgnostic.isHostingLevel(level)) return;
        ServerLevel parent = IplWorldFrameContext.current();
        if (parent == null || parent == level) return;

        SubLevelContainer parentContainer = SubLevelContainer.getContainer(parent);
        if (!(parentContainer instanceof dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer serverContainer)) return;
        SubLevelPhysicsSystem parentSystem = serverContainer.physicsSystem();
        if (parentSystem == null) return;
        PhysicsChunkTicketManager parentTickets = parentSystem.getTicketManager();
        if (parentTickets == null || parentTickets == (Object) this) return;

        cir.setReturnValue(parentTickets.wouldBeLoaded(parent, object));
    }
}

package ipl.sable.mixin;

import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;
import dev.simulated_team.simulated.content.blocks.rope.strand.server.ServerRopeStrand;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.UUID;

/**
 * TEMPORARY DIAGNOSTIC — [IPL-ROPE] probes along the rope strand lifecycle.
 *
 * <p>Every static trace of the rope chain now passes (creation frame, physics-system
 * resolution, add gate, manager bucket, tracking players via the bridged
 * {@code getPlayersTracking}, Veil packet, client BE resolve through the plot bridge,
 * BER dispatch), yet strands render invisible in runtime tests. These throttled probes
 * pinpoint the dead link in one run:
 * <ul>
 *   <li>{@code createRope} TAIL — was the strand created at all, in which frame;</li>
 *   <li>{@code tick} TAIL — is the strand ACTIVE, what do the two add-gate inputs
 *       (attachments loaded / wouldBeLoaded) answer, how many tracking players, how many
 *       points;</li>
 *   <li>{@code receiveClientStrand} TAIL — did the client accept points for the holder.</li>
 * </ul>
 * Remove once the rope chain is verified end-to-end.
 */
@Pseudo
@Mixin(value = RopeStrandHolderBehavior.class, remap = false)
public abstract class IplRopeDiagMixin {

    @Unique
    private static long ipl$lastCreateLogMs = 0;

    @Unique
    private static long ipl$lastTickLogMs = 0;

    @Unique
    private static long ipl$lastReceiveLogMs = 0;

    @Inject(method = "createRope", at = @At("TAIL"), remap = false, require = 0)
    private void ipl$logRopeCreated(
        RopeStrandHolderBehavior target, boolean dropItem, CallbackInfoReturnable<Boolean> cir
    ) {
        long now = System.currentTimeMillis();
        if (now - ipl$lastCreateLogMs < 1000) return;
        ipl$lastCreateLogMs = now;

        RopeStrandHolderBehavior self = (RopeStrandHolderBehavior) (Object) this;
        Level level = self.getWorld();
        ServerRopeStrand strand = self.getOwnedStrand();
        org.slf4j.LoggerFactory.getLogger("ipl-rope").info(
            "[IPL-ROPE] createRope result={} level={} self={} target={} points={}",
            cir.getReturnValue(),
            level == null ? "null" : level.dimension().location(),
            self.getPos(),
            target != null ? target.getPos() : null,
            strand != null ? strand.getPoints().size() : -1);
    }

    @Inject(method = "tick", at = @At("TAIL"), remap = false, require = 0)
    private void ipl$probeServerStrand(CallbackInfo ci) {
        RopeStrandHolderBehavior self = (RopeStrandHolderBehavior) (Object) this;
        Level level = self.getWorld();
        if (level == null || level.isClientSide()) return;
        ServerRopeStrand strand = self.getOwnedStrand();
        if (strand == null) return;

        long now = System.currentTimeMillis();
        if (now - ipl$lastTickLogMs < 2000) return;
        ipl$lastTickLogMs = now;

        boolean attachmentsLoaded = false;
        boolean wouldBeLoaded = false;
        String systemDim = "?";
        try {
            var system = self.getPhysicsSystem();
            ServerLevel systemLevel = system.getLevel();
            systemDim = systemLevel.dimension().location().toString();
            attachmentsLoaded = strand.areAttachmentsLoaded(systemLevel);
            wouldBeLoaded = system.getTicketManager().wouldBeLoaded(systemLevel, strand);
        } catch (Throwable t) {
            systemDim = "ERROR: " + t;
        }

        org.slf4j.LoggerFactory.getLogger("ipl-rope").info(
            "[IPL-ROPE] server strand {} at {} beLevel={} systemLevel={} active={} "
                + "attachmentsLoaded={} wouldBeLoaded={} trackers={} points={}",
            strand.getUUID(), self.getPos(),
            level.dimension().location(), systemDim,
            strand.isActive(), attachmentsLoaded, wouldBeLoaded,
            self.getStrandTrackingPlayers().size(), strand.getPoints().size());
    }

    @Inject(method = "receiveClientStrand", at = @At("TAIL"), remap = false, require = 0)
    private void ipl$logClientReceive(
        int interpolationTick, List<Vector3d> incomingPoints, UUID uuid,
        BlockPos startAttachmentPos, BlockPos endAttachmentPos, CallbackInfo ci
    ) {
        long now = System.currentTimeMillis();
        if (now - ipl$lastReceiveLogMs < 2000) return;
        ipl$lastReceiveLogMs = now;

        RopeStrandHolderBehavior self = (RopeStrandHolderBehavior) (Object) this;
        Level level = self.getWorld();
        org.slf4j.LoggerFactory.getLogger("ipl-rope").info(
            "[IPL-ROPE] client received strand {} at {} level={} points={} tick={}",
            uuid, self.getPos(),
            level == null ? "null" : level.dimension().location(),
            incomingPoints.size(), interpolationTick);
    }
}

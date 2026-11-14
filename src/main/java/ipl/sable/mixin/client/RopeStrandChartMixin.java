package ipl.sable.mixin.client;

import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;
import ipl.sable.client.IplRopeChartSync;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.UUID;

/**
 * Rope points enter the client strand in the chart the holder's body is shown in
 * ({@link IplRopeChartSync}), before they become interpolation snapshots.
 */
@Pseudo
@Mixin(value = RopeStrandHolderBehavior.class, remap = false)
public abstract class RopeStrandChartMixin {

	@Inject(method = "receiveClientStrand", at = @At("HEAD"))
	private void ipl$keepHolderChart(int interpolationTick, List<Vector3d> incomingPoints, UUID uuid,
									 @Nullable BlockPos startAttachmentPos, @Nullable BlockPos endAttachmentPos,
									 CallbackInfo ci) {
		IplRopeChartSync.onReceive((RopeStrandHolderBehavior) (Object) this, uuid, interpolationTick, incomingPoints);
	}
}

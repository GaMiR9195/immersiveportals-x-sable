package ipl.sable.mixin.client;

import dev.ryanhcode.sable.network.client.ClientSableInterpolationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * State of Sable's interpolation clock that {@link ipl.sable.client.IplSharedClock} reads to merge
 * clock packets and resets in place: all client containers hold the one shared instance, so a new
 * connection or level must not replace it.
 */
@Mixin(value = ClientSableInterpolationState.class, remap = false)
public interface InterpolationClockAccessor {

    @Accessor("mostRecentTick") double ipl$getMostRecentTick();
    @Accessor("mostRecentTick") void ipl$setMostRecentTick(double value);

    @Accessor("receivedFirstUpdate") boolean ipl$getReceivedFirstUpdate();
    @Accessor("receivedFirstUpdate") void ipl$setReceivedFirstUpdate(boolean value);

    @Accessor("interpolationTick") void ipl$setInterpolationTick(double value);

    @Accessor("estimatedServerTickSpeed") void ipl$setEstimatedServerTickSpeed(double value);

    @Accessor("serverMsFromLastUpdate") void ipl$setServerMsFromLastUpdate(float value);

    @Accessor("stopped") void ipl$setStopped(boolean value);
}

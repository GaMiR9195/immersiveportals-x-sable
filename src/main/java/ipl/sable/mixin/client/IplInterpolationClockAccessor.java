package ipl.sable.mixin.client;

import dev.ryanhcode.sable.network.client.ClientSableInterpolationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Доступ к часам интерполяции контейнера уровня.
 *
 * <p>Зачем: у каждого серверного уровня свой счётчик {@code interpolationTick}, и у каждого
 * клиентского контейнера — свои часы. При межизмеренческом переходе корабль «перескакивает»
 * в контейнер с другими (часто стоящими или устаревшими) часами — отсюда «подвис как пинг».
 * {@link ipl.sable.client.IplParentDimSync} выравнивает часы назначения по часам источника
 * со сдвигом, который сообщает сервер.
 */
@Mixin(value = ClientSableInterpolationState.class, remap = false)
public interface IplInterpolationClockAccessor {

    @Accessor("mostRecentTick") double ipl$getMostRecentTick();
    @Accessor("mostRecentTick") void ipl$setMostRecentTick(double value);

    @Accessor("receivedFirstUpdate") boolean ipl$getReceivedFirstUpdate();
    @Accessor("receivedFirstUpdate") void ipl$setReceivedFirstUpdate(boolean value);

    @Accessor("interpolationTick") double ipl$getInterpolationTick();
    @Accessor("interpolationTick") void ipl$setInterpolationTick(double value);

    @Accessor("estimatedServerTickSpeed") double ipl$getEstimatedServerTickSpeed();
    @Accessor("estimatedServerTickSpeed") void ipl$setEstimatedServerTickSpeed(double value);

    @Accessor("serverMsFromLastUpdate") float ipl$getServerMsFromLastUpdate();
    @Accessor("serverMsFromLastUpdate") void ipl$setServerMsFromLastUpdate(float value);

    @Accessor("stopped") void ipl$setStopped(boolean value);
}

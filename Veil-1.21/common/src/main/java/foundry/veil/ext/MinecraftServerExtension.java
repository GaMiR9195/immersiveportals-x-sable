package foundry.veil.ext;

import foundry.veil.impl.TickTaskSchedulerImpl;

import javax.annotation.Nullable;

public interface MinecraftServerExtension {

    @Nullable
    TickTaskSchedulerImpl veil$getScheduler();

    TickTaskSchedulerImpl veil$getOrCreateScheduler();
}

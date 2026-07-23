package foundry.veil.impl.client;

import foundry.veil.api.TickTaskScheduler;
import foundry.veil.impl.TickTaskSchedulerImpl;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public class VeilClientSchedulerImpl {

    private static final TickTaskSchedulerImpl INSTANCE = new TickTaskSchedulerImpl();

    public static void tick() {
        INSTANCE.run();
    }

    public static void shutdown() {
        INSTANCE.shutdown();
    }

    public static TickTaskScheduler getScheduler() {
        return INSTANCE;
    }
}

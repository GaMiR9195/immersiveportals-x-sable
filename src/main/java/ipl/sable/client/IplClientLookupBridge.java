package ipl.sable.client;

import java.util.function.Supplier;

/**
 * Latch shared between {@link ipl.sable.mixin.client.IplClientSubLevelLookupBridgeMixin}
 * and callers that need a container-LOCAL answer (no cross-level fall-through) — e.g. the
 * start-tracking dedupe, which must distinguish "this container already has the ship"
 * from "some other level's container has it".
 */
public final class IplClientLookupBridge {

    private static final ThreadLocal<Boolean> LOCAL_ONLY = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private IplClientLookupBridge() {}

    public static boolean isLocalOnly() {
        return LOCAL_ONLY.get();
    }

    public static <T> T withLocalOnly(Supplier<T> action) {
        boolean prev = LOCAL_ONLY.get();
        LOCAL_ONLY.set(Boolean.TRUE);
        try {
            return action.get();
        } finally {
            LOCAL_ONLY.set(prev);
        }
    }
}

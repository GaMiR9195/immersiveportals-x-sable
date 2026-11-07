package ipl.sable.client;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** Render-time scheduling independent of Minecraft, with arrival ordering per material body. */
final class IplSessionTimeline {
    /** A stamp beyond Sable's maximum render delay is a reset timeline, not a pending tick. */
    private static final int MAX_TIMED_LEAD = 100;

    private IplSessionTimeline() {}

    static double renderTime(double previous, double current, float partial) {
        return previous + (current - previous) * partial;
    }

    static boolean shown(int tick, double renderTime) {
        return renderTime >= tick || tick - renderTime > MAX_TIMED_LEAD;
    }

    /** A future packet only blocks subsequent packets of the SAME body. */
    static <T, K> void drain(ArrayDeque<T> queue, Function<T, K> body,
                             Predicate<T> due, Consumer<T> apply) {
        Set<K> blocked = new HashSet<>();
        var iterator = queue.iterator();
        while (iterator.hasNext()) {
            T value = iterator.next();
            K key = body.apply(value);
            if (blocked.contains(key)) continue;
            if (!due.test(value)) {
                blocked.add(key);
                continue;
            }
            iterator.remove();
            apply.accept(value);
        }
    }
}

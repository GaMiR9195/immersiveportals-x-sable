package ipl.sable.dim;

import dev.ryanhcode.sable.api.physics.object.ArbitraryPhysicsObject;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Records which physics system a REDIRECTED arbitrary physics object was actually added to
 * (see {@code IplHostedPhysicsObjectRoutingMixin}), so removal paths that run without an
 * armed context (chunk unload, block break) still remove it from the right system.
 */
public final class IplHostedPhysicsObjects {

    private static final Map<ArbitraryPhysicsObject, SubLevelPhysicsSystem> OWNERS =
        Collections.synchronizedMap(new WeakHashMap<>());

    private IplHostedPhysicsObjects() {}

    public static void recordOwner(ArbitraryPhysicsObject object, SubLevelPhysicsSystem system) {
        OWNERS.put(object, system);
    }

    @Nullable
    public static SubLevelPhysicsSystem takeOwner(ArbitraryPhysicsObject object) {
        return OWNERS.remove(object);
    }
}

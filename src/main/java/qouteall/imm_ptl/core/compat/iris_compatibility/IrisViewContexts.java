package qouteall.imm_ptl.core.compat.iris_compatibility;

import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPMcHelper;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Temporal state of shader packs, kept per view.
 *
 * <p>Iris holds one copy of everything a pack carries from frame to frame: previous camera
 * position and matrices, smoothed uniforms, history buffers. Portal views render the pipeline
 * again inside the frame, so without separation each view reads what the previous view left:
 * motion blur and TAA see the jump between the player's camera and a portal camera, and
 * smoothing advances once per view instead of once per frame.
 *
 * <p>A view is identified by its portal path (empty for the player's own view). Temporal owners
 * keep one {@link Slot} per view and advance it at most once per frame; owners whose fields are
 * read outside their update use a {@link Binding} so the fields always hold the active view's
 * state. GPU resources of a view are {@linkplain Context#attachment attachments} and are freed
 * with the view when it has not been rendered for a while.
 *
 * <p>Holds no Iris types. Render thread only.
 */
public final class IrisViewContexts {
    private static final int EVICT_AFTER_FRAMES = 120;

    /** State that can be swapped in and out of an owner's own fields. */
    public interface Temporal {
        Object ip_captureTemporal();

        /** {@code null}: the view has no state yet, start fresh. */
        void ip_restoreTemporal(@Nullable Object state);
    }

    public static final class Slot {
        public @Nullable Object state;
        private int advancedFrame = -1;

        /** True once per frame: the owner may advance its state now. */
        public boolean advance() {
            if (advancedFrame == frame) return false;
            advancedFrame = frame;
            return true;
        }
    }

    public static final class Context {
        private final IdentityHashMap<Object, Slot> slots = new IdentityHashMap<>();
        private final HashMap<Object, AutoCloseable> attachments = new HashMap<>();
        private int lastUsedFrame;
        private boolean disposed;

        public Slot slot(Object owner) {
            return slots.computeIfAbsent(owner, o -> new Slot());
        }

        public boolean isDisposed() {
            return disposed;
        }

        @SuppressWarnings("unchecked")
        public <T extends AutoCloseable> T attachment(Object key, Supplier<T> factory) {
            return (T) attachments.computeIfAbsent(key, k -> factory.get());
        }

        @SuppressWarnings("unchecked")
        public <T extends AutoCloseable> @Nullable T peekAttachment(Object key) {
            return (T) attachments.get(key);
        }

        private void clear() {
            slots.clear();
            for (AutoCloseable attachment : attachments.values()) {
                try {
                    attachment.close();
                }
                catch (Exception ignored) {
                }
            }
            attachments.clear();
        }

        private void dispose() {
            clear();
            disposed = true;
        }
    }

    /** Keeps an owner's fields holding the state of the active view. */
    public static final class Binding {
        private @Nullable Context bound;

        public void bind(Temporal owner) {
            Context active = active();
            if (bound == active) return;
            if (bound != null && !bound.disposed) {
                bound.slot(owner).state = owner.ip_captureTemporal();
            }
            owner.ip_restoreTemporal(active.slot(owner).state);
            bound = active;
        }

        /** Binds, then reports whether the owner may advance this frame. */
        public boolean bindAndAdvance(Temporal owner) {
            bind(owner);
            return active().slot(owner).advance();
        }
    }

    /** The player's own view. */
    private static Context main = new Context();
    private static final HashMap<String, Context> PORTAL_VIEWS = new HashMap<>();
    private static final ArrayDeque<Context> STACK = new ArrayDeque<>();
    private static int frame;
    private static int uniformEpoch;
    /** current() is asked per draw; the path rarely changes between asks. */
    private static @Nullable List<Portal> cachedPath;
    private static @Nullable Context cachedContext;

    private IrisViewContexts() {}

    public static void onFrameStart() {
        frame++;
        uniformEpoch++;
        STACK.clear();
        evictUnused();
    }

    /** Enters the view of the world render about to start. */
    public static void pushCurrentView() {
        Context context = current();
        context.lastUsedFrame = frame;
        STACK.push(context);
        uniformEpoch++;
    }

    /**
     * The context of the view at the current portal path, also after its world render ended
     * (when its portals are composited).
     */
    public static Context current() {
        if (!PortalRendering.isRendering()) return main;
        if (cachedPath != null && cachedContext != null && !cachedContext.disposed
            && PortalRendering.isPortalPath(cachedPath)) {
            return cachedContext;
        }
        cachedPath = PortalRendering.getPortalPath();
        cachedContext = PORTAL_VIEWS.computeIfAbsent(keyOf(cachedPath), k -> new Context());
        return cachedContext;
    }

    /**
     * The player went through {@code portal}, so every view is now seen from its far side. Views
     * through it lose it from their path (the view through it becomes the player's view); the
     * player's old views are reached back through the reverse portal. Previous camera, matrices,
     * smoothing and history buffers carry on across the teleport, nested views included.
     */
    public static void onPlayerTeleported(Portal portal) {
        String throughKey = keyOf(List.of(portal));
        Context through = PORTAL_VIEWS.get(throughKey);
        if (through == null) return;
        Portal reverse = findReversePortal(portal);
        String reverseKey = reverse == null ? null : keyOf(List.of(reverse));

        HashMap<String, Context> moved = new HashMap<>();
        for (Map.Entry<String, Context> entry : PORTAL_VIEWS.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(throughKey) && entry.getValue() != through) {
                moved.put(key.substring(throughKey.length()), entry.getValue());
            }
        }
        for (Map.Entry<String, Context> entry : PORTAL_VIEWS.entrySet()) {
            Context context = entry.getValue();
            if (entry.getKey().startsWith(throughKey)) continue;
            // a view reached both ways keeps the one seen from the new side
            if (reverseKey == null || moved.putIfAbsent(reverseKey + entry.getKey(), context) != null) {
                context.dispose();
            }
        }
        if (reverseKey != null) {
            Context replaced = moved.put(reverseKey, main);
            if (replaced != null) replaced.dispose();
        }
        else {
            main.dispose();
        }

        main = through;
        PORTAL_VIEWS.clear();
        PORTAL_VIEWS.putAll(moved);
        cachedPath = null;
        cachedContext = null;
        uniformEpoch++;
    }

    private static @Nullable Portal findReversePortal(Portal portal) {
        var destWorld = ClientWorldLoader.getOptionalWorld(portal.getDestDim());
        if (destWorld == null) return null;
        return IPMcHelper.getNearbyPortals(destWorld, portal.getDestPos(), 1)
            .filter(p -> Portal.isReversePortal(portal, p))
            .findFirst().orElse(null);
    }

    private static String keyOf(List<Portal> path) {
        StringBuilder key = new StringBuilder();
        for (Portal portal : path) {
            key.append(portal.getUUID()).append('/');
        }
        return key.toString();
    }

    public static void popView() {
        if (!STACK.isEmpty()) STACK.pop();
        uniformEpoch++;
    }

    public static Context active() {
        Context context = STACK.peek();
        return context == null ? main : context;
    }

    public static boolean isMainViewActive() {
        return active() == main;
    }

    public static int frame() {
        return frame;
    }

    /**
     * Changes whenever another view starts or a frame begins: per-frame uniforms are refreshed
     * on this instead of the frame counter, so every view uploads its own values.
     */
    public static int uniformEpoch() {
        return uniformEpoch;
    }

    /** The pipeline was rebuilt: every owner and GPU resource of the old one is gone. */
    public static void reset() {
        main.clear();
        PORTAL_VIEWS.values().forEach(Context::dispose);
        PORTAL_VIEWS.clear();
        uniformEpoch++;
    }

    private static void evictUnused() {
        Iterator<Map.Entry<String, Context>> it = PORTAL_VIEWS.entrySet().iterator();
        while (it.hasNext()) {
            Context context = it.next().getValue();
            if (frame - context.lastUsedFrame > EVICT_AFTER_FRAMES) {
                context.dispose();
                it.remove();
            }
        }
    }
}

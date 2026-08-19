package ipl.sable.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kill switch and self-check for the hosted one-block render routing installed by
 * {@code IplHostedSingleBlockChunkRenderMixin}.
 *
 * <p>That mixin forces hosted sub-levels onto Sable's CHUNKED render path because every
 * portal clip in this mod is attached to that path only. The one-block path bakes
 * {@code modelView * transform} into its vertices, so its {@code Position} attribute is in
 * EYE space, while IP's slot-0 clipping equation is built in camera-relative WORLD space --
 * dotting one against the other yields a cut plane rotated by the inverse camera rotation,
 * which is exactly the "crooked triangular slice that moves when I turn my head" symptom.
 *
 * <p>Two things follow, and both are the reason this class exists:
 *
 * <ul>
 *   <li>The routing is load-bearing for correctness, but it is an injection into a
 *       third-party method ({@code VanillaSubLevelRenderDispatcher.isSingleBlock}) declared
 *       with {@code require = 0}. If Sable renames or inlines that method, the injector
 *       silently does nothing and the eye-space cut comes back with no error anywhere.
 *       {@link #verifyRoutingHook(boolean)} turns that silence into one warning.</li>
 *   <li>If the routing itself ever regresses (batching, lighting, or a Sable change that
 *       makes chunk-rendering a 1x1x1 plot expensive), {@code -Dipl.sable.forceChunkedForHosted=false}
 *       restores Sable's own decision without a rebuild.</li>
 * </ul>
 */
public final class IplHostedRenderRouting {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-render-routing");

    /** Read once: {@code isSingleBlock} is called per sub-level per frame. */
    private static final boolean FORCE_CHUNKED_FOR_HOSTED = !"false".equalsIgnoreCase(
        System.getProperty("ipl.sable.forceChunkedForHosted", "true"));

    private static volatile boolean routingHookFired = false;
    private static volatile boolean warned = false;

    private IplHostedRenderRouting() {}

    public static boolean forceChunkedForHosted() {
        return FORCE_CHUNKED_FOR_HOSTED;
    }

    /** Called from the injector, for any sub-level, to prove the injection landed. */
    public static void markRoutingHookFired() {
        if (!routingHookFired) routingHookFired = true;
    }

    /**
     * Warns once if a hosted client sub-level exists but the routing injector has never run.
     * Driven from {@code IplParentDimSync.clientHeartbeat()} (5s cadence).
     */
    public static void verifyRoutingHook(boolean sawHostedClientSubLevel) {
        if (warned || routingHookFired || !sawHostedClientSubLevel || !FORCE_CHUNKED_FOR_HOSTED) return;
        warned = true;
        LOG.warn("[IPL-CLIP-PATCH] hosted sub-levels are present but the isSingleBlock routing "
            + "injector never fired -- Sable's VanillaSubLevelRenderDispatcher.isSingleBlock was "
            + "probably renamed. One-block hosted bodies will render through the eye-space "
            + "single-block path, which this mod does not clip: expect a camera-dependent "
            + "triangular cut near portals until the mixin target is updated.");
    }
}

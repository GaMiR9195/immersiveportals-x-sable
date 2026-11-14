package ipl.sable.render;

import foundry.veil.forge.event.ForgeVeilAddShaderProcessorsEvent;
import net.neoforged.bus.api.IEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loaded only when Veil is present on the classpath. The caller in
 * {@code IPModEntryClient.onInitializeClient} guards entry with
 * {@code ModList.isLoaded("veil")} so this class is never referenced -- and
 * therefore never verified -- on a Veil-less install. See the long-form
 * rationale on {@link IplVeilShaderPreProcessor} for why this hookup is
 * necessary.
 */
public final class IplVeilCompat {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-veil-preprocess");

    /**
     * The Veil {@link ShaderProcessorList} stores preprocessors in an unbounded
     * {@code List} and nulls its cached composed processor on every add, so
     * registering inside the event handler — which fires once *per compile cycle*,
     * not once per init — would grow the list linearly across the session.
     * Latch to register the singleton instance exactly once.
     */
    private static volatile IplVeilShaderPreProcessor INSTANCE;

    private IplVeilCompat() {}

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(ForgeVeilAddShaderProcessorsEvent.class, event -> {
            if (INSTANCE == null) {
                INSTANCE = new IplVeilShaderPreProcessor();
                LOG.debug("Created IplVeilShaderPreProcessor singleton");
            }

            // Last: the clip writes must follow every other modification of gl_Position.
            event.addPreprocessor(INSTANCE, false);
        });
    }
}

package top.leonx.irisveil.compat.veil;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VeilCompatRegistryTest {
    @Test
    void registeredShaderReplacementExclusionsAreApplied() {
        String blocked = "testmod:direct_world_effect";
        String regular = "testmod:regular_geometry";

        assertTrue(VeilCompatRegistry.shouldReplaceShader(blocked));

        VeilCompatRegistry.excludeShaderReplacement(blocked);

        assertFalse(VeilCompatRegistry.shouldReplaceShader(blocked));
        assertTrue(VeilCompatRegistry.shouldReplaceShader(regular));
    }

    @Test
    void externalStateGenerationTracksRegisteredStatesAsStableBitmask() {
        AtomicBoolean firstActive = new AtomicBoolean(false);
        AtomicBoolean secondActive = new AtomicBoolean(false);

        int firstMask = VeilCompatRegistry.registerExternalRenderState("test:first", firstActive::get);
        int secondMask = VeilCompatRegistry.registerExternalRenderState("test:second", secondActive::get);

        assertNotEquals(firstMask, secondMask);
        assertEquals(0, VeilCompatRegistry.getExternalRenderStateGeneration() & (firstMask | secondMask));

        firstActive.set(true);
        assertEquals(firstMask, VeilCompatRegistry.getExternalRenderStateGeneration() & (firstMask | secondMask));

        secondActive.set(true);
        assertEquals(firstMask | secondMask, VeilCompatRegistry.getExternalRenderStateGeneration() & (firstMask | secondMask));

        firstActive.set(false);
        assertEquals(secondMask, VeilCompatRegistry.getExternalRenderStateGeneration() & (firstMask | secondMask));
    }

    @Test
    void inactiveWorldHooksDoNotBindFramebufferOrRender() {
        AtomicBoolean active = new AtomicBoolean(false);
        AtomicBoolean framebufferBound = new AtomicBoolean(false);
        AtomicBoolean rendered = new AtomicBoolean(false);
        AtomicBoolean restored = new AtomicBoolean(false);

        VeilCompatRegistry.registerWorldRenderHook(
            "test:inactive_world_hook",
            new int[] { 0 },
            active::get,
            (camera, gameRenderer) -> {
                rendered.set(true);
                return true;
            });

        VeilCompatRegistry.renderWorldHooks(
            null,
            null,
            drawBuffers -> {
                framebufferBound.set(true);
                return true;
            },
            () -> restored.set(true));

        assertFalse(framebufferBound.get());
        assertFalse(rendered.get());
        assertFalse(restored.get());
    }
}

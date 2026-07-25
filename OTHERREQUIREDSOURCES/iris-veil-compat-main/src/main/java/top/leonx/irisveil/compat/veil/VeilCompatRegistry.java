package top.leonx.irisveil.compat.veil;

import net.minecraft.resources.ResourceLocation;
import top.leonx.irisveil.IrisVeilCompat;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * Internal registry for optional mod-specific compatibility hooks.
 */
public final class VeilCompatRegistry {
    private static final Set<String> SHADER_REPLACEMENT_EXCLUSIONS =
        ConcurrentHashMap.newKeySet();
    private static final Map<String, ExternalRenderState> EXTERNAL_RENDER_STATES_BY_ID =
        new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<ExternalRenderState> EXTERNAL_RENDER_STATES =
        new CopyOnWriteArrayList<>();
    private static final Map<String, WorldRenderHookEntry> WORLD_RENDER_HOOKS_BY_ID =
        new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<WorldRenderHookEntry> WORLD_RENDER_HOOKS =
        new CopyOnWriteArrayList<>();

    private static int nextExternalRenderStateMask = 1;

    private VeilCompatRegistry() {
    }

    public static void excludeShaderReplacement(ResourceLocation shaderPath) {
        excludeShaderReplacement(shaderPath.toString());
    }

    static void excludeShaderReplacement(String shaderPath) {
        SHADER_REPLACEMENT_EXCLUSIONS.add(Objects.requireNonNull(shaderPath, "shaderPath"));
    }

    public static boolean shouldReplaceShader(ResourceLocation shaderPath) {
        return shouldReplaceShader(shaderPath.toString());
    }

    static boolean shouldReplaceShader(String shaderPath) {
        return !SHADER_REPLACEMENT_EXCLUSIONS.contains(shaderPath);
    }

    public static synchronized int registerExternalRenderState(String id, BooleanSupplier active) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(active, "active");

        ExternalRenderState existing = EXTERNAL_RENDER_STATES_BY_ID.get(id);
        if (existing != null) {
            return existing.mask();
        }

        if (nextExternalRenderStateMask == 0) {
            throw new IllegalStateException("Too many Veil compat external render states");
        }

        int mask = nextExternalRenderStateMask;
        nextExternalRenderStateMask <<= 1;
        ExternalRenderState state = new ExternalRenderState(id, mask, active);
        EXTERNAL_RENDER_STATES_BY_ID.put(id, state);
        EXTERNAL_RENDER_STATES.add(state);
        return mask;
    }

    public static int getExternalRenderStateGeneration() {
        int generation = 0;
        for (ExternalRenderState state : EXTERNAL_RENDER_STATES) {
            if (state.isActive()) {
                generation |= state.mask();
            }
        }
        return generation;
    }

    public static void registerWorldRenderHook(
            String id,
            int[] drawBuffers,
            BooleanSupplier shouldRender,
            WorldRenderCallback callback) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(drawBuffers, "drawBuffers");
        Objects.requireNonNull(shouldRender, "shouldRender");
        Objects.requireNonNull(callback, "callback");

        int[] drawBuffersCopy = drawBuffers.clone();
        WorldRenderHookEntry entry = new WorldRenderHookEntry(id, drawBuffersCopy, shouldRender, callback);
        if (WORLD_RENDER_HOOKS_BY_ID.putIfAbsent(id, entry) == null) {
            WORLD_RENDER_HOOKS.add(entry);
        }
    }

    public static void renderWorldHooks(
            Object camera,
            Object gameRenderer,
            CompatFramebufferBinder framebufferBinder,
            Runnable restoreMainTarget) {
        Objects.requireNonNull(framebufferBinder, "framebufferBinder");
        Objects.requireNonNull(restoreMainTarget, "restoreMainTarget");

        for (WorldRenderHookEntry hook : WORLD_RENDER_HOOKS) {
            hook.render(camera, gameRenderer, framebufferBinder, restoreMainTarget);
        }
    }

    private record ExternalRenderState(String id, int mask, BooleanSupplier active) {
        private boolean isActive() {
            try {
                return active.getAsBoolean();
            } catch (RuntimeException | LinkageError e) {
                IrisVeilCompat.LOGGER.debug(
                    "IrisVeilCompat: external render state '{}' is unavailable: {}",
                    id,
                    e.getMessage());
                return false;
            }
        }
    }

    private record WorldRenderHookEntry(
            String id,
            int[] drawBuffers,
            BooleanSupplier shouldRender,
            WorldRenderCallback callback) {
        private void render(
                Object camera,
                Object gameRenderer,
                CompatFramebufferBinder framebufferBinder,
                Runnable restoreMainTarget) {
            try {
                if (!shouldRender.getAsBoolean()) {
                    return;
                }

                if (!framebufferBinder.bind(drawBuffers.clone())) {
                    return;
                }

                try {
                    callback.render(camera, gameRenderer);
                } finally {
                    restoreMainTarget.run();
                }
            } catch (RuntimeException | LinkageError e) {
                IrisVeilCompat.LOGGER.warn("IrisVeilCompat: compat world render hook '{}' failed", id, e);
            }
        }
    }

    @FunctionalInterface
    public interface CompatFramebufferBinder {
        boolean bind(int[] drawBuffers);
    }

    @FunctionalInterface
    public interface WorldRenderCallback {
        boolean render(Object camera, Object gameRenderer);
    }
}

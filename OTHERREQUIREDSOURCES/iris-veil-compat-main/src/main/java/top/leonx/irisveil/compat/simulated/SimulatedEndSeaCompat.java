package top.leonx.irisveil.compat.simulated;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import top.leonx.irisveil.IrisVeilCompat;
import top.leonx.irisveil.compat.veil.VeilCompatRegistry;

import java.lang.reflect.Method;

public class SimulatedEndSeaCompat {
    private static final ResourceLocation END_SEA_SHADER =
        ResourceLocation.fromNamespaceAndPath("simulated", "end_sea");
    private static final String END_SEA_RENDERER =
        "dev.simulated_team.simulated.content.end_sea.EndSeaRenderer";
    private static final String END_SEA_SHADOW_RENDERER =
        "dev.simulated_team.simulated.content.end_sea.EndSeaShadowRenderer";
    private static final String CAMERA_CLASS = "net.minecraft.client.Camera";
    private static final String GAME_RENDERER_CLASS = "net.minecraft.client.renderer.GameRenderer";
    private static final int[] END_SEA_FINAL_COMPOSITE_DRAW_BUFFERS = {0};

    private static volatile @Nullable Method renderMethod;
    private static volatile boolean renderLookupFailed;
    private static volatile boolean registered;

    private SimulatedEndSeaCompat() {
    }

    public static synchronized void registerCompat() {
        if (registered) {
            return;
        }

        VeilCompatRegistry.excludeShaderReplacement(END_SEA_SHADER);
        VeilCompatRegistry.registerExternalRenderState(
            "simulated:end_sea_shadow",
            SimulatedEndSeaCompat::isRenderingEndSeaShadowMap);
        VeilCompatRegistry.registerWorldRenderHook(
            "simulated:end_sea",
            finalCompositeDrawBuffers(),
            SimulatedEndSeaCompat::shouldRenderWorldHook,
            SimulatedEndSeaCompat::render);
        registered = true;
    }

    public static boolean render(Object camera, Object gameRenderer) {
        Method method = getRenderMethod();
        if (method == null) {
            return false;
        }

        try {
            method.invoke(null, camera, gameRenderer);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            IrisVeilCompat.LOGGER.warn("IrisVeilCompat: failed to invoke Simulated End Sea renderer", e);
            return false;
        }
    }

    public static boolean isRenderingEndSeaShadowMap() {
        return isRenderingEndSeaShadowMap(END_SEA_SHADOW_RENDERER, "renderingShadowMap");
    }

    public static boolean isRenderingEndSeaShadowMap(String className, String methodName) {
        try {
            Class<?> rendererClass = Class.forName(className);
            Object value = rendererClass.getMethod(methodName).invoke(null);
            return value instanceof Boolean rendering && rendering;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    public static int[] finalCompositeDrawBuffers() {
        return END_SEA_FINAL_COMPOSITE_DRAW_BUFFERS.clone();
    }

    public static void prepareShadowMapRenderState() {
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
    }

    private static boolean shouldRenderWorldHook() {
        return !isRenderingEndSeaShadowMap() && getRenderMethod() != null;
    }

    private static @Nullable Method getRenderMethod() {
        Method method = renderMethod;
        if (method != null) {
            return method;
        }
        if (renderLookupFailed) {
            return null;
        }

        try {
            Class<?> rendererClass = Class.forName(END_SEA_RENDERER);
            Class<?> cameraClass = Class.forName(CAMERA_CLASS);
            Class<?> gameRendererClass = Class.forName(GAME_RENDERER_CLASS);
            method = rendererClass.getMethod("render", cameraClass, gameRendererClass);
            renderMethod = method;
            return method;
        } catch (ReflectiveOperationException | LinkageError e) {
            renderLookupFailed = true;
            IrisVeilCompat.LOGGER.debug("IrisVeilCompat: Simulated End Sea renderer is unavailable: {}", e.getMessage());
            return null;
        }
    }
}

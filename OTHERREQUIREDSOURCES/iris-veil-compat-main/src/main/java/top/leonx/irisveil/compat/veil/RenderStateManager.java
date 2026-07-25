package top.leonx.irisveil.compat.veil;

/**
 * Tracks current render pass state so that {@link IrisVeilProgramLinker}
 * can select the correct Iris program (gbuffer vs shadow).
 *
 * <p>Set by {@code MixinShadowRenderer} at the boundaries of the full
 * {@code ShadowRenderer.renderShadows()} call. This intentionally does not
 * depend on shaderpack block-entity shadow settings because Veil render-stage
 * hooks can still execute inside the shadow pass.
 */
public class RenderStateManager {
    private static boolean renderingShadow;

    public static boolean isRenderingShadow() {
        return renderingShadow;
    }

    public static void beginShadowPass(boolean shouldRenderBlockEntities) {
        renderingShadow = true;
    }

    public static void endShadowPass() {
        renderingShadow = false;
    }

    public static void setRenderingShadow(boolean renderingShadow) {
        RenderStateManager.renderingShadow = renderingShadow;
    }
}

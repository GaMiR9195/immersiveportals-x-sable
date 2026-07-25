package top.leonx.irisveil.compat.veil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderStateManagerTest {
    @AfterEach
    void resetShadowState() {
        RenderStateManager.endShadowPass();
    }

    @Test
    void shadowPassStateDoesNotDependOnBlockEntityShadowSetting() {
        RenderStateManager.beginShadowPass(false);

        assertTrue(RenderStateManager.isRenderingShadow());

        RenderStateManager.endShadowPass();
        assertFalse(RenderStateManager.isRenderingShadow());
    }
}

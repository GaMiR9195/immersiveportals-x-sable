package top.leonx.irisveil.compat.sable;

import top.leonx.irisveil.IrisVeilCompat;

public final class SableShadowCompat {
    private static boolean disabled;
    private static boolean unavailableLogged;
    private static boolean failureLogged;

    private SableShadowCompat() {
    }

    public static boolean renderSubLevelBlockEntities(
            Object level,
            Object renderBuffers,
            Object blockEntityRenderDispatcher,
            Object shadowModelView,
            double cameraX,
            double cameraY,
            double cameraZ,
            float partialTick) {
        if (disabled) {
            return false;
        }

        try {
            return SableShadowBridge.renderSubLevelBlockEntities(
                level,
                renderBuffers,
                blockEntityRenderDispatcher,
                shadowModelView,
                cameraX,
                cameraY,
                cameraZ,
                partialTick);
        } catch (NoClassDefFoundError e) {
            logUnavailable();
            return false;
        } catch (AssertionError | LinkageError | RuntimeException e) {
            logFailure(e);
            return false;
        }
    }

    static boolean renderSubLevelBlockEntities(
            Object level,
            Object renderBuffers,
            Object blockEntityRenderDispatcher,
            double cameraX,
            double cameraY,
            double cameraZ,
            float partialTick) {
        return renderSubLevelBlockEntities(
            level,
            renderBuffers,
            blockEntityRenderDispatcher,
            null,
            cameraX,
            cameraY,
            cameraZ,
            partialTick);
    }

    private static void logUnavailable() {
        disabled = true;
        if (!unavailableLogged) {
            unavailableLogged = true;
            IrisVeilCompat.LOGGER.debug("Sable shadow block entity bridge is unavailable");
        }
    }

    private static void logFailure(Throwable throwable) {
        disabled = true;
        if (!failureLogged) {
            failureLogged = true;
            IrisVeilCompat.LOGGER.warn("Disabling Sable shadow block entity bridge after compatibility failure", throwable);
        }
    }
}

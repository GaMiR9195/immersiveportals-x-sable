package ipl.sable.render;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplStraddleRenderState;
import ipl.sable.render.clip.IplClipPlanes;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3dc;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.util.Arrays;

/**
 * Debug output of the vanilla sub-level clip path ({@code ChunkedRenderDataMixin}), enabled by
 * {@code -Dipl.portalDiagnostics=true}. Every 5 seconds: how often a sub-level draw ran and
 * installed a piece cut, and the complete clip state of one sub-level draw inside a portal pass
 * (the program that draws, whether it carries the clip uniform, which clip distances GL has
 * enabled, the portal plane of the pass and on which side of it the body's pose lies). A draw
 * that should be cut by the portal plane but is not shows here which link is missing.
 * Render thread only.
 */
public final class IplClipDiagnostics {

    private static final boolean DIAGNOSTICS = Boolean.getBoolean("ipl.portalDiagnostics");
    private static final long REPORT_INTERVAL_NANOS = 5_000_000_000L;

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-clip");

    private static long calls;
    private static long installs;
    private static long lastReportNanos;
    private static long lastStateNanos;

    private IplClipDiagnostics() {}

    public static void onVanillaCall(boolean installed) {
        if (!DIAGNOSTICS) return;
        calls++;
        if (installed) installs++;
        long now = System.nanoTime();
        if (lastReportNanos == 0L) {
            lastReportNanos = now;
            return;
        }
        if (now - lastReportNanos < REPORT_INTERVAL_NANOS) return;
        LOG.info("[IPL-CLIP] last 5s: vanilla calls={} installs={}", calls, installs);
        calls = 0;
        installs = 0;
        lastReportNanos = now;
    }

    /** A sub-level draw with its shader bound; logs the clip state of one portal-pass draw per interval. */
    public static void onSubLevelDraw(ClientSubLevel sub) {
        if (!DIAGNOSTICS || !PortalRendering.isRendering()) return;
        long now = System.nanoTime();
        if (now - lastStateNanos < REPORT_INTERVAL_NANOS) return;
        lastStateNanos = now;

        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int location = program > 0 ? GL20.glGetUniformLocation(program, IplClipPlanes.UNIFORM) : -1;
        StringBuilder enabled = new StringBuilder();
        for (int i = 0; i < IplClipPlanes.SLOTS; i++) {
            enabled.append(GL11.glIsEnabled(GL30.GL_CLIP_DISTANCE0 + i) ? '1' : '0');
        }
        double[] portal = IplClipPlanes.plane(IplClipPlanes.PORTAL);
        Vec3 camera = CHelper.getCurrentCameraPos();
        Vector3dc pose = sub.renderPose().position();
        double poseSide = portal == null ? Double.NaN
            : portal[0] * (pose.x() - camera.x) + portal[1] * (pose.y() - camera.y)
                + portal[2] * (pose.z() - camera.z) + portal[3];
        LOG.info("[IPL-CLIP] portal={} layer={} sub={} projected={} program={} uniform={} enabled={} active0={} slot0={} poseSide={}",
            PortalRendering.getRenderingPortal().getUUID(), PortalRendering.getPortalLayer(), sub.getUniqueId(),
            IplStraddleRenderState.isActiveFor(sub), program, location, enabled,
            IplClipPlanes.isActive(IplClipPlanes.PORTAL), Arrays.toString(portal), String.format("%.2f", poseSide));
    }
}

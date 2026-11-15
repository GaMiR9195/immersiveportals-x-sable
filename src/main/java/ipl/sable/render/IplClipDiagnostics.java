package ipl.sable.render;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplStraddleRenderState;
import ipl.sable.render.clip.IplClipPlanes;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Debug output of the sub-level clip path ({@code ChunkedRenderDataMixin}), enabled by
 * {@code -Dipl.portalDiagnostics=true}.
 *
 * <p>For every sub-level drawn in a portal pass while any part of it lies on the CUT side of the
 * pass's portal plane (a draw that must be clipped), at most once per second per sub-level and
 * portal layer, the complete clip state as the GPU sees it at that moment: the bound program,
 * whether its vertex shader carries the clip writer, the clip uniform as READ BACK from the
 * program next to the value it should hold, which clip distances GL has enabled, the active slots,
 * the camera and the box of the sub-level against the plane. A leaking draw shows which link of
 * the chain is broken. Render thread only.
 */
public final class IplClipDiagnostics {

    private static final boolean DIAGNOSTICS = Boolean.getBoolean("ipl.portalDiagnostics");
    private static final long INTERVAL_NANOS = 1_000_000_000L;

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-clip");

    private static final Map<String, Long> LAST = new HashMap<>();
    private static final Int2ObjectOpenHashMap<String> WRITER = new Int2ObjectOpenHashMap<>();
    private static final FloatBuffer UNIFORM = BufferUtils.createFloatBuffer(IplClipPlanes.SLOTS * 4);

    private IplClipDiagnostics() {}

    public static void onVanillaCall(boolean installed) {
    }

    private static final it.unimi.dsi.fastutil.ints.IntOpenHashSet REPORTED = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();

    /** Right after an upload: an upload that does not land is reported once per program. */
    public static void afterUpload(int program, int location) {
        if (!DIAGNOSTICS) return;
        int error = GL11.glGetError();
        UNIFORM.clear();
        GL20.glGetUniformfv(program, location, UNIFORM);
        float[] expected = IplClipPlanes.clipSpaceEquations();
        boolean landed = UNIFORM.get(0) == expected[0] && UNIFORM.get(3) == expected[3];
        if ((error != 0 || !landed) && REPORTED.add(program)) {
            LOG.warn("[IPL-CLIP] upload to program {} at {} did not land: glError=0x{} read={} expected={}",
                program, location, Integer.toHexString(error), UNIFORM.get(0) + "," + UNIFORM.get(3),
                expected[0] + "," + expected[3]);
        }
    }

    /** A sub-level draw with its shader bound. */
    public static void onSubLevelDraw(ClientSubLevel sub) {
        if (!DIAGNOSTICS || !PortalRendering.isRendering()) return;
        double[] portal = IplClipPlanes.plane(IplClipPlanes.PORTAL);
        Vec3 camera = CHelper.getCurrentCameraPos();
        AABB box = sub.boundingBox().toMojang();
        double minSide = Double.NaN;
        double maxSide = Double.NaN;
        if (portal != null) {
            minSide = Double.MAX_VALUE;
            maxSide = -Double.MAX_VALUE;
            for (int i = 0; i < 8; i++) {
                double x = ((i & 1) == 0 ? box.minX : box.maxX) - camera.x;
                double y = ((i & 2) == 0 ? box.minY : box.maxY) - camera.y;
                double z = ((i & 4) == 0 ? box.minZ : box.maxZ) - camera.z;
                double d = portal[0] * x + portal[1] * y + portal[2] * z + portal[3];
                minSide = Math.min(minSide, d);
                maxSide = Math.max(maxSide, d);
            }
            if (minSide >= 0) return; // wholly on the kept side: nothing to clip
        }
        String key = sub.getUniqueId() + "/" + PortalRendering.getPortalLayer() + "/" + IplStraddleRenderState.isActiveFor(sub);
        long now = System.nanoTime();
        Long last = LAST.get(key);
        if (last != null && now - last < INTERVAL_NANOS) return;
        LAST.put(key, now);

        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int location = program > 0 ? GL20.glGetUniformLocation(program, IplClipPlanes.UNIFORM) : -1;
        String gpu = "-";
        if (location >= 0) {
            float[] values = new float[IplClipPlanes.SLOTS * 4];
            for (int i = 0; i < IplClipPlanes.SLOTS; i++) {
                int loc = GL20.glGetUniformLocation(program, IplClipPlanes.UNIFORM + "[" + i + "]");
                UNIFORM.clear();
                if (loc >= 0) GL20.glGetUniformfv(program, loc, UNIFORM);
                for (int c = 0; c < 4; c++) values[i * 4 + c] = loc >= 0 ? UNIFORM.get(c) : Float.NaN;
            }
            gpu = Arrays.toString(values);
        }
        String expected = Arrays.toString(IplClipPlanes.clipSpaceEquations());
        StringBuilder enabled = new StringBuilder();
        StringBuilder active = new StringBuilder();
        for (int i = 0; i < IplClipPlanes.SLOTS; i++) {
            enabled.append(GL11.glIsEnabled(GL30.GL_CLIP_DISTANCE0 + i) ? '1' : '0');
            active.append(IplClipPlanes.isActive(i) ? '1' : '0');
        }
        boolean depthClamp = GL11.glIsEnabled(0x864F);
        LOG.info("[IPL-CLIP] layer={} portal={} sub={} projected={} side=[{}, {}] program={} writer={} location={} enabled={} active={} depthClamp={} rev={} plane={}\n  gpu     ={}\n  expected={}",
            PortalRendering.getPortalLayer(), PortalRendering.getRenderingPortal().getUUID(), sub.getUniqueId(),
            IplStraddleRenderState.isActiveFor(sub), String.format("%.2f", minSide), String.format("%.2f", maxSide),
            program, writer(program), location, enabled, active, depthClamp, IplClipPlanes.revision(),
            Arrays.toString(portal), gpu, expected);
    }

    /** Whether the vertex stage of {@code program} carries the injected clip writer. */
    private static String writer(int program) {
        if (program <= 0) return "none";
        return WRITER.computeIfAbsent(program, p -> {
            IntBuffer count = BufferUtils.createIntBuffer(1);
            IntBuffer shaders = BufferUtils.createIntBuffer(8);
            GL20.glGetAttachedShaders(p, count, shaders);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < count.get(0); i++) {
                int shader = shaders.get(i);
                int type = GL20.glGetShaderi(shader, GL20.GL_SHADER_TYPE);
                String source = GL20.glGetShaderSource(shader);
                String stage = type == GL20.GL_VERTEX_SHADER ? "vs" : type == GL20.GL_FRAGMENT_SHADER ? "fs" : "s" + type;
                out.append(stage).append(source.contains("ipl_clipWrite") || source.contains("ipl_clipEmitVertex") ? "+" : "-").append(' ');
            }
            return out.toString().trim();
        });
    }
}

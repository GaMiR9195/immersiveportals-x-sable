package qouteall.imm_ptl.core.render;

import org.joml.Matrix4f;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;

import java.util.ArrayList;
import java.util.List;

/**
 * Background boxes of the name tags drawn in the latest world render.
 *
 * <p>With shader packs, portal content is composited after the view; name tags write no depth
 * where they are see-through, so the content would cover them. Their boxes are kept in front of
 * portals instead (IrisForegroundMask).
 */
public final class NameTagFootprints {
    /** A box in name tag space; {@code pose} maps it to camera-relative world space. */
    public record Footprint(Matrix4f pose, float x0, float y0, float x1, float y1) {}

    private static final List<Footprint> RECORDED = new ArrayList<>();

    private NameTagFootprints() {}

    public static void onWorldRenderStart() {
        RECORDED.clear();
    }

    /** Called for each name tag text drawn with a background. */
    public static void record(Matrix4f pose, float x, float y, int width) {
        if (!IrisInterface.invoker.isShaders() || IrisInterface.invoker.isRenderingShadowMap()) return;
        // Matches the background effect of Font.StringRenderOutput.
        RECORDED.add(new Footprint(new Matrix4f(pose), x - 1, y - 1, x + width + 1, y + 9));
    }

    public static List<Footprint> snapshot() {
        return List.copyOf(RECORDED);
    }
}

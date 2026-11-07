package ipl.sable.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import java.util.List;

/** Actual production cache, isolated API fixtures; no game or GL renderer. */
public final class RenderCacheTest {
    private static int assertions;
    public static void main(String[] args) {
        ClientLevel source = new ClientLevel(), dest = new ClientLevel();
        ClientSubLevel ship = new ClientSubLevel();
        Object sourcePose = new Object(), mappedPose = new Object();
        long before = IplStraddleRenderCache.frameId();
        check(IplStraddleRenderCache.visibility(ship, sourcePose) == null, "no out-of-pass cache");
        IplStraddleRenderCache.begin(source);
        long frame = IplStraddleRenderCache.frameId();
        check(frame == before + 1, "outer pass advances frame");
        var projection = new IplClientProjections.StraddleProjection();
        IplStraddleRenderCache.projections(source, () -> List.of(projection));
        check(IplStraddleRenderCache.projections(source, List::of).getFirst() == projection, "projection list cached per pass");
        IplStraddleRenderCache.cacheVisibility(ship, sourcePose, true);
        IplStraddleRenderCache.cacheVisibility(ship, mappedPose, false);
        check(IplStraddleRenderCache.visibility(ship, sourcePose), "source pose visible");
        check(!IplStraddleRenderCache.visibility(ship, mappedPose), "mapped pose separately culled");
        IplStraddleRenderCache.begin(dest);
        check(IplStraddleRenderCache.frameId() == frame, "nested pass keeps frame");
        check(IplStraddleRenderCache.visibility(ship, sourcePose) == null, "visibility is pass-local");
        check(IplStraddleRenderCache.projections(dest, List::of).isEmpty(), "projection list is pass-local");
        IplStraddleRenderCache.cacheVisibility(ship, sourcePose, false);
        IplStraddleRenderCache.invalidateActivePasses();
        check(IplStraddleRenderCache.visibility(ship, sourcePose) == null, "inner visibility invalidated");
        IplStraddleRenderCache.end(dest);
        check(IplStraddleRenderCache.visibility(ship, sourcePose) == null, "outer visibility invalidated");
        check(IplStraddleRenderCache.projections(source, List::of).isEmpty(), "outer projection list invalidated");
        IplStraddleRenderCache.end(source);
        IplStraddleRenderCache.end(source);
        IplStraddleRenderCache.begin(source);
        check(IplStraddleRenderCache.frameId() == frame + 1, "next frame advances");
        IplStraddleRenderCache.end(source);
        System.out.println("PASS: " + assertions + " render-cache assertions");
    }
    private static void check(boolean value, String message) {
        assertions++; if (!value) throw new AssertionError(message);
    }
}

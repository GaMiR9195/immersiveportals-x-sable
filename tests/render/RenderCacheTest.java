package ipl.sable.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import qouteall.imm_ptl.core.portal.Portal;
import java.util.List;

/** Actual production cache, isolated API fixtures; no game or GL renderer. */
public final class RenderCacheTest {
    private static int assertions;
    public static void main(String[] args) {
        ClientLevel source = new ClientLevel(), dest = new ClientLevel();
        ClientSubLevel ship = new ClientSubLevel();
        Portal portal = new Portal();
        var plan = new IplClientProjections.RenderPlan();
        Object sourcePose=new Object(),mappedPose=new Object();
        long before = IplStraddleRenderCache.frameId();
        check(IplStraddleRenderCache.portalCandidates(source) == null, "no out-of-pass cache");
        IplStraddleRenderCache.begin(source);
        long frame = IplStraddleRenderCache.frameId();
        check(frame == before + 1, "outer pass advances frame");
        IplStraddleRenderCache.cachePortalCandidates(source, List.of(portal));
        IplStraddleRenderCache.cacheCanonicalPortalFace(portal, true);
        IplStraddleRenderCache.cachePlan(ship, plan);
        IplStraddleRenderCache.cacheRenderPortals(ship, List.of(portal));
        var projection = new IplClientProjections.StraddleProjection();
        IplStraddleRenderCache.projections(source, () -> List.of(projection));
        check(IplStraddleRenderCache.projections(source, List::of).getFirst() == projection, "projection list cached per pass");
        check(IplStraddleRenderCache.renderPortals(ship).size() == 1, "source session cached");
        IplStraddleRenderCache.cacheVisibility(ship,sourcePose,true);
        IplStraddleRenderCache.cacheVisibility(ship,mappedPose,false);
        check(IplStraddleRenderCache.visibility(ship,sourcePose),"source pose visible");
        check(!IplStraddleRenderCache.visibility(ship,mappedPose),"mapped pose separately culled");
        check(IplStraddleRenderCache.plan(ship) == plan, "plan cached per pass");
        IplStraddleRenderCache.begin(dest);
        check(IplStraddleRenderCache.visibility(ship,sourcePose)==null,"visibility is pass-local");
        check(IplStraddleRenderCache.plan(ship)==null,"plans are pass-local");
        IplStraddleRenderCache.cacheVisibility(ship,sourcePose,false);
        IplStraddleRenderCache.cachePlan(ship,plan);
        check(IplStraddleRenderCache.plan(ship)==plan,"inner plan cached");
        check(IplStraddleRenderCache.frameId() == frame, "nested pass keeps frame");
        check(IplStraddleRenderCache.portalCandidates(source).getFirst() == portal, "portal index shared across nested views");
        check(IplStraddleRenderCache.canonicalPortalFace(portal), "canonical identity shared");
        check(IplStraddleRenderCache.renderPortals(ship) == null, "resolved sessions are pass-local");
        IplStraddleRenderCache.cacheRenderPortals(ship, List.of());
        check(IplStraddleRenderCache.renderPortals(ship).isEmpty(), "empty result is cached");
        IplStraddleRenderCache.invalidateActivePasses();
        check(IplStraddleRenderCache.renderPortals(ship) == null, "invalidation clears inner session list");
        check(IplStraddleRenderCache.portalCandidates(source) == null, "invalidation clears shared portal index");
        check(IplStraddleRenderCache.visibility(ship,sourcePose)==null,"inner visibility invalidated");
        check(IplStraddleRenderCache.plan(ship)==null,"inner plan invalidated");
        IplStraddleRenderCache.end(dest);
        check(IplStraddleRenderCache.visibility(ship,sourcePose)==null,"outer visibility invalidated");
        check(IplStraddleRenderCache.plan(ship)==null,"outer plan invalidated");
        check(IplStraddleRenderCache.renderPortals(ship) == null, "invalidation also clears outer session list");
        check(IplStraddleRenderCache.projections(source, List::of).isEmpty(), "invalidation also clears outer projection list");
        IplStraddleRenderCache.end(source);
        IplStraddleRenderCache.begin(source);
        check(IplStraddleRenderCache.frameId() == frame + 1, "next frame advances");
        check(IplStraddleRenderCache.portalCandidates(source) == null, "portal index not retained across frames");
        IplStraddleRenderCache.end(source);
        System.out.println("PASS: " + assertions + " render-cache assertions");
    }
    private static void check(boolean value, String message) {
        assertions++; if (!value) throw new AssertionError(message);
    }
}

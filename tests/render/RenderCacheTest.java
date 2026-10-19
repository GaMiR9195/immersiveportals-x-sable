package ipl.sable.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.render.SourceClipPortalFinder;
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
        var decision = new SourceClipPortalFinder.ClipDecision();
        Object sourcePose=new Object(),mappedPose=new Object();
        long before = IplStraddleRenderCache.frameId();
        check(IplStraddleRenderCache.portalCandidates(source) == null, "no out-of-pass cache");
        IplStraddleRenderCache.begin(source);
        long frame = IplStraddleRenderCache.frameId();
        check(frame == before + 1, "outer pass advances frame");
        IplStraddleRenderCache.cachePortalCandidates(source, List.of(portal));
        IplStraddleRenderCache.cacheCanonicalPortalFace(portal, true);
        IplStraddleRenderCache.cacheDecision(ship, decision);
        IplStraddleRenderCache.cacheRenderPortals(ship, List.of(portal));
        IplStraddleRenderCache.hosted(source, () -> List.of(ship));
        check(IplStraddleRenderCache.renderPortals(ship).size() == 1, "source session cached");
        IplStraddleRenderCache.cacheVisibility(ship,sourcePose,true);
        IplStraddleRenderCache.cacheVisibility(ship,mappedPose,false);
        check(IplStraddleRenderCache.visibility(ship,sourcePose),"source pose visible");
        check(!IplStraddleRenderCache.visibility(ship,mappedPose),"mapped pose separately culled");
        IplStraddleRenderCache.cacheAllDecisions(ship,List.of(decision));
        IplStraddleRenderCache.begin(dest);
        check(IplStraddleRenderCache.visibility(ship,sourcePose)==null,"visibility is pass-local");
        check(IplStraddleRenderCache.allDecisions(ship)==null,"all cuts are pass-local");
        IplStraddleRenderCache.cacheVisibility(ship,sourcePose,false);
        IplStraddleRenderCache.cacheAllDecisions(ship,List.of());
        check(IplStraddleRenderCache.allDecisions(ship).isEmpty(),"empty cuts cached");
        check(IplStraddleRenderCache.frameId() == frame, "nested pass keeps frame");
        check(IplStraddleRenderCache.portalCandidates(source).getFirst() == portal, "portal index shared across nested views");
        check(IplStraddleRenderCache.canonicalPortalFace(portal), "canonical identity shared");
        check(!IplStraddleRenderCache.hasDecision(ship), "clip decision is pass-local");
        check(IplStraddleRenderCache.renderPortals(ship) == null, "resolved sessions are pass-local");
        IplStraddleRenderCache.cacheRenderPortals(ship, List.of());
        check(IplStraddleRenderCache.renderPortals(ship).isEmpty(), "empty result is cached");
        IplStraddleRenderCache.cacheDecision(ship, null);
        check(IplStraddleRenderCache.hasDecision(ship), "negative clip decision is cached");
        IplStraddleRenderCache.invalidateActivePasses();
        check(IplStraddleRenderCache.renderPortals(ship) == null, "invalidation clears inner session list");
        check(!IplStraddleRenderCache.hasDecision(ship), "invalidation clears inner negative decision");
        check(IplStraddleRenderCache.portalCandidates(source) == null, "invalidation clears shared portal index");
        check(IplStraddleRenderCache.visibility(ship,sourcePose)==null,"inner visibility invalidated");
        check(IplStraddleRenderCache.allDecisions(ship)==null,"inner cuts invalidated");
        IplStraddleRenderCache.end(dest);
        check(IplStraddleRenderCache.visibility(ship,sourcePose)==null,"outer visibility invalidated");
        check(IplStraddleRenderCache.allDecisions(ship)==null,"outer cuts invalidated");
        check(IplStraddleRenderCache.renderPortals(ship) == null, "invalidation also clears outer session list");
        check(!IplStraddleRenderCache.hasDecision(ship), "invalidation also clears outer decision");
        check(IplStraddleRenderCache.hosted(source, List::of).isEmpty(), "invalidation also clears outer hosted list");
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

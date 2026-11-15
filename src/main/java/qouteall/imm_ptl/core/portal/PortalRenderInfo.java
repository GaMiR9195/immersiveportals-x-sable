package qouteall.imm_ptl.core.portal;

import com.mojang.logging.LogUtils;
import de.nick1st.imm_ptl.events.ClientPortalTickEvent;
import de.nick1st.imm_ptl.events.PortalDisposeEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.common.NeoForge;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.portal.animation.ClientPortalAnimationManagement;
import qouteall.imm_ptl.core.render.GlQueryObject;
import qouteall.imm_ptl.core.render.PortalScreenCulling;
import qouteall.imm_ptl.core.render.QueryManager;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.q_misc_util.Helper;

import java.lang.ref.Cleaner;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// A portal's rendering related things
// to access the package private field of Portal, this class is not in "render" package
@SuppressWarnings("resource")
//@Environment(EnvType.CLIENT)
public class PortalRenderInfo implements AutoCloseable {
    
    private static final Logger LOGGER = LogUtils.getLogger();

    public static class Visibility {
        public GlQueryObject lastFrameQuery;
        public GlQueryObject thisFrameQuery;
        public Boolean lastFrameRendered;
        public Boolean thisFrameRendered;
        
        public Visibility() {
            lastFrameQuery = null;
            thisFrameQuery = null;
            lastFrameRendered = null;
        }
        
        void update() {
            if (lastFrameQuery != null) {
                GlQueryObject.returnQueryObject(lastFrameQuery);
            }
            lastFrameQuery = thisFrameQuery;
            thisFrameQuery = null;
            lastFrameRendered = thisFrameRendered;
            thisFrameRendered = null;
        }
        
        void dispose() {
            if (lastFrameQuery != null) {
                GlQueryObject.returnQueryObject(lastFrameQuery);
            }
            if (thisFrameQuery != null) {
                GlQueryObject.returnQueryObject(thisFrameQuery);
            }
        }
        
        GlQueryObject acquireThisFrameQuery() {
            if (thisFrameQuery == null) {
                thisFrameQuery = GlQueryObject.acquireQueryObject();
            }
            return thisFrameQuery;
        }
    }
    
    private final Map<List<UUID>, Visibility> infoMap = new HashMap<>();
    
    public int thisFrameQueryFrameIndex = -1;
    
    private long mispredictTime1 = 0;
    private long mispredictTime2 = 0;
    
    private int totalMispredictCount = 0;
    
    public static void init() {
        NeoForge.EVENT_BUS.addListener(ClientPortalTickEvent.class, event -> {
            Portal portal = event.portal;
            PortalRenderInfo presentation = getOptional(portal);
            if (presentation != null) {
                presentation.tick(portal);
            }
        });

        NeoForge.EVENT_BUS.addListener(PortalDisposeEvent.class, event -> {
            Portal portal = event.portal;
            if (portal.level().isClientSide()) {
                PortalRenderInfo renderInfo = getOptional(portal);
                if (renderInfo != null) {
                    renderInfo.dispose();
                }
            }
        });
    }
    
    @Nullable
    public static PortalRenderInfo getOptional(Portal portal) {
        Validate.isTrue(portal.level().isClientSide());
        
        return portal.portalRenderInfo;
    }
    
    public static PortalRenderInfo get(Portal portal) {
        Validate.isTrue(portal.level().isClientSide());
        
        if (portal.portalRenderInfo == null) {
            portal.portalRenderInfo = new PortalRenderInfo();
        }
        return portal.portalRenderInfo;
    }
    
    public PortalRenderInfo() {
        CLEANER.register(this, getGcDirectedCleaningFunc());
    }
    
    private void tick(Portal portal) {
        Validate.isTrue(portal.level().isClientSide());
    }
    
    // disposing twice is fine
    public void dispose() {
        disposeInfoMap(infoMap);
    }
    
    private Runnable getGcDirectedCleaningFunc() {
        Map<List<UUID>, Visibility> infoMap1 = this.infoMap;
        // the disposal func should not reference this
        return () -> {
            LOGGER.debug("Running GC-directed PortalRenderInfo clean");
            
            // the cleaner runs on its own thread. Use the task list to avoid thread safety issue.
            IPGlobal.PRE_TOTAL_RENDER_TASK_LIST.addOneShotTask(() -> {
                disposeInfoMap(infoMap1);
            });
        };
    }
    
    @Override
    public void close() throws Exception {
        dispose();
    }
    
    // running it twice is fine
    private static void disposeInfoMap(Map<List<UUID>, Visibility> infoMap) {
        infoMap.values().forEach(Visibility::dispose);
        infoMap.clear();
    }
    
    // Visibility Predicting -----
    
    private void updateQuerySet() {
        if (RenderStates.frameIndex != thisFrameQueryFrameIndex) {
            
            if (RenderStates.frameIndex == thisFrameQueryFrameIndex + 1) {
                infoMap.entrySet().removeIf(entry -> {
                    Visibility visibility = entry.getValue();
                    
                    return visibility.lastFrameQuery == null &&
                        visibility.thisFrameQuery == null;
                });
                
                infoMap.values().forEach(Visibility::update);
            }
            else {
                disposeInfoMap(infoMap);
            }
            
            thisFrameQueryFrameIndex = RenderStates.frameIndex;
        }
    }
    
    @NotNull
    private Visibility getVisibility(List<UUID> desc) {
        updateQuerySet();
        
        return infoMap.computeIfAbsent(desc, k -> new Visibility());
    }
    
    private void onMispredict() {
        mispredictTime1 = mispredictTime2;
        mispredictTime2 = System.nanoTime();
        totalMispredictCount++;
    }
    
    private boolean isFrequentlyMispredicted() {
        if (totalMispredictCount > 5) {
            return true;
        }

        long currTime = System.nanoTime();

        return (currTime - mispredictTime1) < Helper.secondToNano(30);
    }
    
    private void updatePredictionStatus(Visibility visibility, boolean thisFrameDecision) {
        visibility.thisFrameRendered = thisFrameDecision;
        
        if (thisFrameDecision) {
            if (visibility.lastFrameRendered != null) {
                if (!visibility.lastFrameRendered) {
                    if (!isFrequentlyMispredicted()) {
                        onMispredict();
                    }
                }
            }
        }
    }
    
    /**
     * Renders the opening's query and decides whether the portal's world is rendered: visible
     * when at least {@link PortalScreenCulling#minVisibleSamples} samples passed, so a gap of a
     * few pixels between blocks does not trigger a whole world pass.
     *
     * <p>With offset queries the result of the previous frame decides, unless the portal was
     * fully hidden then and prediction is unreliable: then this frame's result is waited for.
     */
    public static boolean renderAndDecideVisibility(Portal portal, Runnable queryRendering) {
        if (!IPGlobal.offsetOcclusionQuery) {
            return QueryManager.renderAndDecideVisible(portal, queryRendering);
        }
        
        long minSamples = PortalScreenCulling.minVisibleSamples(portal, PortalRendering.getPortalLayer());
        PortalRenderInfo renderInfo = get(portal);
        Visibility visibility = renderInfo.getVisibility(WorldRenderInfo.getRenderingDescription());
        
        GlQueryObject lastFrameQuery = visibility.lastFrameQuery;
        GlQueryObject thisFrameQuery = visibility.acquireThisFrameQuery();
        thisFrameQuery.performQuerySampleNumPassed(queryRendering);
        
        if (lastFrameQuery == null) {
            return fetchNow(thisFrameQuery, minSamples);
        }
        
        long lastFrameSamples = lastFrameQuery.fetchSampleCount();
        boolean noPredict = renderInfo.isFrequentlyMispredicted() || QueryManager.queryStallCounter <= 3;
        if (lastFrameSamples == 0 && noPredict) {
            return fetchNow(thisFrameQuery, minSamples);
        }
        
        // A portal seen through a gap last frame stays culled until it opens up, without
        // stalling the pipeline every frame for it.
        boolean decision = lastFrameSamples >= minSamples;
        renderInfo.updatePredictionStatus(visibility, decision);
        return decision;
    }
    
    private static boolean fetchNow(GlQueryObject query, long minSamples) {
        ProfilerFiller profiler = Minecraft.getInstance().getProfiler();
        profiler.push("fetch_this_frame");
        boolean visible = query.fetchSampleCount() >= minSamples;
        profiler.pop();
        QueryManager.queryStallCounter++;
        return visible;
    }
    
    private static final Cleaner CLEANER = Cleaner.create();
}

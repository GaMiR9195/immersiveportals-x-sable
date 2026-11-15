package qouteall.imm_ptl.core.render;

import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import qouteall.imm_ptl.core.CHelper;

public class QueryManager {
    public static int queryStallCounter = 0;
    
    public static boolean isQuerying = false;
    private static int idQueryObject = -1;
    
    /** The query id is only ever used as a sample counter (its target is fixed by first use). */
    public static boolean renderAndGetDoesAnySamplePass(Runnable renderingFunc) {
        return renderAndGetSampleCountPassed(renderingFunc) > 0;
    }
    
    /** Whether enough of {@code portal}'s opening passed to be worth its world pass. */
    public static boolean renderAndDecideVisible(qouteall.imm_ptl.core.portal.Portal portal, Runnable renderingFunc) {
        long minSamples = qouteall.imm_ptl.core.render.PortalScreenCulling.minVisibleSamples(
            portal, qouteall.imm_ptl.core.render.context_management.PortalRendering.getPortalLayer()
        );
        return Integer.toUnsignedLong(renderAndGetSampleCountPassed(renderingFunc)) >= minSamples;
    }
    
    public static int renderAndGetSampleCountPassed(Runnable renderingFunc) {
        assert (!isQuerying);
        
        if (idQueryObject == -1) {
            idQueryObject = GL15.glGenQueries();
            CHelper.checkGlError();
        }
        
        GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, QueryManager.idQueryObject);
        
        isQuerying = true;
        
        renderingFunc.run();
        
        GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);
        
        isQuerying = false;
        
        int result = GL15.glGetQueryObjecti(QueryManager.idQueryObject, GL15.GL_QUERY_RESULT);
        queryStallCounter++;
        
        return result;
    }
}

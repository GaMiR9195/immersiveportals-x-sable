package qouteall.imm_ptl.core.render;

import net.minecraft.client.Minecraft;
import org.apache.commons.lang3.Validate;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import java.util.ArrayList;

public class GlQueryObject {
    private int idQueryObject = -1;
    private boolean isQuerying = false;
    private boolean hasResult = false;
    
    public GlQueryObject(int handle) {
        this.idQueryObject = handle;
    }
    
    /**
     * Pooled query objects are always used as sample counters: a query object's target is fixed
     * by its first use, so mixing ANY_SAMPLES_PASSED and SAMPLES_PASSED on recycled ids fails.
     */
    public void performQueryAnySamplePassed(Runnable renderingFunc) {
        performQuerySampleNumPassed(renderingFunc);
    }
    
    public void performQuerySampleNumPassed(Runnable renderingFunc) {
        performQuery(renderingFunc, GL15.GL_SAMPLES_PASSED);
    }
    
    private void performQuery(Runnable renderingFunc, int glQueryType) {
        Validate.isTrue(isValid());
        
        Validate.isTrue(!isQuerying);
        
        GL15.glBeginQuery(glQueryType, idQueryObject);
        
        isQuerying = true;
        
        renderingFunc.run();
        
        GL15.glEndQuery(glQueryType);
        
        isQuerying = false;
        
        hasResult = true;
    }
    
    public boolean fetchQueryResult() {
        return fetchSampleCount() != 0;
    }
    
    /** Whether the result can be read without waiting for the GPU. */
    public boolean isResultAvailable() {
        Validate.isTrue(isValid());
        Validate.isTrue(hasResult);
        
        return GL15.glGetQueryObjecti(idQueryObject, GL15.GL_QUERY_RESULT_AVAILABLE) != 0;
    }
    
    /** Samples that passed (waits for the result). */
    public long fetchSampleCount() {
        Validate.isTrue(isValid());
        Validate.isTrue(hasResult);
        
        return Integer.toUnsignedLong(GL15.glGetQueryObjecti(idQueryObject, GL15.GL_QUERY_RESULT));
    }
    
    private void dispose() {
        if (idQueryObject != -1) {
            GL15.glDeleteQueries(idQueryObject);
            idQueryObject = -1;
        }
    }
    
    public boolean isValid() {
        return idQueryObject != -1;
    }
    
    private void reset() {
        hasResult = false;
    }
    
    private static final ArrayList<GlQueryObject> queryObjects = new ArrayList<>();
    
    private static void prepareQueryObjects() {
        int[] buf = new int[500];
        GL15.glGenQueries(buf);
        for (int id : buf) {
            queryObjects.add(new GlQueryObject(id));
        }
    }
    
    public static GlQueryObject acquireQueryObject() {
        if (queryObjects.isEmpty()) {
            prepareQueryObjects();
        }
        
        return queryObjects.remove(queryObjects.size() - 1);
    }
    
    public static void returnQueryObject(GlQueryObject obj) {
        obj.reset();
        if (queryObjects.size() > 1500) {
            obj.dispose();
        }
        else {
            queryObjects.add(obj);
        }
    }
}

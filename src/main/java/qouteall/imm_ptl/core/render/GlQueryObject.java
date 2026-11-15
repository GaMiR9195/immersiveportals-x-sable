package qouteall.imm_ptl.core.render;

import org.apache.commons.lang3.Validate;
import org.lwjgl.opengl.GL15;

import java.util.ArrayList;

/**
 * A pooled occlusion query. Pooled objects are only ever used as {@code GL_SAMPLES_PASSED}
 * counters: a query object's target is fixed by its first use, so mixing targets on recycled ids
 * would fail.
 */
public class GlQueryObject {
    private int idQueryObject = -1;
    private boolean isQuerying = false;
    private boolean hasResult = false;
    
    public GlQueryObject(int handle) {
        this.idQueryObject = handle;
    }
    
    public void performQuerySampleNumPassed(Runnable renderingFunc) {
        Validate.isTrue(isValid());
        Validate.isTrue(!isQuerying);
        
        GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, idQueryObject);
        isQuerying = true;
        renderingFunc.run();
        GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);
        isQuerying = false;
        
        hasResult = true;
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

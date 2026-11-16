package qouteall.imm_ptl.core.compat.sodium_compatibility;

import com.mojang.blaze3d.vertex.VertexBuffer;
import net.caffeinemc.mods.sodium.client.render.immediate.CloudRenderer.CloudGeometry;
import net.caffeinemc.mods.sodium.client.render.immediate.CloudRenderer.CloudGeometryParameters;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Cloud meshes of one Sodium cloud renderer (one per level renderer, so per dimension), the
 * Sodium counterpart of {@link qouteall.imm_ptl.core.render.context_management.CloudContext}.
 *
 * <p>Sodium keeps a single mesh and rebuilds it whenever the camera's cloud cell or its height
 * relative to the clouds changes. The views of a frame (the player's and the portal passes into
 * the same dimension) look from different places, so they would rebuild it for every view of
 * every frame. Their meshes are kept here and swapped in instead. Render thread only.
 */
public final class CloudGeometryCache {
    private static final int CAPACITY = 4;
    
    /** Most recently used first. */
    private final ArrayDeque<CloudGeometry> entries = new ArrayDeque<>();
    
    /**
     * The geometry Sodium continues with for {@code params}: {@code current} when it matches, a
     * cached match, otherwise the geometry Sodium rebuilds into (null allocates a new buffer).
     */
    public @Nullable CloudGeometry select(@Nullable CloudGeometry current, CloudGeometryParameters params) {
        if (current != null && current.params().equals(params)) {
            return current;
        }
        if (!IPGlobal.cloudOptimization || RenderStates.getRenderedPortalNum() == 0) {
            // a single view: Sodium's own rebuild in place
            clear();
            return current;
        }
        CloudGeometry cached = take(params);
        if (current != null) {
            entries.addFirst(current);
        }
        if (cached != null) {
            trim();
            return cached;
        }
        // reuse the buffer of the least recently used mesh once the cache is full
        return entries.size() > CAPACITY ? entries.removeLast() : null;
    }
    
    public void clear() {
        for (CloudGeometry geometry : entries) {
            close(geometry);
        }
        entries.clear();
    }
    
    private @Nullable CloudGeometry take(CloudGeometryParameters params) {
        Iterator<CloudGeometry> iterator = entries.iterator();
        while (iterator.hasNext()) {
            CloudGeometry geometry = iterator.next();
            if (geometry.params().equals(params)) {
                iterator.remove();
                return geometry;
            }
        }
        return null;
    }
    
    private void trim() {
        while (entries.size() > CAPACITY) {
            close(entries.removeLast());
        }
    }
    
    private static void close(CloudGeometry geometry) {
        VertexBuffer buffer = geometry.vertexBuffer();
        if (buffer != null) {
            buffer.close();
        }
    }
}

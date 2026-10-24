package ipl.sable.client;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.neoforge.compatibility.flywheel.FlywheelCompatNeoForge;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import java.util.IdentityHashMap;
import java.util.Map;
/** Entity visual membership is independent of the honest entity storage world. */
public final class IplHostedEntityVisuals {
    private static final Map<Entity, Level> OWNERS = new IdentityHashMap<>();
    private IplHostedEntityVisuals() {}
    public static Level view(Level fallback, Entity entity) {
        Level tracked = OWNERS.get(entity);
        var sub = Sable.HELPER.getContaining(entity);
        if (sub != null && IplDimAgnostic.isHosted(sub)) {
            Level parent = IplDimAgnostic.getParentLevel(sub);
            if (parent != null) return parent;
        }
        return tracked != null ? tracked : fallback;
    }
    public static void sweep(SubLevelContainer hosting) {
        for (var entry : new IdentityHashMap<>(OWNERS).entrySet()) {
            Entity entity = entry.getKey();
            SubLevel sub = Sable.HELPER.getContaining(entity);
            Level target = sub != null && IplDimAgnostic.isHosted(sub) && !sub.isRemoved()
                ? IplDimAgnostic.getParentLevel(sub) : null;
            if (entity.isRemoved() || target != entry.getValue()) {
                var old = VisualizationManager.get(entry.getValue());
                if (old != null) old.entities().queueRemove(entity);
                OWNERS.remove(entity);
                if (!entity.isRemoved() && target == null) {
                    var nativeManager = VisualizationManager.get(entity.level());
                    if (nativeManager != null) nativeManager.entities().queueAdd(entity);
                }
            }
        }
        if (hosting.getLevel() instanceof ClientLevel storage) scan(storage);
        if (qouteall.imm_ptl.core.ClientWorldLoader.getIsInitialized()) {
            for (ClientLevel level : qouteall.imm_ptl.core.ClientWorldLoader.getClientWorlds()) scan(level);
        }
        var current = net.minecraft.client.Minecraft.getInstance().level;
        if (current != null) scan(current);
    }
    private static void scan(ClientLevel level) {
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.isRemoved() || OWNERS.containsKey(entity) || !VisualizationHelper.canVisualize(entity)) continue;
            var sub = Sable.HELPER.getContaining(entity);
            if (sub == null || !IplDimAgnostic.isHosted(sub) || sub.isRemoved()) continue;
            Level parent = IplDimAgnostic.getParentLevel(sub);
            if (parent == null) continue;
            var manager = VisualizationManager.get(parent);
            if (manager == null) continue;
            // A join event can precede plot/parent sync; create metadata when membership
            // actually becomes resolvable, not only on that first event.
            FlywheelCompatNeoForge.createRenderInfo(sub.getLevel(),sub);
            if (entity.level() != parent) {
                var old = VisualizationManager.get(entity.level());
                if (old != null) old.entities().queueRemove(entity);
            }
            manager.entities().queueAdd(entity);
            OWNERS.put(entity,parent);
        }
    }
    public static void clear() {
        OWNERS.forEach((entity,level) -> {
            var manager = VisualizationManager.get(level);
            if (manager != null) manager.entities().queueRemove(entity);
        });
        OWNERS.clear();
    }
}

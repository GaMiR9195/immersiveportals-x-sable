package ipl.sable.render;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.client.IplParentDimSync;
import ipl.sable.transit.IplContraptions;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * Logs how each contraption entity of a client level is drawn, once per change: its body, whether
 * the body is a hidden transit successor or cut by a portal, who draws it (Flywheel or Create's
 * renderer), whether Flywheel holds its visual and whether Sable has the body's render pose.
 * A contraption that vanishes shows here which of these broke.
 */
final class IplContraptionDiagnostics {

    /** Walks every entity of each flushed level, so off unless asked for. */
    static final boolean ENABLED = Boolean.getBoolean("ipl.contraptionDiagnostics");
    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-contraptions");
    private static final Map<Entity, String> LAST = Collections.synchronizedMap(new WeakHashMap<>());

    private IplContraptionDiagnostics() {}

    /** Flywheel flush of {@code level}: {@code visualized} tells whether Flywheel holds a visual. */
    static void report(ClientLevel level, Predicate<Entity> visualized) {
        if (!ENABLED) return;
        for (Entity entity : level.entitiesForRendering()) {
            if (!IplContraptions.isContraption(entity)) continue;
            String state = describe(entity, visualized);
            String last = LAST.put(entity, state);
            if (!state.equals(last)) {
                LOG.info("[IPL-CONTRAPTION] {} #{} {} {}", level.dimension().location(), entity.getId(),
                    last == null ? "joined:" : "now:", state);
            }
        }
        LAST.entrySet().removeIf(e -> {
            Entity entity = e.getKey();
            if (!entity.isRemoved() || entity.level() != level) return false;
            LOG.info("[IPL-CONTRAPTION] {} #{} removed ({})", level.dimension().location(), entity.getId(),
                entity.getRemovalReason());
            return true;
        });
    }

    private static String describe(Entity entity, Predicate<Entity> visualized) {
        SubLevel body = Sable.HELPER.getContaining(entity);
        StringBuilder out = new StringBuilder();
        out.append("body=").append(body == null ? "none" : body.getUniqueId().toString().substring(0, 8));
        if (body instanceof ClientSubLevel sub) {
            out.append(IplParentDimSync.isSuppressedSuccessor(sub) ? " hidden-successor" : "");
            out.append(IplPieceRender.frameFor(sub) != null ? " cut" : "");
        }
        out.append(IplContraptions.isHollow(entity) ? " hollow" : "");
        out.append(IplFlywheelFallback.isHanded(entity) ? " drawn-by=create" : " drawn-by=flywheel");
        out.append(visualized.test(entity) ? " visual" : " no-visual");
        out.append(IplContraptionRenderPoses.hasPose(entity) ? " pose" : " no-pose");
        return out.toString();
    }
}

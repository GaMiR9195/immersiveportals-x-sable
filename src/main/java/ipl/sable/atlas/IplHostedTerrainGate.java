package ipl.sable.atlas;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Hosted ships are always-live, so their parent-dim terrain must be too.
 *
 * <p>Stock Sable never simulates a sub-level over unloaded terrain: the holding system
 * serializes ships out of the world when their container chunks unload. Dim-agnostic
 * hosting deliberately disables mid-session holding (hosted ships stay active with no
 * players anywhere near), which silently dropped the other half of that invariant — the
 * terrain enrollment skips unloaded parent chunks, so a ship whose parent window has no
 * players (a nether ship while everyone is in the overworld) simulates against NOTHING
 * and falls through the world.
 *
 * <p>Two-part restoration, called from the parent-level enrollment pass each tick:
 * <ol>
 *   <li><b>Chunk loading:</b> every hosted ship keeps a vanilla region ticket over its
 *       terrain window in its parent dimension (short-lived, re-added while the ship
 *       exists — expiry cleans up removal and movement without bookkeeping).</li>
 *   <li><b>Hold gate:</b> until the chunk under the ship's origin is actually loaded
 *       (async load/gen takes many ticks), the body is frozen at its captured pose —
 *       teleport-hold + velocity reset each tick. No terrain, no gravity.</li>
 * </ol>
 *
 * <p>Kill switch: {@code -Dipl.sable.terrainChunkLoading=false} (disables both parts).
 */
public final class IplHostedTerrainGate {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-terrain-gate");

    private static final boolean ENABLED =
        !"false".equalsIgnoreCase(System.getProperty("ipl.sable.terrainChunkLoading", "true"));

    /** Re-added every tick; 60-tick expiry self-cleans when the ship moves or despawns. */
    private static final TicketType<ChunkPos> TERRAIN_TICKET = TicketType.create(
        "ipl_hosted_terrain", Comparator.comparingLong(ChunkPos::toLong), 60);

    private static final int MAX_TICKET_RADIUS =
        Integer.getInteger("ipl.sable.terrainTicketRadius", 8);

    private static final class Hold {
        final Vector3d position;
        final Quaterniond orientation;
        long lastTouchedMs;

        Hold(ServerSubLevel sub) {
            this.position = new Vector3d(sub.logicalPose().position());
            this.orientation = new Quaterniond(sub.logicalPose().orientation());
        }
    }

    private static final Map<UUID, Hold> HELD = new HashMap<>();

    private IplHostedTerrainGate() {}

    /**
     * Keep {@code sub}'s parent terrain window loaded and freeze the body while the
     * ground chunk under it hasn't arrived yet. {@code chunkBounds} is the enrollment
     * window (parent-frame chunk coords); {@code pipeline} is the parent's — per-body
     * calls forward to the real owning pipeline through the ownership guard.
     */
    public static void tick(
        ServerLevel parent, PhysicsPipeline pipeline, ServerSubLevel sub, BoundingBox3i chunkBounds
    ) {
        if (!ENABLED) return;

        var pos = sub.logicalPose().position();
        int cx = SectionPos.blockToSectionCoord(Mth.floor(pos.x()));
        int cz = SectionPos.blockToSectionCoord(Mth.floor(pos.z()));
        ChunkPos center = new ChunkPos(cx, cz);

        // Sable's isChunkLoadedEnough gate is BLOCK-TICKING range (chunk level <= 32); a
        // ticket at distance d yields level 33-d at center rising by 1 per chunk outward,
        // so the whole window needs d = windowRadius + 1.
        int radius = 1 + Math.max(
            Math.max(cx - chunkBounds.minX(), chunkBounds.maxX() - cx),
            Math.max(cz - chunkBounds.minZ(), chunkBounds.maxZ() - cz));
        radius = Mth.clamp(radius, 2, MAX_TICKET_RADIUS);
        parent.getChunkSource().addRegionTicket(TERRAIN_TICKET, center, radius, center);

        UUID id = sub.getUniqueId();
        // Gate on the ORIGIN chunk only: a player driving into ungenerated terrain keeps
        // their own chunks loaded (never held); the fall-through case is the whole window
        // missing, which this chunk always detects.
        if (PhysicsChunkTicketManager.isChunkLoadedEnough(parent, cx, cz)) {
            if (HELD.remove(id) != null) {
                pipeline.wakeUp(sub);
                LOG.info("[IPL-TERRAIN-GATE] released ship {} in {} — terrain loaded at {}",
                    id, parent.dimension().location(), center);
            }
            return;
        }

        Hold hold = HELD.get(id);
        if (hold == null) {
            hold = new Hold(sub);
            HELD.put(id, hold);
            LOG.info("[IPL-TERRAIN-GATE] holding ship {} in {} — terrain not loaded at {} "
                + "(ticket radius {})", id, parent.dimension().location(), center, radius);
        }
        hold.lastTouchedMs = System.currentTimeMillis();
        pipeline.resetVelocity(sub);
        pipeline.teleport(sub, hold.position, hold.orientation);

        // A held ship is re-touched every tick; entries stale for seconds belong to
        // removed/reparented ships. Swept here so no removal path needs to know us.
        if (HELD.size() > 1) {
            long cutoff = System.currentTimeMillis() - 5000;
            HELD.values().removeIf(h -> h.lastTouchedMs != 0 && h.lastTouchedMs < cutoff);
        }
    }

    /** Keep the dest-side terrain of a straddle image region streaming in. No hold —
     *  the ship's own gate covers its body; this only feeds the image's chart. */
    public static void ticketRegion(ServerLevel level, BoundingBox3i chunkBounds) {
        if (!ENABLED) return;
        int cx = (chunkBounds.minX() + chunkBounds.maxX()) / 2;
        int cz = (chunkBounds.minZ() + chunkBounds.maxZ()) / 2;
        int radius = 1 + Math.max(
            Math.max(cx - chunkBounds.minX(), chunkBounds.maxX() - cx),
            Math.max(cz - chunkBounds.minZ(), chunkBounds.maxZ() - cz));
        radius = Mth.clamp(radius, 2, MAX_TICKET_RADIUS);
        level.getChunkSource().addRegionTicket(TERRAIN_TICKET, new ChunkPos(cx, cz), radius,
            new ChunkPos(cx, cz));
    }

    public static void clearAll() {
        HELD.clear();
    }
}

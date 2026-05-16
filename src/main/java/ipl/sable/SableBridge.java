package ipl.sable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Public-facing soft dependency on Sable for the IP-Sable compatibility integration.
 *
 * <p>None of the methods on this class reference Sable types in their bytecode -- those
 * references live inside {@link SableImpl}, which the JVM only loads when its methods are
 * first invoked. We guard every dispatch on {@link #PRESENT} so that {@code SableImpl} is
 * never reached if Sable isn't on the classpath at runtime.
 *
 * <p>This means IP can be packaged and shipped without Sable as a required dep -- if Sable
 * isn't loaded, every method here is a no-op equivalent (returns {@code null} or an empty
 * fallback), and IP behaves identically to upstream.
 */
public final class SableBridge {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-bridge");

    /**
     * {@code true} iff Sable is loadable at the time this class was initialized.
     */
    public static final boolean PRESENT;

    static {
        boolean p;
        try {
            Class.forName("dev.ryanhcode.sable.Sable");
            p = true;
        } catch (Throwable t) {
            p = false;
        }
        PRESENT = p;
        if (PRESENT) {
            LOG.info("[IPL-SABLE] Sable detected on classpath; sub-level-aware integration active.");
        } else {
            LOG.info("[IPL-SABLE] Sable not present; IP runs in upstream-equivalent mode.");
        }
    }

    private SableBridge() {}

    /**
     * Walks the sub-levels in {@code world} that intersect {@code worldPos} and returns the
     * first non-air block found at the corresponding plot-local coordinate, mirroring Sable's
     * own source-side pattern in
     * {@code dev.ryanhcode.sable.mixin.entity.entity_sublevel_collision.EntityMixin#getInBlockState}.
     *
     * <p>Used by IP's cross-portal block-state lookup so that an entity standing under an
     * upward-facing portal whose destination is inside an airship sees the airship's deck
     * block instead of the air at the visible coordinate.
     *
     * @param world    the destination world to consult
     * @param worldPos the world-space position to test (e.g. {@code portal.transformPoint(entity.position())})
     * @return the non-air {@link BlockState} from an intersecting sub-level at {@code worldPos},
     *         or {@code null} if Sable is absent or no sub-level contributes a non-air block.
     */
    @Nullable
    public static BlockState lookupNonAirSubLevelBlockAt(Level world, Vec3 worldPos) {
        if (!PRESENT) return null;
        return SableImpl.lookupNonAirSubLevelBlockAt(world, worldPos);
    }

    /**
     * Tests whether {@code chunkPos} is inside the sub-level plot grid for {@code world}.
     *
     * <p>Sable allocates sub-levels far out in chunk coordinates (default origin around
     * {@code (10000, 10000)} chunks, ~160k blocks out). A chunk whose coordinates fall
     * inside that plot grid contains a sub-level's internal block storage; a chunk outside
     * is a normal world chunk.
     *
     * <p>Used by IP's {@code ChunkMap.getPlayers} replacement to route plot chunks to
     * Sable's sub-level-tracker lookup and leave normal chunks to IP's portal-aware
     * chunk tracking.
     *
     * @return {@code true} if the chunk is in Sable's plot grid; {@code false} if Sable is
     *         absent, the level has no plot container, or the chunk is in normal world space.
     */
    public static boolean isPlotChunk(Level world, ChunkPos chunkPos) {
        if (!PRESENT) return false;
        return SableImpl.isPlotChunk(world, chunkPos);
    }
}

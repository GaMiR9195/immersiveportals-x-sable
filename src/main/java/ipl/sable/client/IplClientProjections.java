package ipl.sable.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.render.IplPieceRender;
import ipl.sable.transit.IplProjectionTree;
import ipl.sable.transit.IplStraddlePoseMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Client view of portal projections in the native Sable model.
 *
 * <p>Every client sub-level lives in its own {@link ClientLevel}. While pieces of it went
 * through portals (server sessions and labels mirrored by {@link IplStraddleSessionStore}),
 * each such piece is drawn as a PROJECTION: the same compiled geometry at the session's mapped
 * pose, inside the destination level's render pass, restricted to the piece's own blocks
 * ({@link IplPieceRender}). Chained sessions project through the composite isometry of every
 * portal on their path.
 *
 * <p>Referenced from common code only behind {@code isClientSide} branches.
 */
public final class IplClientProjections {

    private IplClientProjections() {}

    /**
     * One image of {@code sub} in another chart: piece {@code node}, drawn at
     * {@code mappedPose}. {@code via} lists every portal of the path in order (size 1 for a
     * direct straddle); {@code portal} is the last of them.
     */
    public record StraddleProjection(
        ClientSubLevel sub,
        Portal portal,
        Pose3d mappedPose,
        List<Portal> via,
        IplStraddlePoseMap.StraddleMapping mapping,
        int node
    ) {
        public boolean isChained() { return via.size() > 1; }
    }

    // ------------------------------------------------------------------ level / sub lookup

    /** Client levels IP currently holds, without creating any. */
    public static Iterable<ClientLevel> clientLevels() {
        if (!ClientWorldLoader.getIsInitialized()) {
            ClientLevel main = Minecraft.getInstance().level;
            return main == null ? List.of() : List.of(main);
        }
        return ClientWorldLoader.getClientWorlds();
    }

    @Nullable
    public static ClientLevel clientLevel(@Nullable ResourceKey<Level> dimension) {
        if (dimension == null) return null;
        for (ClientLevel level : clientLevels()) {
            if (level.dimension() == dimension) return level;
        }
        return null;
    }

    /** The live client sub-level {@code id} in {@code dimension}, or null. */
    @Nullable
    public static ClientSubLevel findClientSub(UUID id, @Nullable ResourceKey<Level> dimension) {
        ClientLevel level = clientLevel(dimension);
        if (level == null) return null;
        SubLevelContainer container = SubLevelContainer.getContainer((Level) level);
        SubLevel sub = container == null ? null : container.getSubLevel(id);
        return sub instanceof ClientSubLevel client && !client.isRemoved() ? client : null;
    }

    /** Any live client instance of {@code id}; a real successor wins over a transit ghost. */
    @Nullable
    public static ClientSubLevel findClientSub(UUID id) {
        ClientSubLevel ghost = null;
        for (ClientLevel level : clientLevels()) {
            SubLevelContainer container = SubLevelContainer.getContainer((Level) level);
            SubLevel sub = container == null ? null : container.getSubLevel(id);
            if (!(sub instanceof ClientSubLevel client) || client.isRemoved()) continue;
            if (!IplParentDimSync.isGhost(client)) return client;
            ghost = client;
        }
        return ghost;
    }

    /** Whether another live client instance of {@code id} exists besides {@code except}. */
    public static boolean hasOtherInstance(UUID id, ClientSubLevel except) {
        for (ClientLevel level : clientLevels()) {
            SubLevelContainer container = SubLevelContainer.getContainer((Level) level);
            SubLevel sub = container == null ? null : container.getSubLevel(id);
            if (sub instanceof ClientSubLevel client && client != except && !client.isRemoved()) return true;
        }
        return false;
    }

    /**
     * The client ship whose plot contains a plot-space point. The main level wins;
     * plot slots are server-wide unique, so the fallback scan is unambiguous.
     */
    @Nullable
    public static ClientSubLevel findSubLevelAtPlot(Vec3 localAnchor) {
        var chunk = new ChunkPos(BlockPos.containing(localAnchor));
        ClientLevel main = Minecraft.getInstance().level;
        ClientSubLevel found = plotSub(main, chunk);
        if (found != null) return found;
        for (ClientLevel level : clientLevels()) {
            if (level == main) continue;
            found = plotSub(level, chunk);
            if (found != null) return found;
        }
        return null;
    }

    @Nullable
    private static ClientSubLevel plotSub(@Nullable ClientLevel level, ChunkPos chunk) {
        if (level == null) return null;
        SubLevelContainer container = SubLevelContainer.getContainer((Level) level);
        if (container == null || !container.inBounds(chunk.x, chunk.z)) return null;
        var plot = container.getPlot(chunk);
        return plot != null && plot.getSubLevel() instanceof ClientSubLevel client && !client.isRemoved()
            ? client : null;
    }

    // ------------------------------------------------------------------ projections

    /** Every projection to draw into {@code destLevel}'s render pass (cached per frame). */
    public static List<StraddleProjection> getStraddleProjectionsInto(ClientLevel destLevel) {
        return IplStraddleRenderCache.projections(destLevel, () -> projectionsUncached(destLevel));
    }

    private static List<StraddleProjection> projectionsUncached(@Nullable ClientLevel destLevel) {
        if (destLevel == null || IplStraddleSessionStore.isIdle()) return List.of();
        List<StraddleProjection> out = null;
        for (ClientLevel level : clientLevels()) {
            SubLevelContainer container = SubLevelContainer.getContainer((Level) level);
            if (container == null) continue;
            for (SubLevel sub : container.getAllSubLevels()) {
                if (sub.isRemoved() || !(sub instanceof ClientSubLevel clientSub)) continue;
                if (!IplStraddleSessionStore.mayStraddle(sub.getUniqueId())) continue;
                if (clientSub.getRenderData() == null || IplParentDimSync.isSuppressedSuccessor(clientSub)) continue;
                IplPieceRender.Frame frame = IplPieceRender.frameFor(clientSub);
                if (frame == null) continue;
                for (IplStraddleSessionStore.ResolvedSession s : frame.view.sessions()) {
                    if (s.portal().getDestDim() != destLevel.dimension() || !frame.draws(s.node())) continue;
                    if (out == null) out = new ArrayList<>(2);
                    out.add(projection(frame, s));
                }
            }
        }
        return out == null ? List.of() : out;
    }

    private static StraddleProjection projection(IplPieceRender.Frame frame, IplStraddleSessionStore.ResolvedSession s) {
        IplStraddlePoseMap.StraddleMapping mapping = frame.mappingOf(s.node());
        return new StraddleProjection(frame.sub, s.portal(), mapping.mapPose(frame.pose), s.path(), mapping, s.node());
    }

    /**
     * Client ships of ANY level with a portal image into {@code level} that intersects
     * {@code bounds} (spatial-query splice; see {@code CompanionIntersectMixin}).
     */
    @Nullable
    public static List<SubLevel> intersectingImages(Level level, BoundingBox3dc bounds) {
        if (IplStraddleSessionStore.isIdle()) return null;
        List<SubLevel> out = null;
        for (ClientLevel candidateLevel : clientLevels()) {
            SubLevelContainer container = SubLevelContainer.getContainer((Level) candidateLevel);
            if (container == null) continue;
            for (SubLevel sub : container.getAllSubLevels()) {
                if (sub.isRemoved() || !IplStraddleSessionStore.mayStraddle(sub.getUniqueId())) continue;
                if (sub instanceof ClientSubLevel clientSub && IplParentDimSync.isGhost(clientSub)) continue;
                boolean[] hit = {false};
                forEachClientStraddleInto(sub, level, (portal, mapping) -> {
                    if (!hit[0] && mapping.mappedIntersects(sub.boundingBox(), bounds)) hit[0] = true;
                });
                if (!hit[0]) continue;
                if (out == null) out = new ArrayList<>(2);
                out.add(sub);
            }
        }
        return out;
    }

    /** Whether this client sub-level currently has a piece beyond a portal. */
    public static boolean isClientStraddling(SubLevel sub) {
        return sub instanceof ClientSubLevel clientSub && !IplStraddleSessionStore.sessions(clientSub).isEmpty();
    }

    /** Isometry mapping {@code sub}'s own chart into {@code destLevel}, or null. */
    @Nullable
    public static IplStraddlePoseMap.StraddleMapping getClientStraddleMappingInto(SubLevel sub, Level destLevel) {
        IplStraddlePoseMap.StraddleMapping[] found = {null};
        forEachClientStraddleInto(sub, destLevel, (portal, mapping) -> {
            if (found[0] == null) found[0] = mapping;
        });
        return found[0];
    }

    /** Every (last portal, composite mapping) of {@code sub}'s sessions into {@code contextLevel}. */
    public static void forEachClientStraddleInto(
        SubLevel sub, Level contextLevel, BiConsumer<Portal, IplStraddlePoseMap.StraddleMapping> visitor
    ) {
        if (!(sub instanceof ClientSubLevel clientSub)) return;
        for (IplProjectionTree.Node node : IplProjectionTree.of(clientSub).nodes()) {
            if (node.destDim() != contextLevel.dimension()) continue;
            visitor.accept(node.portal, node.mapping);
        }
    }

    // ------------------------------------------------------------------ projection tree

    private static long clientTicks;

    /** Client tick tail: advances {@link #clientStamp}. */
    public static void onClientTick() {
        clientTicks++;
    }

    /**
     * Per-tick key of the client caches (projection trees, render views). A client tick count,
     * not {@code Minecraft.level}'s game time: Immersive Portals swaps that level for every
     * portal pass, and each dimension has its own clock, so a projection into another dimension
     * would drop and rebuild every cache on each pass switch.
     */
    public static long clientStamp() {
        return clientTicks;
    }

    /** The mirrored server sessions of {@code sub} as projection-tree entries (none for ghosts). */
    public static List<IplProjectionTree.Entry> treeEntries(SubLevel sub) {
        if (!(sub instanceof ClientSubLevel clientSub)) return List.of();
        if (!IplStraddleSessionStore.mayStraddle(sub.getUniqueId())) return List.of();
        return IplStraddleSessionStore.treeEntries(clientSub);
    }

    /** Pose through a portal isometry (rotation point and scale unchanged). */
    public static Pose3d computeMappedPose(Pose3dc sourcePose, IplStraddlePoseMap.StraddleMapping mapping) {
        return mapping.mapPose(sourcePose);
    }
}

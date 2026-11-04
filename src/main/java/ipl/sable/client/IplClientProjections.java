package ipl.sable.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.render.IplClipCells;
import ipl.sable.render.IplClipPortalFinder;
import ipl.sable.render.IplClipUniformPatcher;
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
import qouteall.q_misc_util.my_util.Plane;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Client view of portal projections in the native Sable model.
 *
 * <p>Every client sub-level lives in its own {@link ClientLevel}. While it straddles a
 * portal (server session mirrored by {@link IplStraddleSessionStore}), the part that went
 * through the aperture (the session's through prism) is drawn as a PROJECTION: the same
 * compiled geometry at the portal-mapped pose, inside the destination level's render pass,
 * clipped to that piece's convex cells. Chained sessions (the projected part entering another
 * portal) project through the composite isometry of every portal on the chain; parts beyond the
 * plane but beside the aperture stay in the native piece.
 *
 * <p>Referenced from common code only behind {@code isClientSide} branches.
 */
public final class IplClientProjections {

    private IplClientProjections() {}

    /**
     * One drawn image of {@code sub}: its compiled geometry at {@code mappedPose}, kept inside
     * the convex cell {@code planes} (destination chart, intersection of the kept half-spaces;
     * empty = nothing to cut). {@code via} lists the portals of the piece's path in order (size 1
     * for a direct straddle) and {@code portal} is the last of them. A further cell of the NATIVE
     * piece has an empty {@code via} and the identity mapping, and draws into the body's own
     * level (see {@link RenderPlan}).
     */
    public record StraddleProjection(
        ClientSubLevel sub,
        Portal portal,
        Pose3d mappedPose,
        List<Plane> planes,
        List<Portal> via,
        IplStraddlePoseMap.StraddleMapping mapping
    ) {
        public boolean isChained() { return via.size() > 1; }

        /** A further cell of the native piece, drawn in the body's own level. */
        public boolean isNative() { return via.isEmpty(); }

        /** The dimension this image is drawn into. */
        public ResourceKey<Level> destDim() {
            return via.isEmpty() ? sub.getLevel().dimension() : portal.getDestDim();
        }
    }

    /**
     * Everything drawn of one sub-level in this pass: the first cell of the NATIVE piece, which
     * the body's own draw installs ({@link IplClipPortalFinder#findStraddlingPortalPlane}), and
     * every other cell as a {@link StraddleProjection}.
     *
     * <p>Куски те же, что у физики и у {@code IplProjectionTree.pieceAt}: родной кусок лежит вне
     * всех корневых призм, кусок узла — внутри призм своего пути, без призм дочерних порталов и
     * старших соседей. Бесконечная плоскость портала тело больше не делит: часть, ушедшая за
     * плоскость мимо рамки, остаётся в родном куске и рисуется в своём мире. Невыпуклый кусок
     * раскладывается на выпуклые ячейки ({@link IplClipCells}), по проходу на непустую ячейку.
     */
    public record RenderPlan(
        @Nullable IplClipPortalFinder.ClipDecision nativeDecision, List<StraddleProjection> projections
    ) {
        static final RenderPlan NONE = new RenderPlan(null, List.of());
    }

    /** Kept side above the world's height limit: the native piece is empty, draw nothing of it. */
    private static final Plane REJECT_ALL = new Plane(new Vec3(0.0, 3.0e7, 0.0), new Vec3(0.0, 1.0, 0.0));

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

    /** Every projection to draw into {@code destLevel}'s render pass (cached per pass). */
    public static List<StraddleProjection> getStraddleProjectionsInto(ClientLevel destLevel) {
        return IplStraddleRenderCache.projections(destLevel, () -> projectionsUncached(destLevel));
    }

    private static List<StraddleProjection> projectionsUncached(@Nullable ClientLevel destLevel) {
        if (destLevel == null) return List.of();
        List<StraddleProjection> out = null;
        for (ClientLevel level : clientLevels()) {
            SubLevelContainer container = SubLevelContainer.getContainer((Level) level);
            if (container == null) continue;
            for (SubLevel sub : container.getAllSubLevels()) {
                if (sub.isRemoved() || !(sub instanceof ClientSubLevel clientSub)) continue;
                if (clientSub.getRenderData() == null || IplParentDimSync.isSuppressedSuccessor(clientSub)) continue;
                for (StraddleProjection projection : renderPlan(clientSub).projections()) {
                    if (projection.destDim() != destLevel.dimension()) continue;
                    if (out == null) out = new ArrayList<>(2);
                    out.add(projection);
                }
            }
        }
        return out == null ? List.of() : out;
    }

    /** The pieces of {@code sub} drawn in this pass (cached per pass). */
    public static RenderPlan renderPlan(ClientSubLevel sub) {
        RenderPlan cached = IplStraddleRenderCache.plan(sub);
        if (cached != null) return cached;
        RenderPlan plan;
        // Built in the body's REAL chart, never under an armed projection's mapped pose.
        IplStraddleRenderState.suspendPose();
        try {
            plan = computePlan(sub);
        } finally {
            IplStraddleRenderState.resumePose();
        }
        IplStraddleRenderCache.cachePlan(sub, plan);
        return plan;
    }

    /**
     * Render tree of {@code sub} (every root and chain the delayed pose still shows: tails,
     * predictions, a ghost's crossing portal) cut into convex cells inside the plot box.
     */
    private static RenderPlan computePlan(ClientSubLevel sub) {
        List<Portal> roots = IplStraddleSessionStore.resolveAllRenderPortals(sub);
        if (roots.isEmpty() || sub.getPlot().getBoundingBox() == null) return RenderPlan.NONE;
        UUID id = sub.getUniqueId();
        List<IplProjectionTree.Entry> entries = new ArrayList<>(roots.size() + 1);
        Set<String> seen = new HashSet<>();
        for (Portal portal : roots) addEntry(entries, seen, id, "", portal);
        for (List<Portal> chain : IplStraddleSessionStore.resolveRenderChains(sub)) addChain(entries, seen, id, chain);
        Pose3dc pose = sub.renderPose();
        IplProjectionTree tree = IplProjectionTree.build(sub, entries, pose);
        if (tree.isEmpty()) return RenderPlan.NONE;
        CellFrame frame = new CellFrame(sub, pose);
        IplStraddlePoseMap.StraddleMapping identity = IplStraddlePoseMap.StraddleMapping.IDENTITY;
        List<StraddleProjection> projections = new ArrayList<>(2);

        // Native piece: the plot box minus every root prism.
        List<List<double[]>> nativeCells = List.of(List.of());
        for (IplProjectionTree.Node root : tree.roots()) {
            nativeCells = IplClipCells.subtract(nativeCells, frame.local(root.prism.faces(), null), frame.box);
        }
        Portal first = tree.roots().get(0).portal;
        IplClipPortalFinder.ClipDecision nativeDecision = new IplClipPortalFinder.ClipDecision(first,
            nativeCells.isEmpty() ? List.of(REJECT_ALL) : frame.planes(nativeCells.get(0), identity));
        for (int i = 1; i < nativeCells.size(); i++) {
            projections.add(new StraddleProjection(sub, first, new Pose3d(pose),
                frame.planes(nativeCells.get(i), identity), List.of(), identity));
        }

        // Node pieces: the path's prisms minus the children's and the higher-priority siblings'.
        for (IplProjectionTree.Node node : tree.nodes()) {
            List<double[]> keep = keepPlanes(frame, node);
            if (!IplClipCells.hasInterior(keep, frame.box)) continue;
            List<List<double[]>> cells = List.of(keep);
            for (IplProjectionTree.Node child : node.children) {
                cells = IplClipCells.subtract(cells, frame.local(child.prism.faces(), node.mapping), frame.box);
            }
            for (IplProjectionTree.Node a = node; a != null && !cells.isEmpty(); a = a.parent) {
                for (IplProjectionTree.Node sibling : tree.siblingsOf(a)) {
                    if (sibling == a) break;
                    cells = IplClipCells.subtract(cells,
                        frame.local(sibling.prism.faces(), a.parent == null ? null : a.parent.mapping), frame.box);
                }
            }
            List<Portal> via = cells.isEmpty() ? List.of() : node.pathPortals();
            for (List<double[]> cell : cells) {
                projections.add(new StraddleProjection(sub, node.portal, node.mapping.mapPose(pose),
                    frame.planes(cell, node.mapping), via, node.mapping));
            }
        }
        return new RenderPlan(nativeDecision, List.copyOf(projections));
    }

    /** The prisms of {@code node}'s path in the real chart: every portal plane first, then the sides. */
    private static List<double[]> keepPlanes(CellFrame frame, IplProjectionTree.Node node) {
        List<IplProjectionTree.Node> path = new ArrayList<>(2);
        for (IplProjectionTree.Node a = node; a != null; a = a.parent) path.add(0, a);
        List<double[]> planes = new ArrayList<>(5 * path.size());
        List<double[]> sides = new ArrayList<>(4 * path.size());
        for (IplProjectionTree.Node a : path) {
            double[][] faces = frame.local(a.prism.faces(), a.parent == null ? null : a.parent.mapping);
            planes.add(faces[0]);
            for (int i = 1; i < faces.length; i++) sides.add(faces[i]);
        }
        planes.addAll(sides);
        return planes;
    }

    /** Slack for block-entity geometry slightly outside the plot's block box. */
    private static final double CULL_MARGIN = 1.0;

    /**
     * The plot box of a body at its render pose, as six planes; cell planes live in coordinates
     * relative to the box centre (well-conditioned vertex arithmetic far from the world origin).
     */
    private static final class CellFrame {
        final Vec3 center;
        final double[][] box;

        CellFrame(ClientSubLevel sub, Pose3dc pose) {
            BoundingBox3ic plot = sub.getPlot().getBoundingBox();
            double x = (plot.minX() + (double) plot.maxX() + 1) * 0.5;
            double y = (plot.minY() + (double) plot.maxY() + 1) * 0.5;
            double z = (plot.minZ() + (double) plot.maxZ() + 1) * 0.5;
            center = pose.transformPosition(new Vec3(x, y, z));
            Vec3 ex = pose.transformPosition(new Vec3(plot.maxX() + 1.0, y, z)).subtract(center);
            Vec3 ey = pose.transformPosition(new Vec3(x, plot.maxY() + 1.0, z)).subtract(center);
            Vec3 ez = pose.transformPosition(new Vec3(x, y, plot.maxZ() + 1.0)).subtract(center);
            box = IplClipCells.box(new double[3],
                new double[][]{{ex.x, ex.y, ex.z}, {ey.x, ey.y, ey.z}, {ez.x, ez.y, ez.z}}, CULL_MARGIN);
        }

        /**
         * World planes of a node's SOURCE chart ({@code toSource} maps the real chart there; null
         * = the real chart itself) as real-chart planes relative to the centre.
         */
        double[][] local(double[][] faces, @Nullable IplStraddlePoseMap.StraddleMapping toSource) {
            double[][] out = new double[faces.length][];
            for (int i = 0; i < faces.length; i++) {
                double[] f = faces[i];
                Vec3 n = new Vec3(f[0], f[1], f[2]);
                Vec3 p = n.scale(-f[3]);
                if (toSource != null) {
                    n = toSource.unmapVec(n);
                    p = toSource.unmapPoint(p);
                }
                out[i] = new double[]{n.x, n.y, n.z, -n.dot(p.subtract(center))};
            }
            return out;
        }

        /** The cell's planes that actually cut the box (at most the shader's slots), mapped into the destination chart. */
        List<Plane> planes(List<double[]> cell, IplStraddlePoseMap.StraddleMapping mapping) {
            List<double[]> essential = IplClipCells.essential(cell, box);
            int count = Math.min(essential.size(), IplClipUniformPatcher.SLOTS);
            List<Plane> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                double[] e = essential.get(i);
                Vec3 n = new Vec3(e[0], e[1], e[2]);
                // Foot of the perpendicular from the box centre.
                Vec3 p = center.add(n.scale(-e[3]));
                out.add(new Plane(mapping.mapPoint(p), mapping.mapVec(n)));
            }
            return List.copyOf(out);
        }
    }

    // ------------------------------------------------------------------ collision / interaction

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

    /** Whether this client sub-level currently straddles a portal (client judgment). */
    public static boolean isClientStraddling(SubLevel sub) {
        return sub instanceof ClientSubLevel clientSub
            && IplClipPortalFinder.findStraddlingPortalPlane(clientSub) != null;
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

    /** Every (portal, mapping) of {@code sub} whose destination is {@code contextLevel}, chains included. */
    public static void forEachClientStraddleInto(
        SubLevel sub, Level contextLevel, BiConsumer<Portal, IplStraddlePoseMap.StraddleMapping> visitor
    ) {
        if (!(sub instanceof ClientSubLevel clientSub)) return;
        for (Portal portal : IplStraddleSessionStore.resolveAllPortals(clientSub)) {
            if (portal.getDestDim() != contextLevel.dimension()) continue;
            if (Math.abs(portal.getScaling() - 1.0) > 1e-9) continue;
            visitor.accept(portal, IplStraddlePoseMap.StraddleMapping.of(portal));
        }
        for (List<Portal> chain : IplStraddleSessionStore.resolveChains(clientSub)) {
            Portal last = chain.get(chain.size() - 1);
            if (last.getDestDim() != contextLevel.dimension()) continue;
            // Only the composite mapping: building a whole render projection (planes, mapped
            // render pose, path copy) here ran on every spatial query.
            visitor.accept(last, chainMapping(chain));
        }
    }

    /** Composite isometry of a chain path (its first portal applied first). */
    private static IplStraddlePoseMap.StraddleMapping chainMapping(List<Portal> chain) {
        IplStraddlePoseMap.StraddleMapping mapping = IplStraddlePoseMap.StraddleMapping.of(chain.get(0));
        for (int i = 1; i < chain.size(); i++) {
            mapping = mapping.then(IplStraddlePoseMap.StraddleMapping.of(chain.get(i)));
        }
        return mapping;
    }

    // ------------------------------------------------------------------ projection tree

    /** Client clock for {@link ipl.sable.transit.IplProjectionTree}'s per-tick cache. */
    public static long clientStamp() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? 0L : level.getGameTime();
    }

    /**
     * The mirrored server sessions of {@code sub} as projection-tree entries: the ROOT
     * session portals (authoritative, no render tails) and every resolvable chain, each
     * prefix registered before its extension, with the sessions' passage anchors. Transit
     * ghosts are visual-only and have no tree (their real successor owns interaction).
     */
    public static List<IplProjectionTree.Entry> treeEntries(SubLevel sub) {
        if (!(sub instanceof ClientSubLevel clientSub) || IplParentDimSync.isGhost(clientSub)) return List.of();
        UUID id = sub.getUniqueId();
        if (!IplStraddleSessionStore.mayStraddle(id)) return List.of();
        List<IplProjectionTree.Entry> out = new ArrayList<>(2);
        Set<String> seen = new HashSet<>();
        for (Portal portal : IplStraddleSessionStore.resolveAllPortals(clientSub)) addEntry(out, seen, id, "", portal);
        for (List<Portal> chain : IplStraddleSessionStore.resolveChains(clientSub)) addChain(out, seen, id, chain);
        return out;
    }

    private static void addChain(List<IplProjectionTree.Entry> out, Set<String> seen, UUID id, List<Portal> chain) {
        String via = "";
        for (Portal portal : chain) via = addEntry(out, seen, id, via, portal);
    }

    /** Registers {@code portal} under {@code via} once, with its session anchor; returns its path. */
    private static String addEntry(
        List<IplProjectionTree.Entry> out, Set<String> seen, UUID id, String via, Portal portal
    ) {
        String path = via.isEmpty() ? portal.getUUID().toString() : via + ">" + portal.getUUID();
        if (seen.add(path)) {
            out.add(new IplProjectionTree.Entry(path, via, portal, IplStraddleSessionStore.anchor(id, path)));
        }
        return path;
    }
}

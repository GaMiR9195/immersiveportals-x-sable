package ipl.sable.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.render.IplClipPortalFinder;
import ipl.sable.transit.IplProjectionTree;
import ipl.sable.transit.IplStraddlePoseMap;
import ipl.sable.transit.PortalOrder;
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
 * portal (server session mirrored by {@link IplStraddleSessionStore}), the part past the
 * portal plane is drawn as a PROJECTION: the same compiled geometry at the portal-mapped
 * pose, inside the destination level's render pass, clipped at the mapped plane. Chained
 * sessions (the projected part entering another portal) project through the composite
 * isometry of every portal on the chain.
 *
 * <p>Referenced from common code only behind {@code isClientSide} branches.
 */
public final class IplClientProjections {

    private IplClientProjections() {}

    /**
     * One image of {@code sub} in another chart: drawn at {@code mappedPose}, keeping the
     * half beyond {@code destPlane}. {@code via} lists every portal of the path in order
     * (size 1 for a direct straddle); {@code portal} is the last of them.
     */
    public record StraddleProjection(
        ClientSubLevel sub,
        Portal portal,
        Pose3d mappedPose,
        Plane destPlane,
        List<Portal> via,
        IplStraddlePoseMap.StraddleMapping mapping,
        @Nullable Plane exclude
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
        if (destLevel == null) return List.of();
        List<StraddleProjection> out = null;
        for (ClientLevel level : clientLevels()) {
            SubLevelContainer container = SubLevelContainer.getContainer((Level) level);
            if (container == null) continue;
            for (SubLevel sub : container.getAllSubLevels()) {
                if (sub.isRemoved() || !(sub instanceof ClientSubLevel clientSub)) continue;
                if (clientSub.getRenderData() == null || IplParentDimSync.isSuppressedSuccessor(clientSub)) continue;
                // ONE projection per session portal (multi-straddle).
                List<IplClipPortalFinder.ClipDecision> roots =
                    IplClipPortalFinder.findStraddlingPortalPlanes(clientSub);
                // Render chains include the tails the delayed pose still shows (see the store):
                // they are both drawn and used as the root's second cut plane.
                List<List<Portal>> chains = IplStraddleSessionStore.resolveRenderChains(clientSub);
                for (IplClipPortalFinder.ClipDecision decision : roots) {
                    if (decision.portal() == null || decision.portal().getDestDim() != destLevel.dimension()) continue;
                    StraddleProjection projection = direct(clientSub, decision, chains, roots);
                    if (!keepsAnything(projection)) continue;
                    if (out == null) out = new ArrayList<>(2);
                    out.add(projection);
                }
                for (List<Portal> chain : chains) {
                    Portal last = chain.get(chain.size() - 1);
                    if (last.getDestDim() != destLevel.dimension()) continue;
                    if (coveredBySibling(clientSub, chain, chains)) continue;
                    StraddleProjection projection = chained(clientSub, chain, chains);
                    if (!keepsAnything(projection)) continue;
                    if (out == null) out = new ArrayList<>(2);
                    out.add(projection);
                }
            }
        }
        return out == null ? List.of() : out;
    }

    private static StraddleProjection direct(
        ClientSubLevel sub, IplClipPortalFinder.ClipDecision decision, List<List<Portal>> chains,
        List<IplClipPortalFinder.ClipDecision> roots
    ) {
        Portal portal = decision.portal();
        IplStraddlePoseMap.StraddleMapping mapping = IplStraddlePoseMap.StraddleMapping.of(portal);
        // Through the portal the kept half flips: n_dest = -R(n_src), anchored at the mapped point.
        Plane destPlane = new Plane(portal.transformPoint(decision.plane().pos()),
            portal.transformLocalVec(decision.plane().normal()).scale(-1.0));
        List<Portal> via = List.of(portal);
        Plane exclude = childEntryPlane(via, chains);
        if (exclude == null) exclude = upstreamSiblingPlane(sub, portal, roots);
        return new StraddleProjection(sub, portal, computeMappedPose(sub.renderPose(), mapping),
            destPlane, via, mapping, exclude);
    }

    /**
     * The cut a root projection through {@code portal} owes to an UPSTREAM sibling root (one
     * whose plane {@code portal} stands beyond, see {@code PortalOrder}): material past that
     * sibling's plane went through the sibling, so only the side not yet through it is kept,
     * expressed in {@code portal}'s destination frame. Fully covered portals never get here
     * (the session store drops them); this is the partial-overlap case.
     */
    @Nullable
    private static Plane upstreamSiblingPlane(
        ClientSubLevel sub, Portal portal, List<IplClipPortalFinder.ClipDecision> roots
    ) {
        if (roots.size() < 2) return null;
        UUID id = sub.getUniqueId();
        boolean wide = IplStraddleSessionStore.isWide(id, portal.getUUID().toString());
        for (IplClipPortalFinder.ClipDecision other : roots) {
            Portal sibling = other.portal();
            if (sibling == null || sibling.getUUID().equals(portal.getUUID())) continue;
            boolean siblingWide = IplStraddleSessionStore.isWide(id, sibling.getUUID().toString());
            if (!PortalOrder.strictlyDownstreamOf(portal, wide, sibling, siblingWide)) continue;
            return new Plane(portal.transformPoint(sibling.getOriginPos()), portal.transformLocalVec(sibling.getNormal()));
        }
        return null;
    }

    /**
     * Whether a sibling chain (same path up to the last portal) covers {@code chain}'s last
     * portal: its region lies beyond the sibling's plane, so its piece is empty and its
     * projection would only repeat the sibling's material in the wrong world.
     */
    private static boolean coveredBySibling(ClientSubLevel sub, List<Portal> chain, List<List<Portal>> chains) {
        UUID id = sub.getUniqueId();
        Portal last = chain.get(chain.size() - 1);
        String path = pathOf(chain);
        for (List<Portal> other : chains) {
            if (other == chain || other.size() != chain.size()) continue;
            boolean sameParent = true;
            for (int i = 0; i < chain.size() - 1 && sameParent; i++) {
                sameParent = other.get(i).getUUID().equals(chain.get(i).getUUID());
            }
            Portal sibling = other.get(other.size() - 1);
            if (!sameParent || sibling.getUUID().equals(last.getUUID())) continue;
            if (PortalOrder.coveredBy(last, IplStraddleSessionStore.isWide(id, path),
                sibling, IplStraddleSessionStore.isWide(id, pathOf(other)))) return true;
        }
        return false;
    }

    private static String pathOf(List<Portal> chain) {
        StringBuilder path = new StringBuilder();
        for (Portal portal : chain) {
            if (path.length() > 0) path.append('>');
            path.append(portal.getUUID());
        }
        return path.toString();
    }

    private static StraddleProjection chained(ClientSubLevel sub, List<Portal> chain, List<List<Portal>> chains) {
        IplStraddlePoseMap.StraddleMapping mapping = IplStraddlePoseMap.StraddleMapping.of(chain.get(0));
        for (int i = 1; i < chain.size(); i++) {
            mapping = mapping.then(IplStraddlePoseMap.StraddleMapping.of(chain.get(i)));
        }
        Portal last = chain.get(chain.size() - 1);
        Plane destPlane = new Plane(last.transformPoint(last.getOriginPos()),
            last.transformLocalVec(last.getNormal()).scale(-1.0));
        // Вторая плоскость куска цепочки. Приоритет — вход в СЛЕДУЮЩИЙ портал (кусок, ушедший
        // глубже, рисует уже следующая проекция). Если глубже никто не ушёл — выход ПРЕДЫДУЩЕГО
        // портала, перенесённый через последний: без неё «родная» часть корабля, отображённая
        // составной изометрией, при косой расстановке порталов может попасть за выход последнего
        // портала и нарисоваться лишней копией.
        Plane exclude = childEntryPlane(chain, chains);
        if (exclude == null && chain.size() >= 2) {
            Portal parent = chain.get(chain.size() - 2);
            Vec3 parentExitPos = parent.transformPoint(parent.getOriginPos());
            Vec3 parentExitKeep = parent.transformLocalVec(parent.getNormal()).scale(-1.0);
            exclude = new Plane(last.transformPoint(parentExitPos), last.transformLocalVec(parentExitKeep));
        }
        return new StraddleProjection(sub, last, computeMappedPose(sub.renderPose(), mapping),
            destPlane, List.copyOf(chain), mapping, exclude);
    }

    /** Slack for block-entity geometry slightly outside the plot's block box. */
    private static final double CULL_MARGIN = 1.0;

    /**
     * Проекция рисует всю геометрию корабля, а режет её шейдер.
     * Сессии, открытые заранее (предсказанный или ведущий вход), и хвосты держат проекции, чей
     * кусок ещё или уже пуст, — в ряду порталов это полные лишние проходы на каждый портал.
     * Если коробка плота целиком по отрезанную сторону любой из двух плоскостей куска, рисовать
     * нечего: такая проекция не попадает в список (отбор, компиляция, отрисовка, выбор).
     */
    private static boolean keepsAnything(StraddleProjection projection) {
        var bounds = projection.sub().getPlot().getBoundingBox();
        if (bounds == null) return true;
        return reaches(projection.destPlane(), projection.mappedPose(), bounds)
            && (projection.exclude() == null || reaches(projection.exclude(), projection.mappedPose(), bounds));
    }

    /** Whether any corner of the plot box at {@code pose} is on (or near) the kept side of {@code plane}. */
    private static boolean reaches(Plane plane, Pose3dc pose, BoundingBox3ic bounds) {
        for (int i = 0; i < 8; i++) {
            Vec3 corner = pose.transformPosition(new Vec3(
                (i & 1) == 0 ? bounds.minX() : bounds.maxX() + 1.0,
                (i & 2) == 0 ? bounds.minY() : bounds.maxY() + 1.0,
                (i & 4) == 0 ? bounds.minZ() : bounds.maxZ() + 1.0));
            if (plane.getDistanceTo(corner) > -CULL_MARGIN) return true;
        }
        return false;
    }

    /**
     * Плоскость входа в первый дочерний портал куска {@code path}: кусок проекции, который
     * уже зашёл в следующий портал, должен быть ОБРЕЗАН на его входе (то, что за входом,
     * рисует дочерняя проекция в своём измерении). Без этой второй плоскости прямая
     * проекция рисовала бы корабль целиком за входом следующего портала — «несколько копий
     * саб-уровня» вместо кусков, переходящих из портала в портал.
     *
     * <p>Сохраняемое полупространство — сторона нормали входа (по конвенции IP нормаль
     * смотрит на исходную сторону), как и у корневого среза в {@link IplClipPortalFinder}.
     * Дочерних порталов может быть несколько; шейдер даёт только два независимых среза,
     * поэтому берётся первый (детерминированно — порядок сессий сервера).
     */
    @Nullable
    private static Plane childEntryPlane(List<Portal> path, List<List<Portal>> chains) {
        for (List<Portal> chain : chains) {
            if (chain.size() != path.size() + 1) continue;
            boolean prefix = true;
            for (int i = 0; i < path.size() && prefix; i++) {
                prefix = chain.get(i).getUUID().equals(path.get(i).getUUID());
            }
            if (!prefix) continue;
            Portal child = chain.get(path.size());
            return new Plane(child.getOriginPos(), child.getNormal());
        }
        return null;
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
                    if (!hit[0] && mapping.mapAabb(sub.boundingBox()).intersects(bounds)) hit[0] = true;
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
            visitor.accept(last, chained(clientSub, chain, List.of()).mapping());
        }
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
     * prefix registered before its extension. Transit ghosts are visual-only and have no
     * tree (their real successor owns interaction).
     */
    public static List<IplProjectionTree.Entry> treeEntries(SubLevel sub) {
        if (!(sub instanceof ClientSubLevel clientSub) || IplParentDimSync.isGhost(clientSub)) return List.of();
        if (!IplStraddleSessionStore.mayStraddle(sub.getUniqueId())) return List.of();
        List<IplProjectionTree.Entry> out = new ArrayList<>(2);
        Set<String> seen = new HashSet<>();
        for (Portal portal : IplStraddleSessionStore.resolveAllPortals(clientSub)) {
            String path = portal.getUUID().toString();
            if (seen.add(path)) out.add(new IplProjectionTree.Entry(path, "", portal,
                IplStraddleSessionStore.isWide(sub.getUniqueId(), path)));
        }
        for (List<Portal> chain : IplStraddleSessionStore.resolveChains(clientSub)) {
            String via = "";
            for (Portal portal : chain) {
                String path = via.isEmpty() ? portal.getUUID().toString() : via + ">" + portal.getUUID();
                if (seen.add(path)) out.add(new IplProjectionTree.Entry(path, via, portal,
                    IplStraddleSessionStore.isWide(sub.getUniqueId(), path)));
                via = path;
            }
        }
        return out;
    }

    /** Pose through a portal isometry (rotation point and scale unchanged). */
    public static Pose3d computeMappedPose(Pose3dc sourcePose, IplStraddlePoseMap.StraddleMapping mapping) {
        return mapping.mapPose(sourcePose);
    }
}

package ipl.sable.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.IplPerfDiagnostics;
import ipl.sable.client.IplParentDimSync;
import ipl.sable.client.IplStraddleRenderCache;
import ipl.sable.client.IplStraddleRenderState;
import ipl.sable.client.IplStraddleSessionStore;
import ipl.sable.transit.IplPieceMap;
import ipl.sable.transit.IplApertureSweep;
import ipl.sable.transit.IplProjectionTree;
import ipl.sable.transit.IplStraddlePoseMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import qouteall.q_misc_util.my_util.Plane;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Piece-exact drawing of sub-levels that cross portals (README, section 8).
 *
 * <p>Every draw of a body is a draw of ONE piece: the native piece at the body's own pose, or a
 * session's piece at its mapped pose. Per section and layer the plan is
 * <ul>
 *   <li>SKIP -- no block of this piece in the section;</li>
 *   <li>FULL -- the section holds only this piece and no aperture zone touches it (the common
 *       case: one buffer bind, one draw, no clip plane);</li>
 *   <li>RANGES -- mixed labels: one multi-draw over the quads of this piece's blocks, from the
 *       same buffer ({@link IplSectionRanges});</li>
 *   <li>SPLIT -- blocks inside an aperture zone are drawn by both pieces of the edge, each cut by
 *       the aperture plane at the current draw pose ({@code gl_ClipDistance[1]}).</li>
 * </ul>
 * Labels come from the server ({@link IplStraddleSessionStore}); the plane only decides inside
 * the zone, so material beside a frame is never cut and a ring pushed through a portal looks
 * right from both sides. Plans of untouched sections are cached per buffer and label revision.
 */
public final class IplPieceRender {

    private IplPieceRender() {}

    /** One straddling body during one render frame. */
    public static final class Frame {
        public final ClientSubLevel sub;
        public final IplStraddleSessionStore.RenderView view;
        /** Real render pose: the pose of the native draw. */
        public final Pose3dc pose;
        final IplPieceMap.Edge[] edges;
        final Map<Integer, IplStraddlePoseMap.StraddleMapping> mappings;

        Frame(ClientSubLevel sub, IplStraddleSessionStore.RenderView view, Pose3dc pose,
              IplPieceMap.Edge[] edges, Map<Integer, IplStraddlePoseMap.StraddleMapping> mappings) {
            this.sub = sub;
            this.view = view;
            this.pose = pose;
            this.edges = edges;
            this.mappings = mappings;
        }

        /** Composite mapping of session {@code node}'s path; null for the native piece. */
        @Nullable
        public IplStraddlePoseMap.StraddleMapping mappingOf(int node) {
            return mappings.get(node);
        }

        /** Whether piece {@code node} may have anything to draw this frame. */
        public boolean draws(int node) {
            if (node < 0) return false;
            if (node == IplPieceMap.NATIVE || view.pieces().has(node)) return true;
            for (IplPieceMap.Edge e : edges) if (e.child == node || e.parent == node) return true;
            return false;
        }

        /** Edges a draw of {@code node} has split voxels on. */
        public List<IplPieceMap.Edge> splitEdges(int node) {
            List<IplPieceMap.Edge> out = new ArrayList<>(1);
            for (IplPieceMap.Edge e : edges) if (e.parent == node || e.child == node) out.add(e);
            return out;
        }

        int label(int x, int y, int z) {
            return IplPieceMap.voxelLabel(edges, x, y, z, view.label(x, y, z));
        }
    }

    private static final Map<ClientSubLevel, Frame> FRAMES = new IdentityHashMap<>();
    private static long framesId = Long.MIN_VALUE;
    private static long framesGeneration = Long.MIN_VALUE;

    /** The frame of {@code sub}, or null when it draws as one piece (no session image). */
    @Nullable
    public static Frame frameFor(@Nullable ClientSubLevel sub) {
        if (sub == null || IplStraddleSessionStore.isIdle()) return null;
        long id = IplStraddleRenderCache.frameId();
        long generation = IplProjectionTree.generation();
        if (id != framesId || generation != framesGeneration) {
            FRAMES.clear();
            framesId = id;
            framesGeneration = generation;
        }
        Frame frame = FRAMES.get(sub);
        if (frame == null && !FRAMES.containsKey(sub)) {
            frame = compute(sub);
            FRAMES.put(sub, frame);
        }
        return frame;
    }

    @Nullable
    private static Frame compute(ClientSubLevel sub) {
        IplStraddleSessionStore.RenderView view = IplStraddleSessionStore.renderView(sub);
        if (view.sessions().isEmpty()) return null;
        Pose3d real;
        IplStraddleRenderState.suspendPose();
        try {
            real = new Pose3d(sub.renderPose());
        } finally {
            IplStraddleRenderState.resumePose();
        }
        Map<Integer, IplStraddlePoseMap.StraddleMapping> mappings = new java.util.HashMap<>();
        List<IplPieceMap.Edge> edges = new ArrayList<>(view.sessions().size());
        for (IplStraddleSessionStore.ResolvedSession s : view.sessions()) {
            IplStraddlePoseMap.StraddleMapping mapping = null;
            for (var portal : s.path()) {
                var local = IplStraddlePoseMap.StraddleMapping.of(portal);
                mapping = mapping == null ? local : mapping.then(local);
            }
            mappings.put(s.node(), mapping);
            if (s.parent() < 0) continue;
            IplStraddlePoseMap.StraddleMapping parent = s.parent() == view.nativeNode() ? null : mappings.get(s.parent());
            if (s.parent() != view.nativeNode() && parent == null) continue;
            IplPieceMap.Aperture world = IplPieceMap.Aperture.of(s.portal());
            IplPieceMap.Aperture drawn = world.toPlot(parent == null ? real : parent.mapPose(real));
            Pose3dc last = sub.lastPose();
            IplPieceMap.Aperture fallback = world.toPlot(parent == null ? last : parent.mapPose(last));
            IplPieceMap.Edge labelEdge = view.pieces().edge(s.parent(), s.node());
            IplPieceMap.Aperture reference = labelEdge == null ? fallback
                : labelEdge.aperture.shift(-view.shiftX(), -view.shiftZ());
            edges.add(new IplPieceMap.Edge(s.parent(), s.node(), drawn, reference));
        }
        return new Frame(sub, view, real, edges.toArray(new IplPieceMap.Edge[0]), mappings);
    }

    /** The piece the current draw of {@code sub} shows: the armed projection's, else native. */
    public static int drawNode(ClientSubLevel sub, Frame frame) {
        if (IplStraddleRenderState.isActiveFor(sub)) return IplStraddleRenderState.getNodeFor(sub);
        return frame.view.nativeNode();
    }

    /**
     * The world plane that keeps {@code node}'s side of edge {@code e} for a draw at
     * {@code drawPose} (kept side: {@code normal · (p - pos) > 0}).
     */
    public static Plane keepPlane(IplPieceMap.Edge e, int node, Pose3dc drawPose) {
        IplPieceMap.Aperture a = e.aperture;
        Vector3d p = drawPose.transformPosition(new Vector3d(a.ox(), a.oy(), a.oz()));
        Vector3d n = drawPose.transformNormal(new Vector3d(a.nx(), a.ny(), a.nz())).normalize();
        if (node != e.parent) n.negate();
        return new Plane(new Vec3(p.x, p.y, p.z), new Vec3(n.x, n.y, n.z));
    }

    // ------------------------------------------------------------------ sections

    /** One {@code renderChunkedSubLevel} call of a straddling body. */
    public static final class Draw {
        Frame frame;
        int node;
        Pose3dc pose;
    }

    private static final Draw DRAW = new Draw();

    /** Starts a piece draw; null when nothing of the piece can be in this call. */
    @Nullable
    public static Draw begin(Frame frame, int node, Pose3dc drawPose) {
        if (!frame.draws(node)) return null;
        DRAW.frame = frame;
        DRAW.node = node;
        DRAW.pose = drawPose;
        return DRAW;
    }

    /** Cached RANGES plan of a buffer (sections no zone touches). */
    private record Plan(long ranges, IplPieceMap map, long revision, int node, int shiftX, int shiftZ,
                        int[] first, int[] count, int n) {}

    /** Draws {@code section}'s {@code buffer} (already bound) for the current piece. */
    public static void drawSection(Draw draw, SectionRenderDispatcher.RenderSection section,
                                   VertexBuffer buffer, Runnable full) {
        if (!IplPerfDiagnostics.ENABLED) {
            drawPiece(draw, section, buffer, full);
            return;
        }
        long start = System.nanoTime();
        drawPiece(draw, section, buffer, full);
        IplPerfDiagnostics.record(IplPerfDiagnostics.CLIENT_PIECE_DRAW, System.nanoTime() - start);
    }

    private static void drawPiece(Draw draw, SectionRenderDispatcher.RenderSection section,
                                  VertexBuffer buffer, Runnable full) {
        Frame frame = draw.frame;
        int node = draw.node;
        BlockPos origin = section.getOrigin();
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        IplStraddleSessionStore.RenderView view = frame.view;
        long key = SectionPos.asLong((ox + view.shiftX()) >> 4, oy >> 4, (oz + view.shiftZ()) >> 4);
        long[] present = view.pieces().present(key);
        IplPieceMap.Edge[] relevant = relevantEdges(frame.edges, ox, oy, oz);
        if (relevant.length == 0) {
            int single = IplPieceMap.single(present);
            if (single == node) {
                full.run();
                return;
            }
            if (!IplPieceMap.contains(present, node)) return;
        }
        IplSectionRanges.Ranges ranges = ((IplSectionRanges.Holder) buffer).ipl$ranges();
        IplSectionRanges.BufferAccess access = (IplSectionRanges.BufferAccess) buffer;
        if (ranges == null || access.ipl$mode() != VertexFormat.Mode.QUADS) {
            // Compiled before ranges existed (or by a foreign compiler): whole section until rebuilt.
            full.run();
            return;
        }
        byte[] labels = view.pieces().section(key);
        if (relevant.length == 0) {
            Plan plan = access.ipl$plan() instanceof Plan p && p.ranges == ranges.version && p.map == view.pieces()
                && p.revision == view.pieces().revision() && p.node == node && p.shiftX == view.shiftX()
                && p.shiftZ == view.shiftZ() ? p : null;
            if (plan == null) {
                IntArrayList first = new IntArrayList(), count = new IntArrayList();
                for (int i = 0; i < ranges.count; i++) {
                    int label = labels == null ? IplPieceMap.NATIVE : labels[ranges.block(i)] & 0xFF;
                    if (label == node) append(first, count, ranges.firstQuad(i), ranges.quads(i));
                }
                plan = new Plan(ranges.version, view.pieces(), view.pieces().revision(), node, view.shiftX(),
                    view.shiftZ(), first.toIntArray(), count.toIntArray(), first.size());
                access.ipl$setPlan(plan);
            }
            multiDraw(access, plan.first, plan.count, plan.n);
            return;
        }
        IntArrayList first = new IntArrayList(), count = new IntArrayList();
        List<IplPieceMap.Edge> splitEdges = null;
        List<IntArrayList[]> splits = null;
        for (int i = 0; i < ranges.count; i++) {
            int index = ranges.block(i);
            int label = labels == null ? IplPieceMap.NATIVE : labels[index] & 0xFF;
            int x = ox + (index & 15), y = oy + (index >> 4 & 15), z = oz + (index >> 8 & 15);
            IplPieceMap.Edge e = IplPieceMap.zoneEdgeOfVoxel(relevant, x, y, z, label);
            if (e == null) {
                if (IplPieceMap.voxelLabel(relevant, x, y, z, label) == node)
                    append(first, count, ranges.firstQuad(i), ranges.quads(i));
                continue;
            }
            if (e.parent != node && e.child != node) continue;
            if (splitEdges == null) {
                splitEdges = new ArrayList<>(1);
                splits = new ArrayList<>(1);
            }
            int k = splitEdges.indexOf(e);
            if (k < 0) {
                splitEdges.add(e);
                splits.add(new IntArrayList[]{new IntArrayList(), new IntArrayList()});
                k = splitEdges.size() - 1;
            }
            IntArrayList[] lists = splits.get(k);
            append(lists[0], lists[1], ranges.firstQuad(i), ranges.quads(i));
        }
        if (!first.isEmpty()) multiDraw(access, first.elements(), count.elements(), first.size());
        if (splitEdges == null) return;
        for (int k = 0; k < splitEdges.size(); k++) {
            IntArrayList[] lists = splits.get(k);
            IplClipUniformPatcher.patchForSubLevel(frame.sub, keepPlane(splitEdges.get(k), node, draw.pose), null);
            multiDraw(access, lists[0].elements(), lists[1].elements(), lists[0].size());
            GL11.glDisable(GL30.GL_CLIP_DISTANCE1);
            GL11.glDisable(GL30.GL_CLIP_DISTANCE2);
        }
    }

    private static void append(IntArrayList first, IntArrayList count, int quad, int quads) {
        int n = first.size();
        if (n > 0 && first.getInt(n - 1) + count.getInt(n - 1) == quad) {
            count.set(n - 1, count.getInt(n - 1) + quads);
        } else {
            first.add(quad);
            count.add(quads);
        }
    }

    /** Edges whose observed finite sweep may reach the section at {@code (ox, oy, oz)}. */
    private static IplPieceMap.Edge[] relevantEdges(IplPieceMap.Edge[] edges, int ox, int oy, int oz) {
        IplPieceMap.Edge[] out = null;
        int n = 0;
        for (IplPieceMap.Edge e : edges) {
            if (!IplApertureSweep.boxMayIntersect(e.reference, e.aperture, ox, oy, oz, 16.0, e.zone + e.reach)) continue;
            if (out == null) out = new IplPieceMap.Edge[edges.length];
            out[n++] = e;
        }
        return out == null ? NO_EDGES : n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    private static final IplPieceMap.Edge[] NO_EDGES = new IplPieceMap.Edge[0];

    /** Reused native arguments for glMultiDrawElements (render thread only). */
    private static IntBuffer counts = MemoryUtil.memAllocInt(256);
    private static PointerBuffer offsets = MemoryUtil.memAllocPointer(256);

    /** Scratch element buffer of sorted translucent piece draws, and its CPU staging. */
    private static int sortedIndexBuffer;
    private static IntBuffer sortedIndices = MemoryUtil.memAllocInt(6 * 256);
    private static final BitSet CHOSEN = new BitSet();

    /**
     * One multi-draw over quad runs of the bound buffer. A sorted translucent buffer draws the
     * chosen quads in its own back-to-front order ({@link #drawSorted}); without a recorded
     * order the runs go through the shared sequential index buffer (compile order).
     */
    private static void multiDraw(IplSectionRanges.BufferAccess buffer, int[] first, int[] count, int n) {
        if (n == 0) return;
        RenderSystem.AutoStorageIndexBuffer sequential = buffer.ipl$sequential();
        boolean borrowed = sequential == null;
        int[] order = borrowed ? buffer.ipl$quadOrder() : null;
        if (order != null) {
            drawSorted(buffer, order, first, count, n);
            return;
        }
        if (borrowed) {
            sequential = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
            sequential.bind(buffer.ipl$indexCount());
        }
        VertexFormat.IndexType type = sequential.type();
        if (counts.capacity() < n) {
            int capacity = Math.max(n, counts.capacity() * 2);
            MemoryUtil.memFree(counts);
            MemoryUtil.memFree(offsets);
            counts = MemoryUtil.memAllocInt(capacity);
            offsets = MemoryUtil.memAllocPointer(capacity);
        }
        counts.clear();
        offsets.clear();
        for (int i = 0; i < n; i++) {
            counts.put(i, count[i] * 6);
            offsets.put(i, (long) first[i] * 6L * type.bytes);
        }
        counts.limit(n);
        offsets.limit(n);
        GL14C.glMultiDrawElements(GL11.GL_TRIANGLES, counts, type.asGLType, offsets);
        if (borrowed) GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, buffer.ipl$indexBufferId());
    }

    /**
     * Draws the quads of the runs in the order of the buffer's sorted index buffer, so
     * translucent blocks of a piece blend back to front like a whole section does.
     */
    private static void drawSorted(IplSectionRanges.BufferAccess buffer, int[] order, int[] first, int[] count, int n) {
        CHOSEN.clear();
        for (int i = 0; i < n; i++) CHOSEN.set(first[i], first[i] + count[i]);
        int indices = CHOSEN.cardinality() * 6;
        if (indices == 0) return;
        if (sortedIndices.capacity() < indices) {
            MemoryUtil.memFree(sortedIndices);
            sortedIndices = MemoryUtil.memAllocInt(Math.max(indices, sortedIndices.capacity() * 2));
        }
        sortedIndices.clear();
        for (int quad : order) {
            if (!CHOSEN.get(quad)) continue;
            int v = quad << 2;
            sortedIndices.put(v).put(v + 1).put(v + 2).put(v + 2).put(v + 3).put(v);
        }
        sortedIndices.flip();
        if (sortedIndexBuffer == 0) sortedIndexBuffer = GL15C.glGenBuffers();
        GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, sortedIndexBuffer);
        GL15C.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, sortedIndices, GL15.GL_STREAM_DRAW);
        GL11C.glDrawElements(GL11.GL_TRIANGLES, sortedIndices.limit(), GL11.GL_UNSIGNED_INT, 0L);
        GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, buffer.ipl$indexBufferId());
    }

    // ------------------------------------------------------------------ block entities

    /**
     * Extra zone depth of block entities: their models reach past their block (cogwheels, water
     * wheels), so the ones near an aperture are drawn by both pieces, each cut by the plane.
     */
    private static final double BE_SLACK = 1.0;
    /** Flywheel gives a block entity up this much earlier: its removal applies a frame later. */
    private static final double FLYWHEEL_LEAD = 1.0;

    @Nullable private static ClientSubLevel filterSub;
    @Nullable private static Frame filterFrame;
    private static int filterNode;
    @Nullable private static IplPieceMap.Edge filterEdge;

    /**
     * Until {@link #endBlockEntityFilter}, block entities of {@code sub} are drawn only when they
     * belong to piece {@code node}: wholly ({@code edge} null) or inside the zone of {@code edge}.
     */
    public static void beginBlockEntityFilter(ClientSubLevel sub, Frame frame, int node, @Nullable IplPieceMap.Edge edge) {
        filterSub = sub;
        filterFrame = frame;
        filterNode = node;
        filterEdge = edge;
    }

    public static void endBlockEntityFilter() {
        filterSub = null;
        filterFrame = null;
        filterEdge = null;
    }

    /** Whether the active filter drops {@code be} (HEAD of every sub-level BE draw). */
    public static boolean rejectsBlockEntity(BlockEntity be) {
        Frame frame = filterFrame;
        if (frame == null || be == null || Sable.HELPER.getContainingClient(be) != filterSub) return false;
        BlockPos pos = be.getBlockPos();
        int label = frame.label(pos.getX(), pos.getY(), pos.getZ());
        IplPieceMap.Edge e = blockEntityEdge(frame.edges, pos, frame.view.label(pos.getX(), pos.getY(), pos.getZ()), label, BE_SLACK);
        return filterEdge == null ? e != null || label != filterNode : e != filterEdge;
    }

    /** Whether Flywheel may keep drawing the block entity at {@code pos} with the native piece. */
    public static boolean flywheelKeeps(Frame frame, BlockPos pos) {
        int label = frame.label(pos.getX(), pos.getY(), pos.getZ());
        return label == frame.view.nativeNode()
            && blockEntityEdge(frame.edges, pos, frame.view.label(pos.getX(), pos.getY(), pos.getZ()), label, BE_SLACK + FLYWHEEL_LEAD) == null;
    }

    @Nullable
    private static IplPieceMap.Edge blockEntityEdge(IplPieceMap.Edge[] edges, BlockPos pos, int original, int label, double modelReach) {
        double cx = pos.getX() + 0.5, cy = pos.getY() + 0.5, cz = pos.getZ() + 0.5;
        for (IplPieceMap.Edge e : edges) {
            if (e.parent != label && e.child != label) continue;
            if (e.splits(original, label, cx, cy, cz, modelReach)) return e;
        }
        return null;
    }
}

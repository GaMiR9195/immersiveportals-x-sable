package ipl.sable.transit;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import ipl.sable.transit.IplTransitSettlement.Bounds;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.joml.Vector3d;

/** Occupied material only (including implicit native sections), captured once for tick-tail settlement. */
final class IplMaterialSnapshot {
    final int[] counts = new int[IplPieceMap.MAX_PIECE + 1];
    private final LongArrayList[] voxels = new LongArrayList[counts.length];
    private final Bounds[] labelBounds = new Bounds[counts.length];
    private final IplPieceMap map;
    private final Bounds bounds;
    private int total;

    IplMaterialSnapshot(LevelPlot plot, IplPieceMap map) {
        this.map = map;
        int minX = Integer.MAX_VALUE, minY = minX, minZ = minX;
        int maxX = Integer.MIN_VALUE, maxY = maxX, maxZ = maxX;
        for (PlotChunkHolder holder : plot.getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk == null) continue;
            int ox = chunk.getPos().x << 4, oz = chunk.getPos().z << 4;
            LevelChunkSection[] sections = chunk.getSections();
            for (int sy = 0; sy < sections.length; sy++) {
                LevelChunkSection section = sections[sy];
                if (section.hasOnlyAir()) continue;
                int oy = chunk.getSectionYFromSectionIndex(sy) << 4;
                for (int i = 0; i < IplPieceMap.SECTION_VOLUME; i++) {
                    int x = i & 15, y = i >> 4 & 15, z = i >> 8 & 15;
                    if (section.getBlockState(x,y,z).isAir()) continue;
                    int gx = ox+x, gy = oy+y, gz = oz+z;
                    add(map.label(gx, gy, gz), gx, gy, gz);
                    minX = Math.min(minX, gx); maxX = Math.max(maxX, gx);
                    minY = Math.min(minY, gy); maxY = Math.max(maxY, gy);
                    minZ = Math.min(minZ, gz); maxZ = Math.max(maxZ, gz);
                }
            }
        }
        bounds = total == 0 ? null : new Bounds(minX,minY,minZ,maxX+1.0,maxY+1.0,maxZ+1.0);
    }

    private void add(int label, int x, int y, int z) {
        if (voxels[label] == null) voxels[label] = new LongArrayList();
        voxels[label].add(BlockPos.asLong(x,y,z));
        counts[label]++;
        total++;
    }

    int total() { return total; }

    Bounds allBounds() { return bounds; }

    /** Moves actual blocks, not the 4096 air cells of an allocated label section. */
    boolean move(int from, int to) {
        if (from == to || counts[from] == 0) return false;
        LongArrayList source = voxels[from];
        if (voxels[to] == null) voxels[to] = new LongArrayList(source.size());
        for (int i = 0; i < source.size(); i++) {
            long p = source.getLong(i);
            map.setLabel(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p), to);
        }
        voxels[to].addAll(source);
        counts[to] += counts[from]; counts[from] = 0;
        voxels[from] = null;
        labelBounds[from] = labelBounds[to] = null;
        return true;
    }

    /** Assigns a completed whole body; the destination map can belong to a newly tracked mate. */
    boolean assign(int target, IplPieceMap destination) {
        boolean changed = false;
        for (int label = 0; label < counts.length; label++) {
            if (label == target || counts[label] == 0) continue;
            LongArrayList source = voxels[label];
            for (int i = 0; i < source.size(); i++) {
                long p = source.getLong(i);
                destination.setLabel(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p), target);
            }
            changed = true;
        }
        return changed;
    }

    /**
     * Whether any block of a label accepted by {@code labels} has its centre in the slab of
     * aperture {@code a} (plot frame): within {@code depth} of the plane and inside the
     * rectangle grown by {@code margin}. A connected body that is really split by a portal
     * always has material there; one whose two parts meet only around the frame has none.
     */
    boolean occupies(java.util.function.IntPredicate labels, IplPieceMap.Aperture a, double depth, double margin) {
        for (int label = 0; label < counts.length; label++) {
            if (counts[label] == 0 || !labels.test(label)) continue;
            LongArrayList blocks = voxels[label];
            for (int i = 0; i < blocks.size(); i++) {
                long block = blocks.getLong(i);
                double dx = BlockPos.getX(block) + 0.5 - a.ox();
                double dy = BlockPos.getY(block) + 0.5 - a.oy();
                double dz = BlockPos.getZ(block) + 0.5 - a.oz();
                if (Math.abs(dx * a.nx() + dy * a.ny() + dz * a.nz()) <= depth
                    && Math.abs(dx * a.wx() + dy * a.wy() + dz * a.wz()) <= a.halfW() + margin
                    && Math.abs(dx * a.hx() + dy * a.hy() + dz * a.hz()) <= a.halfH() + margin) return true;
            }
        }
        return false;
    }

    /** All members share a rigid parent mapping, so this group envelope is reusable for every path. */
    Bounds boundsIn(Pose3dc pose, Pose3dc reference) {
        return transform(allBounds(), pose, reference);
    }

    /** Tight bounds of this label's actual blocks, never the whole plot or an empty group image. */
    Bounds boundsIn(int label, Pose3dc pose, Pose3dc reference) {
        if (counts[label] == 0) return null;
        Bounds bounds = labelBounds[label];
        if (bounds == null) {
            int minX = Integer.MAX_VALUE, minY = minX, minZ = minX;
            int maxX = Integer.MIN_VALUE, maxY = maxX, maxZ = maxX;
            LongArrayList blocks = voxels[label];
            for (int i = 0; i < blocks.size(); i++) {
                long block = blocks.getLong(i);
                int x = BlockPos.getX(block), y = BlockPos.getY(block), z = BlockPos.getZ(block);
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            }
            bounds = labelBounds[label] = new Bounds(minX,minY,minZ,maxX+1.0,maxY+1.0,maxZ+1.0);
        }
        return transform(bounds, pose, reference);
    }

    private static Bounds transform(Bounds b, Pose3dc pose, Pose3dc reference) {
        if (b == null) return null;
        if (pose == reference) return b;
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        Vector3d p = new Vector3d();
        for (int i = 0; i < 8; i++) {
            pose.transformPosition(p.set((i&1)==0 ? b.minX() : b.maxX(),
                (i&2)==0 ? b.minY() : b.maxY(), (i&4)==0 ? b.minZ() : b.maxZ()));
            reference.transformPositionInverse(p);
            minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
            minZ = Math.min(minZ, p.z); maxZ = Math.max(maxZ, p.z);
        }
        return new Bounds(minX,minY,minZ,maxX,maxY,maxZ);
    }
}

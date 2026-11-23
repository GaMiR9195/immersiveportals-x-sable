package ipl.sable.compat;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.IControlContraption;
import ipl.sable.mixin.ContraptionColliderInvoker;
import ipl.sable.mixin.ControlledContraptionEntityAccessor;
import ipl.sable.transit.IplContraptionShape;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Create-facing side of {@link ipl.sable.transit.IplContraptions}; loaded only with Create. */
public final class IplCreateContraptions {

    /** How far from its anchor a contraption that is not controlled by a block looks for its holder. */
    private static final int HOLDER_SEARCH = 2;
    /** Half diagonal of a block. */
    private static final double BLOCK_CORNER = 0.8660254037844386;
    private static final Vec3 ANCHOR_CENTRE = new Vec3(0.5, 0.5, 0.5);

    /** {@code radius}: farthest block corner from the centre of the contraption's anchor block. */
    private record Blocks(int size, BlockPos[] positions, double radius) {}

    /** Non-air local positions per contraption; contraption block maps change only on (re)assembly. Any thread. */
    private static final Map<Contraption, Blocks> BLOCKS = Collections.synchronizedMap(new WeakHashMap<>());

    private IplCreateContraptions() {}

    public static boolean isContraption(Entity entity) {
        return entity instanceof AbstractContraptionEntity;
    }

    public static boolean isHollow(Entity entity) {
        return entity instanceof AbstractContraptionEntity c && c.getContraption() == null;
    }

    /** The contraption at {@code partialTick} in its own level's frame (the plot of its body), or null. */
    @Nullable
    public static IplContraptionShape shape(Entity entity, float partialTick) {
        if (!(entity instanceof AbstractContraptionEntity c) || c.getContraption() == null) return null;
        BlockPos[] blocks = blocksOf(c.getContraption()).positions();
        if (blocks.length == 0) return null;
        // toGlobalVector is affine: the image of the origin and of the unit axes is the whole transform.
        Vec3 o = c.toGlobalVector(Vec3.ZERO, partialTick);
        Vec3 ex = c.toGlobalVector(new Vec3(1, 0, 0), partialTick).subtract(o);
        Vec3 ey = c.toGlobalVector(new Vec3(0, 1, 0), partialTick).subtract(o);
        Vec3 ez = c.toGlobalVector(new Vec3(0, 0, 1), partialTick).subtract(o);
        double[] centres = new double[blocks.length * 3];
        for (int i = 0; i < blocks.length; i++) {
            double lx = blocks[i].getX() + 0.5, ly = blocks[i].getY() + 0.5, lz = blocks[i].getZ() + 0.5;
            centres[i * 3] = o.x + ex.x * lx + ey.x * ly + ez.x * lz;
            centres[i * 3 + 1] = o.y + ex.y * lx + ey.y * ly + ez.y * lz;
            centres[i * 3 + 2] = o.z + ex.z * lx + ey.z * ly + ez.z * lz;
        }
        Vec3 anchor = o.add(ex.add(ey).add(ez).scale(0.5));
        BlockPos holder = holderOf(c, anchor);
        double[] sweep = c instanceof ControlledContraptionEntity controlled && controlled.getRotationAxis() != null
            ? sweepAround(anchor, centres) : null;
        return new IplContraptionShape(entity, holder.getX() + 0.5, holder.getY() + 0.5, holder.getZ() + 0.5,
            blocks, centres, sweep);
    }

    public static void collideEntities(Entity entity) {
        if (entity instanceof AbstractContraptionEntity contraption) ContraptionColliderInvoker.ipl$collideEntities(contraption);
    }

    /**
     * Moves the controller of a controlled contraption by {@code (dx, 0, dz)} with its plot. Must run
     * before the contraption enters its new level: clients get the controller from its spawn data
     * only, and one left behind made the client hang the contraption on a block of the old plot.
     */
    public static boolean shiftController(Entity entity, int dx, int dz) {
        if (!(entity instanceof ControlledContraptionEntity) || dx == 0 && dz == 0) return false;
        ControlledContraptionEntityAccessor access = (ControlledContraptionEntityAccessor) entity;
        BlockPos controller = access.ipl$controllerPos();
        if (controller == null) return false;
        access.ipl$setControllerPos(controller.offset(dx, 0, dz));
        return true;
    }

    /** A moved controlled contraption is attached to its controller, which drives it again at once. */
    public static boolean attachController(Entity entity) {
        if (!(entity instanceof ControlledContraptionEntity contraption)) return false;
        BlockPos controller = ((ControlledContraptionEntityAccessor) entity).ipl$controllerPos();
        if (controller == null
            || !(contraption.level().getBlockEntity(controller) instanceof IControlContraption control)) return false;
        if (!control.isAttachedTo(contraption)) control.attach(contraption);
        return true;
    }

    /**
     * Box {@code [minX, minY, minZ, maxX, maxY, maxZ]} in its level's frame around everything the
     * contraption covers, in any angle a bearing turns it to: a sphere around its anchor block,
     * without walking its blocks.
     */
    @Nullable
    public static double[] reach(Entity entity) {
        if (!(entity instanceof AbstractContraptionEntity c) || c.getContraption() == null) return null;
        Blocks blocks = blocksOf(c.getContraption());
        if (blocks.positions().length == 0) return null;
        Vec3 o = c.toGlobalVector(ANCHOR_CENTRE, 1.0f);
        double r = blocks.radius();
        return new double[]{o.x - r, o.y - r, o.z - r, o.x + r, o.y + r, o.z + r};
    }

    private static Blocks blocksOf(Contraption contraption) {
        Map<BlockPos, StructureTemplate.StructureBlockInfo> map = contraption.getBlocks();
        Blocks cached = BLOCKS.get(contraption);
        if (cached != null && cached.size() == map.size()) return cached;
        List<BlockPos> out = new ArrayList<>(map.size());
        double radiusSqr = 0.0;
        for (Map.Entry<BlockPos, StructureTemplate.StructureBlockInfo> entry : map.entrySet()) {
            if (entry.getValue().state().isAir()) continue;
            BlockPos pos = entry.getKey().immutable();
            out.add(pos);
            radiusSqr = Math.max(radiusSqr, pos.distSqr(BlockPos.ZERO));
        }
        Blocks blocks = new Blocks(map.size(), out.toArray(new BlockPos[0]), Math.sqrt(radiusSqr) + BLOCK_CORNER);
        BLOCKS.put(contraption, blocks);
        return blocks;
    }

    /** The body block a contraption hangs on: its controller, else the solid block nearest its anchor. */
    private static BlockPos holderOf(AbstractContraptionEntity contraption, Vec3 anchor) {
        if (contraption instanceof ControlledContraptionEntity) {
            BlockPos controller = ((ControlledContraptionEntityAccessor) contraption).ipl$controllerPos();
            if (controller != null) return controller;
        }
        Level level = contraption.level();
        BlockPos centre = BlockPos.containing(anchor);
        BlockPos best = centre;
        double bestDistance = Double.POSITIVE_INFINITY;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = -HOLDER_SEARCH; dx <= HOLDER_SEARCH; dx++) {
            for (int dy = -HOLDER_SEARCH; dy <= HOLDER_SEARCH; dy++) {
                for (int dz = -HOLDER_SEARCH; dz <= HOLDER_SEARCH; dz++) {
                    at.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                    double distance = anchor.distanceToSqr(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
                    if (distance >= bestDistance || level.getBlockState(at).isAir()) continue;
                    bestDistance = distance;
                    best = at.immutable();
                }
            }
        }
        return best;
    }

    /** Sphere around the rotation centre that holds every block in any rotation. */
    private static double[] sweepAround(Vec3 centre, double[] centres) {
        double r = 0.0;
        for (int i = 0; i < centres.length; i += 3) {
            r = Math.max(r, centre.distanceToSqr(centres[i], centres[i + 1], centres[i + 2]));
        }
        return new double[]{centre.x, centre.y, centre.z, Math.sqrt(r) + BLOCK_CORNER};
    }
}

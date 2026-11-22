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

    private record Blocks(int size, BlockPos[] positions) {}

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
        BlockPos[] blocks = blocksOf(c.getContraption());
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
     * A controlled contraption moved with its plot by {@code (dx, 0, dz)}: it keeps its controller,
     * which drives it again at once (a bearing keeps turning it, nothing waits for the next tick).
     */
    public static boolean followPlot(Entity entity, int dx, int dz) {
        if (!(entity instanceof ControlledContraptionEntity contraption)) return false;
        ControlledContraptionEntityAccessor access = (ControlledContraptionEntityAccessor) entity;
        BlockPos controller = access.ipl$controllerPos();
        if (controller == null) return false;
        if (dx != 0 || dz != 0) {
            controller = controller.offset(dx, 0, dz);
            access.ipl$setControllerPos(controller);
        }
        if (!(contraption.level().getBlockEntity(controller) instanceof IControlContraption control)) return false;
        if (!control.isAttachedTo(contraption)) control.attach(contraption);
        return true;
    }

    private static BlockPos[] blocksOf(Contraption contraption) {
        Map<BlockPos, StructureTemplate.StructureBlockInfo> map = contraption.getBlocks();
        Blocks cached = BLOCKS.get(contraption);
        if (cached != null && cached.size() == map.size()) return cached.positions();
        List<BlockPos> out = new ArrayList<>(map.size());
        for (Map.Entry<BlockPos, StructureTemplate.StructureBlockInfo> entry : map.entrySet()) {
            if (!entry.getValue().state().isAir()) out.add(entry.getKey().immutable());
        }
        BlockPos[] positions = out.toArray(new BlockPos[0]);
        BLOCKS.put(contraption, new Blocks(map.size(), positions));
        return positions;
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
        return new double[]{centre.x, centre.y, centre.z, Math.sqrt(r) + 0.8660254037844386};
    }
}

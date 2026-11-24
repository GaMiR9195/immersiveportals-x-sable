package ipl.sable.compat;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Create kinetics of blocks copied into another plot; loaded only with Create. */
public final class IplCreateKinetics {

    private IplCreateKinetics() {}

    /**
     * A copied kinetic block keeps the speed, source and network it was saved with, like a block
     * loaded from disk: the copy's neighbour pass would otherwise clear every network (Create's
     * {@code KineticBlock.updateIndirectNeighbourShapes}), stopping bearings, windmills and
     * everything they drive until the networks were rebuilt, and snapping turning contraptions to
     * a whole angle. The source moves with the plot by {@code (dx, 0, dz)}. Released by
     * {@link #release} once the neighbour pass is over.
     */
    public static void hold(BlockEntity be, int dx, int dz) {
        if (!(be instanceof KineticBlockEntity kinetic)) return;
        if (kinetic.source != null) kinetic.source = kinetic.source.offset(dx, 0, dz);
        kinetic.preventSpeedUpdate++;
    }

    public static void release(BlockEntity be) {
        if (be instanceof KineticBlockEntity kinetic && kinetic.preventSpeedUpdate > 0) kinetic.preventSpeedUpdate--;
    }
}

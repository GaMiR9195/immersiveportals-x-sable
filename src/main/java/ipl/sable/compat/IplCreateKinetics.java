package ipl.sable.compat;

import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Create kinetics of blocks copied into another plot; loaded only with Create. */
public final class IplCreateKinetics {

    private IplCreateKinetics() {}

    /**
     * The copy's neighbour pass clears the network of every kinetic block (Create's
     * {@code KineticBlock.updateIndirectNeighbourShapes}). Followers get their speed back from
     * their source on the next tick; a source (windmill, water wheel, motor) only when it applies
     * its own speed again, which it then does on its next tick.
     */
    public static void restartSource(BlockEntity be) {
        if (be instanceof GeneratingKineticBlockEntity source) source.reActivateSource = true;
    }
}

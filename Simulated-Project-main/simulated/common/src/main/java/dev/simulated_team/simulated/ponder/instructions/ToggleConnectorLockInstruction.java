package dev.simulated_team.simulated.ponder.instructions;

import dev.simulated_team.simulated.content.blocks.docking_connector.DockingConnectorBlockEntity;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.instruction.PonderInstruction;
import net.minecraft.core.BlockPos;

public class ToggleConnectorLockInstruction extends PonderInstruction {
    BlockPos pos;
    boolean lock;
    public ToggleConnectorLockInstruction(BlockPos pos, boolean lock)
    {
        this.pos = pos;
        this.lock = lock;
    }
    @Override
    public boolean isComplete() {
        return true;
    }

    @Override
    public void tick(PonderScene scene) {
        final PonderLevel world = scene.getWorld();
        if (world.getBlockEntity(pos) instanceof final DockingConnectorBlockEntity be) {
            be.setVirtualLock(lock);
            if(!lock)
                be.tank.disconnect();
        }
    }
}

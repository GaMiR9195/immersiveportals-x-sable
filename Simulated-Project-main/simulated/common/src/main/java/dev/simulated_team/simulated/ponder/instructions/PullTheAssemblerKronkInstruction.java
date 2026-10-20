package dev.simulated_team.simulated.ponder.instructions;

import dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.instruction.PonderInstruction;
import net.minecraft.core.BlockPos;

public class PullTheAssemblerKronkInstruction extends PonderInstruction {
    protected final BlockPos assemblerPos;
    protected final boolean isAssembling;
    protected final boolean instantaneous;

    public PullTheAssemblerKronkInstruction(final BlockPos assemblerPos, final boolean isAssembling, final boolean instantaneous) {
        this.assemblerPos = assemblerPos;
        this.isAssembling = isAssembling;
        this.instantaneous = instantaneous;
    }

    @Override
    public boolean isComplete() {
        return true;
    }

    @Override
    public void tick(final PonderScene scene) {
        final PonderLevel world = scene.getWorld();
        if (world.getBlockEntity(this.assemblerPos) instanceof final PhysicsAssemblerBlockEntity be) {
            be.clientFlickLeverTo(this.isAssembling);
            if (this.instantaneous) {
                be.jerkLever();
            }
        }
    }
}

package dev.eriksonn.aeronautics.mixin.ponder;

import net.createmod.ponder.foundation.instruction.TickingInstruction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TickingInstruction.class)
public interface TickingInstructionAccessor {
    @Accessor
    void setRemainingTicks(int time);
}

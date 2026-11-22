package ipl.sable.mixin;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Create's per-tick entity collision of one contraption. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.ContraptionCollider", remap = false)
public interface ContraptionColliderInvoker {

    @Invoker("collideEntities")
    static void ipl$collideEntities(AbstractContraptionEntity contraption) {
        throw new AssertionError();
    }
}

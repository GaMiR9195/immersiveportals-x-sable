package ipl.sable.mixin;

import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A loaded controlled contraption (spawned on a client, moved into another level) starts at rest
 * at its saved angle: its previous angle was 0, so its first frame and tick saw it turn all the
 * way from its assembly angle.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.ControlledContraptionEntity", remap = false)
public abstract class ControlledContraptionLoadMixin {

    @Shadow
    protected float angle;

    @Shadow
    protected float prevAngle;

    @Inject(method = "readAdditional", at = @At("TAIL"))
    private void ipl$restAtLoadedAngle(CompoundTag compound, boolean spawnPacket, CallbackInfo ci) {
        prevAngle = angle;
    }
}

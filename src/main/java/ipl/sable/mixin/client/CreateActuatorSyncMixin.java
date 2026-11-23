package ipl.sable.mixin.client;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link CreateBearingSyncMixin} for linear actuators (pistons, rope and elevator pulleys): a new
 * client block entity takes the server's extension instead of sliding out from 0.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity", remap = false)
public abstract class CreateActuatorSyncMixin {

    @Shadow
    public float offset;

    @Unique
    private boolean ipl$synced;

    @Inject(method = "read", at = @At("HEAD"))
    private void ipl$adoptFirstOffset(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket,
                                      CallbackInfo ci) {
        if (!clientPacket || ipl$synced) return;
        ipl$synced = true;
        offset = tag.getFloat("Offset");
    }
}

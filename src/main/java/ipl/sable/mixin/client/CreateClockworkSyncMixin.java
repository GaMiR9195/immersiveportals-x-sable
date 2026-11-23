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

/** {@link CreateBearingSyncMixin} for the clockwork bearing's two hands. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity", remap = false)
public abstract class CreateClockworkSyncMixin {

    @Shadow
    protected float hourAngle;

    @Shadow
    protected float minuteAngle;

    @Unique
    private boolean ipl$synced;

    @Inject(method = "read", at = @At("HEAD"))
    private void ipl$adoptFirstAngles(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket,
                                      CallbackInfo ci) {
        if (!clientPacket || ipl$synced) return;
        ipl$synced = true;
        hourAngle = tag.getFloat("HourAngle");
        minuteAngle = tag.getFloat("MinuteAngle");
    }
}

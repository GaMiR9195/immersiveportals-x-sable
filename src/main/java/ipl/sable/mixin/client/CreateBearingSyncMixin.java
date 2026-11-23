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
 * The first angle a client bearing hears of is taken as is. Create eases every client update in
 * from the angle the bearing had, which for a new block entity (a chunk sent again, a body moved
 * into another dimension or another plot) is 0: the contraption snapped back to its assembly
 * angle and caught up over the next ticks. Covers every bearing built on Create's (windmill,
 * propeller, borehead).
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity", remap = false)
public abstract class CreateBearingSyncMixin {

    @Shadow
    protected float angle;

    @Unique
    private boolean ipl$synced;

    @Inject(method = "read", at = @At("HEAD"))
    private void ipl$adoptFirstAngle(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket,
                                     CallbackInfo ci) {
        if (!clientPacket || ipl$synced) return;
        ipl$synced = true;
        angle = tag.getFloat("Angle");
    }
}

package ipl.sable.mixin.client;

import ipl.sable.client.IplContraptionCollisionClient;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** After Create collided the player's level: contraptions of other levels whose pieces are in it. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.ContraptionHandler", remap = false)
public abstract class ContraptionHandlerRemoteMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private static void ipl$collideProjectedPieces(Level world, CallbackInfo ci) {
        if (world.isClientSide()) IplContraptionCollisionClient.collideFromOtherLevels(world);
    }
}

package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sable's sub-level sky-light scale is sampled at the ship's WORLD-space bounds
 * ({@code computeSubLevelSkyLight(pose)}): a ship under a roof or in a cave darkens its
 * sky-lit blocks. The sampling level is {@code this.getLevel()} — the level holding the
 * plot. Hosted, that is the void {@code ipl_sable:sublevels} client level, whose empty
 * columns report full sky light everywhere, so hosted ships never darken and every
 * consumer of the scale (block-entity light, Flywheel embeddings, chunked render data,
 * terrain particles) rendered too bright. Sample the level the ship is actually in.
 */
@Mixin(value = ClientSubLevel.class, remap = false)
public abstract class IplHostedSubLevelSkyLightMixin {

    @WrapOperation(
        method = "computeSubLevelSkyLight",
        at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/ClientSubLevel;getLevel()Lnet/minecraft/client/multiplayer/ClientLevel;"),
        require = 1
    )
    private ClientLevel ipl$sampleParentWorld(ClientSubLevel self, Operation<ClientLevel> original) {
        ClientLevel storage = original.call(self);
        if (!IplDimAgnostic.isHostingLevel(storage)) return storage;
        Level parent = IplDimAgnostic.getParentLevel(self);
        return parent instanceof ClientLevel world && !IplDimAgnostic.isHostingLevel(world) ? world : storage;
    }
}

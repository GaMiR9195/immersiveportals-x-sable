package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import ipl.sable.client.IplClientBeIdentity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Client identity fix: {@code getLevel()} on a hosted plot block entity returns the owning
 * sub-level's PARENT {@code ClientLevel} — the dimension the ship actually occupies —
 * instead of the {@code ipl_sable:sublevels} hosting level. See {@link IplClientBeIdentity}
 * for the full rationale (this is THE single root behind invisible ropes/springs/BE parts,
 * split {@code WorldAttached} registries, and wrong container lookups in every sub-level
 * mod).
 *
 * <p>Scope: the public GETTER only, client only. Vanilla's internal storage machinery uses
 * the {@code level} field directly and keeps hosting-level semantics; mod code (Create
 * behaviors, Simulated/Aeronautics/Offroad renderers and handlers) reaches level identity
 * through this getter and now sees stock semantics.
 *
 * <p>Client-only mixin: registered under the {@code "client"} key, so the server never pays
 * the check (server identity is handled contextually by the world-frame router).
 */
@Mixin(BlockEntity.class)
public abstract class IplHostedBeLevelIdentityMixin {

    @Shadow
    @Final
    protected BlockPos worldPosition;

    @ModifyReturnValue(method = "getLevel", at = @At("RETURN"))
    private Level ipl$hostedPlotBeParentIdentity(Level original) {
        if (original == null || !original.isClientSide()) {
            return original;
        }
        return IplClientBeIdentity.mapHostedBeLevel(original, this.worldPosition);
    }
}

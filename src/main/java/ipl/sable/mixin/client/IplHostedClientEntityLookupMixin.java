package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Client entity-by-NETWORK-ID addressing across the plot-space boundary — see
 * {@link ipl.sable.client.IplClientEntityLookup} for the full rationale (an entity stored
 * with a hosted ship lives in the storage client level while the entities it references by
 * id — owners, leash holders, pair links — live in the world it flies through, and vice
 * versa).
 *
 * <p>Targets {@code ClientLevel#getEntity(int)}: the declaration on {@code Level} is
 * abstract, so an injector there never applied. Network ids are server-global, so the
 * fallthrough can never alias a different entity.
 */
@Mixin(ClientLevel.class)
public abstract class IplHostedClientEntityLookupMixin {

    @ModifyReturnValue(method = "getEntity(I)Lnet/minecraft/world/entity/Entity;", at = @At("RETURN"), require = 1)
    private Entity ipl$plotSpaceClientEntityAddressing(Entity original, int id) {
        if (original != null) {
            return original;
        }
        return ipl.sable.client.IplClientEntityLookup.resolveAcrossPlotSpace((ClientLevel) (Object) this, id);
    }
}

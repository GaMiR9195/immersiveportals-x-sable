package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.ClientWorldLoader;

import java.util.UUID;

/**
 * Client-side cross-level sub-level resolution — the structural companion of the
 * parent-stamped packet routing ({@link ipl.sable.dim.IplHostedPacketRouting}).
 *
 * <p>Under the stock-client model each ship lives in exactly ONE {@code ClientLevel}'s
 * container (its parent dimension). Packets that cannot carry a single-dimension stamp
 * (movement snapshot batches spanning ships of several parents, the UDP fast path, and
 * ordering races around a parent flip) are delivered under the player's current level.
 * Their handlers resolve the target by plot slot or UUID against that level's container;
 * on a miss, fall through to every other loaded {@code ClientLevel}'s container.
 *
 * <p>Unambiguous by construction for hosted ships: plot slots come from the single
 * hosting-dimension allocator, so one slot never describes two ships. Miss-only, so
 * same-level lookups keep stock behavior and cost.
 */
@Pseudo
@Mixin(value = SubLevelContainer.class, remap = false)
public abstract class IplClientSubLevelLookupBridgeMixin {

    @Shadow
    public abstract Level getLevel();

    @ModifyReturnValue(
        method = "getSubLevel(II)Ldev/ryanhcode/sable/sublevel/SubLevel;",
        at = @At("RETURN"),
        remap = false
    )
    private SubLevel ipl$bridgeSlotLookupAcrossClientLevels(SubLevel original, int x, int z) {
        if (original != null || ipl.sable.client.IplClientLookupBridge.isLocalOnly()
            || !ipl$isClientContainer()) {
            return original;
        }
        return ipl.sable.client.IplClientLookupBridge.withLocalOnly(() -> {
            for (ClientLevel other : ClientWorldLoader.getClientWorlds()) {
                if (other == this.getLevel()) continue;
                SubLevelContainer container = SubLevelContainer.getContainer((Level) other);
                if (container == null || container == (Object) this) continue;
                SubLevel found = container.getSubLevel(x, z);
                if (found != null) {
                    return found;
                }
            }
            return null;
        });
    }

    @ModifyReturnValue(
        method = "getSubLevel(Ljava/util/UUID;)Ldev/ryanhcode/sable/sublevel/SubLevel;",
        at = @At("RETURN"),
        remap = false
    )
    private SubLevel ipl$bridgeUuidLookupAcrossClientLevels(SubLevel original, UUID uuid) {
        if (original != null || ipl.sable.client.IplClientLookupBridge.isLocalOnly()
            || !ipl$isClientContainer()) {
            return original;
        }
        return ipl.sable.client.IplClientLookupBridge.withLocalOnly(() -> {
            for (ClientLevel other : ClientWorldLoader.getClientWorlds()) {
                if (other == this.getLevel()) continue;
                SubLevelContainer container = SubLevelContainer.getContainer((Level) other);
                if (container == null || container == (Object) this) continue;
                SubLevel found = container.getSubLevel(uuid);
                if (found != null) {
                    return found;
                }
            }
            return null;
        });
    }

    private boolean ipl$isClientContainer() {
        Level level = this.getLevel();
        return level != null && level.isClientSide() && ClientWorldLoader.getIsInitialized();
    }
}

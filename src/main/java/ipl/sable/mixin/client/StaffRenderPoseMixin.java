package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplStraddleStaffPick;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.ClientWorldLoader;

import java.util.UUID;

/**
 * Own every pose read used by staff held-item aim, lock marker, and client drag anchor.
 * Simulated uses separate render/client classes, so broad target coverage is intentional.
 */
@Pseudo
@Mixin(
    targets = {
        "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffRenderHandler",
        "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItemRenderer",
        "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler"
    },
    remap = false
)
public abstract class StaffRenderPoseMixin {

    @WrapOperation(
        method = "*",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/ClientSubLevel;renderPose()Ldev/ryanhcode/sable/companion/math/Pose3dc;"
        )
    )
    private static Pose3dc ipl$portalPose(ClientSubLevel sub, Operation<Pose3dc> original) {
        return IplStraddleStaffPick.mapStaffRenderPose(sub, original.call(sub));
    }

    @WrapOperation(
        method = "*",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/ClientSubLevel;renderPose(F)Ldev/ryanhcode/sable/companion/math/Pose3dc;"
        )
    )
    private static Pose3dc ipl$portalPosePt(
        ClientSubLevel sub, float partialTick, Operation<Pose3dc> original
    ) {
        return IplStraddleStaffPick.mapStaffRenderPose(sub, original.call(sub, partialTick));
    }

    /** The beam owner may be a player of another client world (portal passes): resolve it there too. */
    @WrapOperation(
        method = "onRender",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getPlayerByUUID(Ljava/util/UUID;)Lnet/minecraft/world/entity/player/Player;"
        )
    )
    private static Player ipl$findBeamOwnerAcrossPortalWorlds(
        ClientLevel level, UUID uuid, Operation<Player> original
    ) {
        Player found = original.call(level, uuid);
        if (found != null) return found;
        for (ClientLevel world : ClientWorldLoader.getClientWorlds()) {
            found = world.getPlayerByUUID(uuid);
            if (found != null) return found;
        }
        return null;
    }

}

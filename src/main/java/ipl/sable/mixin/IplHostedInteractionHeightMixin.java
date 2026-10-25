package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ipl.sable.dim.IplHostedPlotHeight;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Only the addressed plot position selects storage's build ceiling. Reach, ownership,
 * permission and packet validation stay vanilla/Sable. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class IplHostedInteractionHeightMixin {
    @WrapOperation(method = "handleUseItemOn", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I"), require = 1)
    private int ipl$placementCeiling(Level level, Operation<Integer> original, ServerboundUseItemOnPacket packet) {
        var profile = IplHostedPlotHeight.profile(level, packet.getHitResult().getBlockPos());
        return profile == level ? original.call(level) : profile.getMaxBuildHeight();
    }
    @WrapOperation(method = "handlePlayerAction", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I"), require = 1)
    private int ipl$breakCeiling(Level level, Operation<Integer> original, ServerboundPlayerActionPacket packet) {
        var profile = IplHostedPlotHeight.profile(level, packet.getPos());
        return profile == level ? original.call(level) : profile.getMaxBuildHeight();
    }
}

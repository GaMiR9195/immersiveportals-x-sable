package ipl.sable.mixin;

import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.transit.IplShipNetherPortal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SHIP-BORNE PORTAL feature hook: restore anchored portal shapes to world coordinates and
 * release their anchors before a ship is disassembled back into blocks.
 *
 * <p>Portal-anchor release is part of the ship-borne-portal lifecycle and needs the
 * disassembly transform (goal + rotation), which only exists at this chokepoint — the single
 * static method every Simulated disassembly path (assembler block, commands) funnels
 * through.
 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.util.SimAssemblyHelper", remap = false)
public abstract class SimAssemblyHelperMixin {

    @Inject(method = "disassembleSubLevel", at = @At("HEAD"))
    private static void ipl$restoreAnchoredPortals(
        Level level, SubLevel subLevel, BlockPos subLevelAnchor, BlockPos goal,
        Rotation rotation, boolean playSound, CallbackInfo ci
    ) {
        if (subLevel.getLevel() instanceof ServerLevel parent) {
            IplShipNetherPortal.restoreOnDisassembly(parent, subLevel, subLevelAnchor, goal, rotation);
        }
    }
}

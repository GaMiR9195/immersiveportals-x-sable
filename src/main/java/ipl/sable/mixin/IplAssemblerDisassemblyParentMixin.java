package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.transit.IplShipNetherPortal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Simulated's physics assembler disassembles into the PARENT dimension.
 *
 * <p>The assembler block rides the ship it assembled, so post-rehome its
 * {@code this.level} is the plot-hosting dimension — stock Simulated then (a) runs
 * the ground/build-height checks against the hosting void (mid-air check always
 * fails or always passes wrongly) and (b) hands the hosting level to
 * {@code disassembleSubLevel} as the TARGET, placing the ship's blocks into the
 * sublevels dimension at world coordinates: the ship silently vanishes. Both are
 * re-pointed at the ship's effective parent level; the plot bridge covers the
 * plot-coordinate block reads from there. Anchored portals get their shapes
 * restored to world coords and released before the move
 * ({@link IplShipNetherPortal#restoreOnDisassembly}).
 */
@Pseudo
@Mixin(
    targets = "dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity",
    remap = false
)
public abstract class IplAssemblerDisassemblyParentMixin {

    @WrapOperation(
        method = "placeIntoWorld",
        at = @At(
            value = "INVOKE",
            target = "Ldev/simulated_team/simulated/util/SimAssemblyHelper;disassembleSubLevel("
                + "Lnet/minecraft/world/level/Level;"
                + "Ldev/ryanhcode/sable/sublevel/SubLevel;"
                + "Lnet/minecraft/core/BlockPos;"
                + "Lnet/minecraft/core/BlockPos;"
                + "Lnet/minecraft/world/level/block/Rotation;Z)V",
            remap = false
        ),
        require = 0
    )
    private void ipl$disassembleIntoParent(
        Level level, SubLevel subLevel, BlockPos subLevelAnchor, BlockPos goal,
        Rotation rotation, boolean playSound, Operation<Void> original
    ) {
        ServerLevel parent = IplDimAgnostic.getServerParentLevel(subLevel);
        if (parent != null) {
            IplShipNetherPortal.restoreOnDisassembly(
                parent, subLevel, subLevelAnchor, goal, rotation);
        }
        original.call(parent != null ? parent : level,
            subLevel, subLevelAnchor, goal, rotation, playSound);
    }

    @WrapOperation(
        method = "throwDisassemblyExceptions",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;"
        ),
        require = 0
    )
    private LevelChunk ipl$groundCheckInParent(
        Level level, int chunkX, int chunkZ, Operation<LevelChunk> original,
        ServerSubLevel subLevel
    ) {
        ServerLevel parent = IplDimAgnostic.getServerParentLevel(subLevel);
        return original.call(parent != null ? (Level) parent : level, chunkX, chunkZ);
    }

    @WrapOperation(
        method = "throwDisassemblyExceptions",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I"
        ),
        require = 0
    )
    private int ipl$maxBuildHeightOfParent(
        Level level, Operation<Integer> original, ServerSubLevel subLevel
    ) {
        ServerLevel parent = IplDimAgnostic.getServerParentLevel(subLevel);
        return original.call(parent != null ? (Level) parent : level);
    }

    @WrapOperation(
        method = "throwDisassemblyExceptions",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I"
        ),
        require = 0
    )
    private int ipl$minBuildHeightOfParent(
        Level level, Operation<Integer> original, ServerSubLevel subLevel
    ) {
        ServerLevel parent = IplDimAgnostic.getServerParentLevel(subLevel);
        return original.call(parent != null ? (Level) parent : level);
    }
}

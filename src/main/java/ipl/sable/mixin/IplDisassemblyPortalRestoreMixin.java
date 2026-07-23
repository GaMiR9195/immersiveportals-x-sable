package ipl.sable.mixin;

import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.transit.IplShipNetherPortal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ship-anchored portals must be restored to world coordinates when their carrier is
 * disassembled. Hooked at Sable's own {@code SubLevelAssemblyHelper.moveBlocks} — the one
 * choke point every disassembly (Simulated's assembler, commands, any future mod) funnels
 * through — instead of per-mod wraps on each assembler class.
 *
 * <p>Direction detection: a DISASSEMBLY maps plot-frame coordinates (|coord| in the plot
 * grid, ~20M blocks) to world-frame coordinates; an ASSEMBLY maps the other way. Only the
 * plot→world direction of a hosted ship's plot triggers the restore.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.api.SubLevelAssemblyHelper", remap = false)
public abstract class IplDisassemblyPortalRestoreMixin {

    @Inject(method = "moveBlocks", at = @At("HEAD"), remap = false, require = 0)
    private static void ipl$restoreAnchoredPortalsOnDisassembly(
        ServerLevel level,
        dev.ryanhcode.sable.api.SubLevelAssemblyHelper.AssemblyTransform transformObj,
        Iterable<BlockPos> blocks,
        CallbackInfo ci
    ) {
        if (!(((Object) transformObj) instanceof IplAssemblyTransformAccessor transform)) {
            return;
        }
        BlockPos anchor = transform.ipl$anchorPos();
        BlockPos goal = transform.ipl$resultingAnchorPos();
        boolean plotToWorld =
            (Math.abs(anchor.getX()) >= 1_000_000 || Math.abs(anchor.getZ()) >= 1_000_000)
                && Math.abs(goal.getX()) < 1_000_000 && Math.abs(goal.getZ()) < 1_000_000;
        if (!plotToWorld) {
            return;
        }

        // The transform's plot anchor identifies the ship being disassembled.
        dev.ryanhcode.sable.api.sublevel.SubLevelContainer container =
            IplDimAgnostic.getHostingContainerFor(level);
        if (container == null) return;
        dev.ryanhcode.sable.sublevel.plot.LevelPlot plot =
            container.getPlot(anchor.getX() >> 4, anchor.getZ() >> 4);
        if (plot == null) return;
        SubLevel ship = plot.getSubLevel();
        if (ship == null || !IplDimAgnostic.isHosted(ship)) return;

        ServerLevel parent = IplDimAgnostic.getServerParentLevel(ship);
        if (parent == null) return;
        IplShipNetherPortal.restoreOnDisassembly(parent, ship, anchor, goal, transform.ipl$rotation());
    }
}

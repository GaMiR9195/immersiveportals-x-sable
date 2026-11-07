package ipl.sable.mixin;

import ipl.sable.transit.IplProjectionRaycast;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Server twin of {@code ClientLevelMixin}: projectiles, mob line of sight,
 * explosions' exposure rays, Create's raycasts, item use validation — every server
 * {@code clip} hits straddling ships piece-by-piece (each piece only on its own blocks,
 * images through every session including chains, foreign images included).
 * Без этого стрела проходила бы сквозь видимую часть корабля за порталом и попадала в
 * "призрака" на исходной стороне.
 */
@Mixin(value = ServerLevel.class, priority = 1200)
public abstract class ServerLevelMixin implements BlockGetter {

    @Override
    public BlockHitResult clip(ClipContext ctx) {
        IplProjectionRaycast.Plan plan = IplProjectionRaycast.plan((ServerLevel) (Object) this, ctx);
        if (plan == null) {
            return BlockGetter.super.clip(ctx);
        }
        BlockHitResult base;
        plan.mask();
        try {
            base = BlockGetter.super.clip(ctx);
        } finally {
            plan.unmask();
        }
        return plan.finish(base);
    }
}

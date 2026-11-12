package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import ipl.sable.client.IplClientProjections;
import ipl.sable.transit.IplProjectionRaycast;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Projection-aware raycasting on the client: every vanilla {@code clip} (crosshair pick,
 * block place/break, the physics staff, Jade, ...) sees each straddling ship as the set
 * of PIECES it physically is (native piece + one piece per portal session, chains
 * included), each hit only on its own blocks. See {@link IplProjectionRaycast}.
 *
 * <p>Merged-override pattern: Sable's overlay lives in the interface default's bytecode
 * ({@code BlockGetterMixin} overwrite), so {@code BlockGetter.super.clip} is the stock
 * result. Straddling/foreign ships are masked out of that stock pass and cast per frame;
 * nested frame casts carry {@code doNotProject} and fall straight through to stock. The
 * same algorithm serves the server ({@code ServerLevelMixin}).
 *
 * <p>Random animate ticks run without portal images ({@link IplClientProjections#withoutImages}):
 * Sable's sub-level animate tick would sample an image with its native pose, which turns into
 * a huge sampling box around the remote level's portal (see there).
 */
@Mixin(value = ClientLevel.class, priority = 1200)
public abstract class ClientLevelMixin implements BlockGetter {

    @Override
    public BlockHitResult clip(ClipContext ctx) {
        IplProjectionRaycast.Plan plan = IplProjectionRaycast.plan((ClientLevel) (Object) this, ctx);
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

    /** Wraps the whole method, Sable's TAIL hook ({@code sable$subLevelAnimateTick}) included. */
    @WrapMethod(method = "animateTick(III)V")
    private void ipl$animateTickWithoutImages(int x, int y, int z, Operation<Void> original) {
        IplClientProjections.withoutImages(() -> original.call(x, y, z));
    }
}

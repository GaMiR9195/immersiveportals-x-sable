package ipl.sable.mixin.client;

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
 * included), each cut exactly at its portal tubes. See {@link IplProjectionRaycast}.
 *
 * <p>Merged-override pattern: Sable's overlay lives in the interface default's bytecode
 * ({@code BlockGetterMixin} overwrite), so {@code BlockGetter.super.clip} is the stock
 * result. Straddling/foreign ships are masked out of that stock pass and cast per frame;
 * nested frame casts carry {@code doNotProject} and fall straight through to stock.
 *
 * <p>Русский комментарий: раньше здесь было два прохода (выкинуть "проходную" половину
 * из стокового результата + отдельный пик прямых проекций) — цепочки и несколько
 * порталов не поддерживались. Теперь вся логика в общем классе, тот же код работает на
 * сервере ({@code IplServerProjectionClipMixin}).
 */
@Mixin(value = ClientLevel.class, priority = 1200)
public abstract class IplProjectionAwareClipMixin implements BlockGetter {

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
}

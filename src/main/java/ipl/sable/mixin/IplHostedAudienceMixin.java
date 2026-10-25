package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.IplWorldFrameContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Predicate;

/**
 * The AUDIENCE of hosted ship code is the world the ship flies through.
 *
 * <p>No player ever stands in {@code ipl_sable:sublevels}, so {@code level.players()} on the
 * hosting level is always empty. Ship code that addresses "the players of my level" —
 * per-player particle/packet loops ({@code for (p : level.players()) level.sendParticles(p,
 * ...)}), nearest-player checks, proximity effects — silently reached nobody. In stock
 * Sable the plot shares the players' level, so it reaches them.
 *
 * <p>While hosted code runs with an armed world frame ({@link IplWorldFrameContext}: plot
 * block-entity ticks, physics ticks, hosted interactions), the hosting level's player
 * accessors answer with the contextual parent's players. Outside an armed frame nothing
 * changes (no guessed dimension). Vanilla's own bookkeeping reads the {@code players} field
 * directly and is unaffected.
 */
@Mixin(ServerLevel.class)
public abstract class IplHostedAudienceMixin {

    @Unique
    @Nullable
    private ServerLevel ipl$audienceWorld() {
        ServerLevel self = (ServerLevel) (Object) this;
        if (!IplDimAgnostic.isHostingLevel(self)) return null;
        ServerLevel context = IplWorldFrameContext.current();
        return context == null || context == self || IplDimAgnostic.isHostingLevel(context) ? null : context;
    }

    @ModifyReturnValue(method = "players()Ljava/util/List;", at = @At("RETURN"), require = 1)
    private List<ServerPlayer> ipl$contextualPlayers(List<ServerPlayer> original) {
        if (!original.isEmpty()) return original;
        ServerLevel world = ipl$audienceWorld();
        return world != null ? world.players() : original;
    }

    @Inject(
        method = "getPlayers(Ljava/util/function/Predicate;I)Ljava/util/List;",
        at = @At("HEAD"), cancellable = true, require = 0
    )
    private void ipl$contextualPlayerQuery(
        Predicate<? super ServerPlayer> predicate, int maxResults,
        CallbackInfoReturnable<List<ServerPlayer>> cir
    ) {
        ServerLevel world = ipl$audienceWorld();
        if (world != null) cir.setReturnValue(world.getPlayers(predicate, maxResults));
    }
}

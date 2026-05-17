package ipl.sable.mixin.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import qouteall.imm_ptl.core.ClientWorldLoader;

/**
 * Route Sable's client-side {@code SubLevelContainer.getContainer(Level)} lookups to the
 * redirected dimension's container when we're inside an IP-redirected packet dispatch.
 *
 * <p><b>Why this is needed:</b> Sable's client packet handlers (e.g.
 * {@code ClientboundSableSnapshotDualPacket.handle}) ask Veil's {@link
 * foundry.veil.api.network.handler.PacketContext#level()} for the target level, then call
 * {@link SubLevelContainer#getContainer(Level)}. Veil's default {@code PacketContext.level()}
 * is:
 * <pre>
 *   default Level level() {
 *       Player p = this.player();
 *       return p == null ? null : p.level();
 *   }
 * </pre>
 * So it reads {@code player.level()} -- the player's <i>current</i> dimension. IP's
 * {@link qouteall.imm_ptl.core.ClientWorldLoader#withSwitchedWorldFailSoft} swaps
 * {@code Minecraft.getInstance().level} during a redirected dispatch but does <b>not</b>
 * touch {@code player.level()}. Result: a wrapped Sable packet for an overworld sub-level
 * arriving at a client whose player is currently in the nether asks for the nether's
 * container, doesn't find the sub-level, logs "Received a sub-level movement packet for a
 * non-existent sub-level", and the airship doesn't move.
 *
 * <p><b>The fix:</b> when {@link ClientWorldLoader#getIsWorldSwitched()} is true,
 * substitute the level argument to {@code getContainer} with
 * {@code Minecraft.getInstance().level} -- which <i>is</i> the redirected dim during
 * {@code withSwitchedWorldFailSoft} / {@code withSwitchedWorld}. Outside an IP-swap this
 * is a no-op (flag is false, original argument passes through).
 *
 * <p><b>Why not {@code PacketRedirectionClient.getIsProcessingRedirectedMessage()}:</b>
 * I tried that first. It doesn't work for the typical NeoForge custom-payload dispatch
 * path because NeoForge defers the payload handler via {@code Minecraft.execute(...)}.
 * IP's {@code MixinMinecraft_RedirectedPacket} wraps that deferred runnable so it
 * re-enters {@code withSwitchedWorldFailSoft} when dequeued, but by the time the
 * wrapped runnable actually runs, the outer {@code handleRedirectedPacket}'s
 * {@code finally} block has already cleared the {@code clientTaskRedirection}
 * thread-local. So inside Sable's payload handler,
 * {@code getIsProcessingRedirectedMessage()} returns {@code false} even though
 * {@code Minecraft.level} <i>is</i> swapped to the source dim. The
 * {@code isWorldSwitched} flag, in contrast, is set inside {@code withSwitchedWorld}
 * itself and stays true for the duration of the swap regardless of which thread-local
 * happens to be live -- which is exactly what we need.
 *
 * <p><b>Why hook getContainer and not PacketContext.level():</b> a single hook on Sable's
 * container lookup covers every Sable packet handler that uses
 * {@code SubLevelContainer.getContainer(Level)} (which is all of them, per the design
 * pattern). Hooking Veil's PacketContext.level() would also work but would affect every
 * Veil packet handler, including those from other mods using Veil that have no
 * cross-dim semantics -- broader blast radius for the same benefit in our config.
 *
 * <p>Loaded only on the client (registered under the {@code "client"} key in
 * {@code ipl_sable.mixins.json}) since {@link Minecraft#getInstance()} would NPE
 * server-side.
 */
@Pseudo
@Mixin(value = SubLevelContainer.class, remap = false)
public abstract class SableContainerClientRedirectMixin {

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-sable-container-redirect");

    @Unique
    private static long ipl$logCounter = 0;

    @ModifyVariable(
        method = "getContainer(Lnet/minecraft/world/level/Level;)Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;",
        at = @At("HEAD"),
        argsOnly = true,
        remap = false,
        require = 1
    )
    private static Level ipl$redirectLevelForCrossDim(Level level) {
        boolean isSwitched = ClientWorldLoader.getIsWorldSwitched();
        Level mcLevel = Minecraft.getInstance().level;
        Level result = level;
        boolean substituted = false;

        if (isSwitched && mcLevel != null && mcLevel != level) {
            result = mcLevel;
            substituted = true;
        }

        // Log every 20th invocation to keep volume manageable while still showing the pattern.
        // Always log when substitution actually fires (rare event, useful to capture exactly).
        long c = ipl$logCounter++;
        if (substituted || (c % 20) == 0) {
            IPL$LOG.info(
                "[IPL-SABLE-CONT] getContainer call#{} arg={} mcLevel={} isWorldSwitched={} substituted={} -> {}",
                c,
                level == null ? "null" : level.dimension().location(),
                mcLevel == null ? "null" : mcLevel.dimension().location(),
                isSwitched,
                substituted,
                result == null ? "null" : result.dimension().location()
            );
        }

        return result;
    }
}

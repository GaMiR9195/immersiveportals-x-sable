package ipl.sable.mixin.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import qouteall.imm_ptl.core.network.PacketRedirectionClient;

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
 * <p><b>The fix:</b> when {@link PacketRedirectionClient#getIsProcessingRedirectedMessage()}
 * is true, substitute the level argument to {@code getContainer} with
 * {@code Minecraft.getInstance().level} -- which <i>is</i> the redirected dim during
 * {@code withSwitchedWorldFailSoft}. Outside redirected dispatch this is a no-op (the
 * check returns false, original argument passes through).
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

    @ModifyVariable(
        method = "getContainer(Lnet/minecraft/world/level/Level;)Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;",
        at = @At("HEAD"),
        argsOnly = true,
        remap = false,
        require = 1
    )
    private static Level ipl$redirectLevelForCrossDim(Level level) {
        if (PacketRedirectionClient.getIsProcessingRedirectedMessage()) {
            Level redirected = Minecraft.getInstance().level;
            if (redirected != null) {
                return redirected;
            }
        }
        return level;
    }
}

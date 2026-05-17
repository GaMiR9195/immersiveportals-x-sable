package ipl.sable.mixin.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import ipl.sable.IplSableLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ClientWorldLoader;

/**
 * Tick Sable's {@link SubLevelContainer} on every loaded {@link ClientLevel}, not just the
 * one matching {@code Minecraft.level}. Required for cross-dim sub-level interpolation.
 *
 * <p><b>The bug this fixes:</b> Sable's
 * {@code dev.ryanhcode.sable.mixin.plot.MinecraftMixin.sable$tickPlotContainer} hooks
 * {@code Minecraft.tick()} with:
 * <pre>
 *   if (this.level != null) {
 *       SableClient.NETWORK_EVENT_LOOP.runAllTasks();
 *       if (!this.pause) {
 *           ((SubLevelContainerHolder) this.level).sable$getPlotContainer().tick();
 *       }
 *   }
 * </pre>
 * It only ticks the {@code ClientSubLevelContainer} attached to the <i>current</i>
 * {@code Minecraft.level}. Inside that container's {@code tick()} is
 * {@code interpolation.tick()} which advances the per-sub-level pose interpolator. When
 * IP is showing a cross-dim portal view, the player is in dim A but they're watching a
 * sub-level in dim B; the dim-B {@code ClientSubLevelContainer} accumulates incoming
 * snapshots from the server (via our cross-dim packet routing) but its interpolation
 * never advances -- so the rendered pose stays frozen at whatever was last interpolated.
 *
 * <p>Observed symptom: airship visible through the portal but appears motionless. On
 * dim transition back to A, Minecraft.level becomes A's ClientLevel again, A's container
 * starts ticking, interpolation suddenly advances to the latest accumulated snapshot,
 * and the airship visibly snaps to its current pose. The "snap on return" was the dead
 * giveaway that snapshots were arriving but interpolation wasn't running.
 *
 * <p><b>The fix:</b> after Sable's hook runs (at {@code Minecraft.tick} TAIL), iterate
 * IP's alt {@code ClientLevel}s (everything in {@link ClientWorldLoader#getClientWorlds})
 * other than the current one and call {@code container.tick()} on each. That advances
 * the interpolation state on alt-dim containers so a sub-level visible cross-dim through
 * a portal renders with live motion.
 *
 * <p>Cost is bounded: per non-current ClientLevel per tick, {@code container.tick()} just
 * advances an interpolation tick counter and runs through whichever sub-levels exist
 * there. Cheap if no sub-levels live in that dim.
 *
 * <p>Client-only mixin (under the {@code "client"} key in {@code ipl_sable.mixins.json}).
 */
@Mixin(Minecraft.class)
public abstract class SableAltContainerTickMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void ipl$tickAltSableContainers(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        ClientLevel current = mc.level;
        if (current == null) return;
        if (!ClientWorldLoader.getIsInitialized()) return;

        int tickedCount = 0;
        try {
            for (ClientLevel alt : ClientWorldLoader.getClientWorlds()) {
                if (alt == current) continue;
                SubLevelContainer container = SubLevelContainer.getContainer((Level) alt);
                if (container == null) continue;
                container.tick();
                tickedCount++;
            }
        } catch (Throwable t) {
            IplSableLog.VEIL_CTX.warn("[IPL-SABLE-ALT-TICK] alt container tick failed", t);
        }

        // Log infrequently when actually ticking something so we can see this hook in action
        // during cross-dim view. Sampled to avoid log flood.
        if (tickedCount > 0 && (mc.gameRenderer != null) && (System.nanoTime() & 0x3FFFFFFFL) < 50_000_000L) {
            // ~1.5% sample rate based on nanoTime LSBs -- cheap to filter
            IplSableLog.VEIL_CTX.info(
                "[IPL-SABLE-ALT-TICK] ticked {} alt sub-level container(s) (current dim={})",
                tickedCount,
                current.dimension().location()
            );
        }
    }
}

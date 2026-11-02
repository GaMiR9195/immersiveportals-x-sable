package ipl.sable.mixin.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import ipl.sable.client.IplDimSwitchGuard;
import ipl.sable.client.IplParentDimSync;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ClientWorldLoader;

/**
 * Sub-level containers of every client level loaded by Immersive Portals tick once per client
 * tick, at the same point as Sable's own tick of {@code Minecraft.level}.
 *
 * <p>Sable ticks only the current level's container (its {@code MinecraftMixin}, before
 * {@code Tutorial.onLookAt}, skipped while paused). That tick advances the interpolation clock
 * and the {@code lastPose -> logicalPose} pair the renderer lerps between, so a container that
 * is not ticked freezes and one ticked twice skips ahead.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-sable");

    @Shadow @Nullable public ClientLevel level;

    @Shadow private volatile boolean pause;

    /** Время кадра для {@link IplDimSwitchGuard}: тики без паузы плюс частичный тик кадра. */
    @Inject(method = "tick", at = @At("HEAD"))
    private void ipl$countTick(CallbackInfo ci) {
        if (!this.pause) IplDimSwitchGuard.onClientTick();
    }

    /** Начало кадра: все тики кадра уже прошли, частичный тик известен. */
    @Inject(method = "runTick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"))
    private void ipl$beginFrame(CallbackInfo ci) {
        IplDimSwitchGuard.beginFrame();
    }

    /**
     * Русский: раньше остальные уровни тикали в TAIL. IP переключает измерение игрока внутри тика
     * (после {@code ClientLevel.tick}), и в таком тике старый уровень тикал дважды, а новый — ни
     * разу: корабль в измерении, куда входит игрок, на тик замирал и отскакивал назад. Теперь все
     * уровни тикают в одной точке до любых переключений, ровно один раз и с учётом паузы.
     */
    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/tutorial/Tutorial;onLookAt(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/world/phys/HitResult;)V"
        ),
        require = 1
    )
    private void ipl$tickOtherContainers(CallbackInfo ci) {
        ClientLevel current = this.level;
        if (current == null || this.pause || !ClientWorldLoader.getIsInitialized()) return;
        for (ClientLevel other : ClientWorldLoader.getClientWorlds()) {
            if (other == current) continue;
            SubLevelContainer container = SubLevelContainer.getContainer(other);
            if (container == null) continue;
            try {
                container.tick();
            } catch (Throwable t) {
                // Один сломанный уровень не должен ронять тик остальных.
                IPL$LOG.warn("[IPL-SABLE] sub-level container tick failed for {}", other.dimension().location(), t);
            }
        }
    }

    /** Transit bookkeeping runs after the whole tick, when every container is up to date. */
    @Inject(method = "tick", at = @At("TAIL"))
    private void ipl$applyTransitHandoffs(CallbackInfo ci) {
        if (this.level == null) return;
        try {
            IplParentDimSync.applyPendingHandoffs();
        } catch (Throwable t) {
            IPL$LOG.warn("[IPL-SABLE] transit handoff update failed", t);
        }
    }
}

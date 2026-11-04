package ipl.sable.mixin.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import ipl.sable.client.IplParentDimSync;
import ipl.sable.client.IplSharedClock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ClientWorldLoader;

/**
 * Sub-level containers of every client level loaded by Immersive Portals tick once per client
 * tick, at the same point as Sable's own tick of {@code Minecraft.level}.
 *
 * <p>Sable ticks only the current level's container (its {@code MinecraftMixin}, before
 * {@code Tutorial.onLookAt}, skipped while paused). That tick moves the
 * {@code lastPose -> logicalPose} pair the renderer lerps between, so a container that is not
 * ticked freezes and one ticked twice skips ahead. The shared interpolation clock advances on
 * the first container tick of each client tick ({@link IplSharedClock}), so every level samples
 * the same pointer. Ticking at the TAIL instead would break the tick in which IP switches the
 * player's dimension (after {@code ClientLevel.tick}): the old level would tick twice and the new
 * one not at all.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Shadow @Nullable public ClientLevel level;

    @Shadow private volatile boolean pause;

    @Inject(method = "tick", at = @At("HEAD"), require = 1)
    private void ipl$beginClientTick(CallbackInfo ci) {
        IplSharedClock.beginClientTick();
    }

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
            if (container != null) container.tick();
        }
    }

    /** Transit bookkeeping runs after the whole tick, when every container is up to date. */
    @Inject(method = "tick", at = @At("TAIL"))
    private void ipl$applyTransitHandoffs(CallbackInfo ci) {
        if (this.level != null) IplParentDimSync.applyPendingHandoffs();
    }
}

package ipl.sable.mixin.client;

import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.client.IplHostedFlywheelVisuals;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Hosted ships' membership in this manager's visual scene, reconciled as the manager begins
 * a frame (render thread, before its frame plan drains the add/remove queues). See
 * {@link IplHostedFlywheelVisuals}. The membership set belongs to the manager instance, so a
 * recreated manager starts empty and is repopulated like Flywheel repopulates its entities.
 */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl", remap = false)
public abstract class IplHostedFlywheelSceneMixin {

    @Shadow @Final private LevelAccessor level;

    @Unique
    private final Set<SubLevel> ipl$hostedShips = Collections.newSetFromMap(new IdentityHashMap<>());

    @Inject(method = "beginFrame", at = @At("HEAD"), require = 1, remap = false)
    private void ipl$reconcileHostedShips(RenderContext context, CallbackInfo ci) {
        IplHostedFlywheelVisuals.reconcile((VisualizationManager) (Object) this, this.level, this.ipl$hostedShips);
    }
}

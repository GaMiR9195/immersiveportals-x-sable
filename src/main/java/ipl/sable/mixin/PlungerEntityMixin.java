package ipl.sable.mixin;

import dev.simulated_team.simulated.content.entities.launched_plunger.LaunchedPlungerEntity;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code LaunchedPlungerEntity.tick} guards its TARGET_POS update with
 * {@code if (!level.isClientSide && other != null)} — on the client that condition never
 * passes, so every client tick locally clobbers the server-synced TARGET_POS with
 * {@code Vec3.ZERO}. The renderer only reads TARGET_POS as a fallback when the partner
 * plunger's client entity is missing or removed (which our plot-space entity tracking can
 * produce transiently), and in that exact case it draws the rope to the clobbered (0,0,0)
 * instead of the partner's synced plot position.
 *
 * <p>Fix: capture TARGET_POS at tick entry and restore it at tick exit when the client
 * zeroed a previously-valid target on a still-paired plunger (OTHER_PLUNGER_ID != -1).
 * Unpaired plungers keep the intended zeroing.
 */
@Pseudo
@Mixin(
    targets = "dev.simulated_team.simulated.content.entities.launched_plunger.LaunchedPlungerEntity",
    remap = false)
public abstract class PlungerEntityMixin {

    @Unique
    private static final boolean IPL$ENABLED =
        !"false".equals(System.getProperty("ipl.sable.plungerTargetFix"));

    @Unique
    private Vec3 ipl$savedTarget;

    @Inject(method = "tick", at = @At("HEAD"))
    private void ipl$captureTarget(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (IPL$ENABLED && self.level().isClientSide) {
            this.ipl$savedTarget = self.getEntityData().get(LaunchedPlungerEntity.TARGET_POS);
        }
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void ipl$restoreTarget(CallbackInfo ci) {
        Vec3 saved = this.ipl$savedTarget;
        this.ipl$savedTarget = null;
        if (saved == null || saved.equals(Vec3.ZERO)) return;
        SynchedEntityData data = ((Entity) (Object) this).getEntityData();
        if (data.get(LaunchedPlungerEntity.OTHER_PLUNGER_ID) == -1) return;
        if (data.get(LaunchedPlungerEntity.TARGET_POS).equals(Vec3.ZERO)) {
            data.set(LaunchedPlungerEntity.TARGET_POS, saved);
        }
    }
}

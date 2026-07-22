package foundry.veil.fabric.mixin.compat.cameratweaks;

import cameratweaks.ThirdPerson;
import foundry.veil.api.client.render.VeilLevelPerspectiveRenderer;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public class CameraTweaksCameraMixin {

    @Unique
    private static final ThirdPerson veil$PERSPECTIVE_THIRD_PERSON = new ThirdPerson();
    @Unique
    private static ThirdPerson veil$backupThirdPerson;

    @Inject(method = "setup", at = @At("HEAD"))
    public void setup(CallbackInfo ci) {
        if (VeilLevelPerspectiveRenderer.isRenderingPerspective()) {
            veil$backupThirdPerson = ThirdPerson.current;
            ThirdPerson.setCurrent(veil$PERSPECTIVE_THIRD_PERSON);
        }
    }

    @Inject(method = "setup", at = @At("RETURN"))
    public void reset(CallbackInfo ci) {
        if (VeilLevelPerspectiveRenderer.isRenderingPerspective()) {
            ThirdPerson.setCurrent(veil$backupThirdPerson);
            veil$backupThirdPerson = null;
        }
    }
}

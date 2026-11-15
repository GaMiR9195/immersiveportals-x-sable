package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.Camera;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Orientation state of a camera, for {@link qouteall.imm_ptl.core.render.PortalPassView}. */
@Mixin(Camera.class)
public interface IECameraOrientation {
    @Accessor("rotation")
    Quaternionf ip_rotation();
    
    @Accessor("forwards")
    Vector3f ip_forwards();
    
    @Accessor("up")
    Vector3f ip_up();
    
    @Accessor("left")
    Vector3f ip_left();
    
    @Accessor("xRot")
    float ip_getXRot();
    
    @Accessor("xRot")
    void ip_setXRot(float xRot);
    
    @Accessor("yRot")
    float ip_getYRot();
    
    @Accessor("yRot")
    void ip_setYRot(float yRot);
}

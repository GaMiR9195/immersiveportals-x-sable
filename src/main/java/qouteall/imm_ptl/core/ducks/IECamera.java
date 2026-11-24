package qouteall.imm_ptl.core.ducks;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

public interface IECamera {
    void ip_resetState(Vec3 pos, ClientLevel currWorld);
    
    void portal_setPos(Vec3 pos);
    
    float ip_getCameraY();
    
    float ip_getLastCameraY();
    
    void ip_setCameraY(float cameraY, float lastCameraY);
    
    void portal_setFocusedEntity(Entity arg);
    
    /** Takes the state {@code view} was set up with (rotation, position, detachment, eye height) for {@code level}. */
    void ip_mirror(Camera view, BlockGetter level, Entity focusedEntity, float partialTick);
    
    /** Whether the camera was set up detached, ignoring {@code isDetached} overrides. */
    boolean ip_wasSetUpDetached();
}

package qouteall.imm_ptl.core.render;

import ipl.sable.render.IplForeignPerspective;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

import java.util.function.DoubleSupplier;

/**
 * Camera inputs that the player's view evaluates once per frame and every other view of the frame
 * (portal passes, camera probes) reuses.
 *
 * <p>Camera mods shape the view through NeoForge's camera angle event (shake, roll, sway) and
 * through the projection stage (FOV, bobbing, hurt tilt). Their listeners may keep state per call:
 * evaluated again in every portal pass they would give each pass a slightly different camera, and
 * the portal image would no longer line up with the view around it. So the player's view records
 * the results and the other views replay them:
 * <ul>
 *   <li>camera angles: the yaw and pitch offsets and the roll the listeners produced;</li>
 *   <li>FOV and the final projection, for portal passes that see the view's bobbing unscaled.</li>
 * </ul>
 * Renders from other mods' cameras ({@link IplForeignPerspective}) are left alone.
 * Render thread only.
 */
public final class FrameCameraInputs {
    /** Inside the outermost world render of the frame, the player's view. */
    private static boolean recording;
    private static boolean viewDone;
    
    private static boolean hasAngles;
    private static float yawOffset;
    private static float pitchOffset;
    private static float roll;
    
    private static double fov = Double.NaN;
    private static @Nullable Matrix4f projection;
    
    private FrameCameraInputs() {}
    
    public static void onFrameStart() {
        recording = false;
        viewDone = false;
        fov = Double.NaN;
        projection = null;
    }
    
    /** {@code GameRenderer.renderLevel} begins; {@code depth} is 1 when it is not nested. */
    public static void onRenderLevelBegin(int depth) {
        if (depth == 1 && !viewDone && !IplForeignPerspective.isRendering()) {
            recording = true;
        }
    }
    
    public static void onRenderLevelEnd(int depth) {
        if (depth == 1 && recording) {
            recording = false;
            viewDone = true;
        }
    }
    
    /**
     * The camera angle event of {@code camera}: posted by the view's main camera and recorded,
     * replayed for any other camera once the view has recorded it.
     */
    public static void computeAngles(Camera camera, ViewportEvent.ComputeCameraAngles event, Runnable post) {
        if (IplForeignPerspective.isRendering()) {
            post.run();
            return;
        }
        if (isRecordingView() && camera == Minecraft.getInstance().gameRenderer.getMainCamera()) {
            float baseYaw = event.getYaw();
            float basePitch = event.getPitch();
            post.run();
            yawOffset = event.getYaw() - baseYaw;
            pitchOffset = event.getPitch() - basePitch;
            roll = event.getRoll();
            hasAngles = true;
            return;
        }
        if (!hasAngles) {
            post.run();
            return;
        }
        event.setYaw(event.getYaw() + yawOffset);
        event.setPitch(event.getPitch() + pitchOffset);
        event.setRoll(roll);
    }
    
    /** {@code GameRenderer.getFov} of a world render. */
    public static double fov(DoubleSupplier compute) {
        if (isReplayingPass() && !Double.isNaN(fov)) {
            return fov;
        }
        double value = compute.getAsDouble();
        if (isRecordingView()) {
            fov = value;
        }
        return value;
    }
    
    /**
     * Whether the current portal pass takes the view's final projection, so the bobbing stage
     * (vanilla's and that of mods hooked into it) is not evaluated again. A pass that scales the
     * bobbing (a scaling portal) or has it off computes its own.
     */
    public static boolean replaysProjection() {
        return projection != null
            && isReplayingPass()
            && WorldRenderInfo.isViewBobbingEnabled()
            && PortalRendering.getExtraModelViewScaling() == 1.0;
    }
    
    /**
     * The projection a world render applies, given the one it computed. Replayed in place:
     * {@code GameRenderer.renderLevel} hands the same matrix on to the level render, so the
     * terrain, the clouds and the clip planes of the pass use it too.
     */
    public static Matrix4f projection(Matrix4f computed) {
        if (replaysProjection()) {
            return computed.set(projection);
        }
        if (isRecordingView()) {
            projection = new Matrix4f(computed);
        }
        return computed;
    }
    
    private static boolean isRecordingView() {
        return recording && PortalPassView.getGameRenderDepth() == 1;
    }
    
    private static boolean isReplayingPass() {
        return PortalRendering.isRendering() && !IplForeignPerspective.isRendering();
    }
}

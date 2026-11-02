package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.client.IplStraddleStaffPick;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

/**
 * Клиентский обработчик посоха: выбор цели через цепочку порталов и фокус камеры.
 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler", remap = false)
public abstract class StaffClientHandlerMixin {

    // ---- Захват через порталы ----
    // Replace Simulated's one-world pick at source. The handler receives the exact plot-coordinate
    // hit Sable expects, while the capture state records portal chain before session construction.

    @WrapOperation(
        method = "onItemUsed",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;pick(DFZ)Lnet/minecraft/world/phys/HitResult;"
        ),
        require = 0
    )
    private HitResult ipl$portalPick(
        LocalPlayer player, double range, float tickDelta, boolean fluids, Operation<HitResult> original
    ) {
        HitResult local = original.call(player, range, tickDelta, fluids);
        HitResult projection = IplStraddleStaffPick.pickStraddleProjections(player, range, tickDelta);
        IplStraddleStaffPick.PortalTarget through =
            IplStraddleStaffPick.pickThroughPortals(player, range, tickDelta);
        // NEAREST-WINS. A through-portal target must never beat a candidate the player can
        // already see in their own world. The old code returned `through` unconditionally,
        // which is why a build standing next to (or on top of) the player got bound to the
        // portal and held through it, and why grabbing a body co-located with the player
        // snapped the grab onto the portal. The comparison is between VISIBLE ray lengths,
        // the only frame in which a local hit and a through-portal hit are comparable.
        double localVisible = IplStraddleStaffPick.visibleRayLength(player, local, tickDelta);
        double projectionVisible = IplStraddleStaffPick.visibleRayLength(player, projection, tickDelta);
        double nearestHere = Math.min(localVisible, projectionVisible);
        if (through != null && through.visibleDistance() <= nearestHere + 1.0e-4) {
            return through.hit();
        }
        // Rejected: pickThroughPortals records its result eagerly, so the speculative
        // capture has to be dropped here. Leaving it pending is what let
        // startDraggingSubLevel seed a portal chain for a body grabbed locally.
        if (through != null) IplStraddleStaffPick.discardTarget(through);
        if (projection != null) {
            IplStraddleStaffPick.rememberLocalProjection(player, projection);
            return projection;
        }
        IplStraddleStaffPick.rememberLocalProjection(player, local);
        return local;
    }

    /**
     * A LOCK is a pick too. Simulated only seeds portal state on the drag path, so a lock
     * placed through a portal published no chain and its beam was drawn straight to the
     * body's raw world position. Seeding the same chain here makes "fixate through a
     * portal" behave optically identically to "grab through a portal".
     */
    @Inject(method = "lockSubLevel", at = @At("HEAD"), require = 0)
    private void ipl$captureLockPath(
        SubLevel sub, net.minecraft.world.phys.Vec3 hitLocation,
        LocalPlayer player, InteractionHand hand, CallbackInfo ci
    ) {
        if (sub instanceof ClientSubLevel clientSub) {
            IplStraddleStaffPick.beginLock(clientSub);
        }
    }

    @Inject(method = "startDraggingSubLevel", at = @At("HEAD"), require = 0)
    private void ipl$capturePortalPath(
        SubLevel sub, BlockPos blockPos, LocalPlayer player, InteractionHand hand, CallbackInfo ci
    ) {
        if (sub instanceof ClientSubLevel clientSub) {
            IplStraddleStaffPick.beginDrag(clientSub);
        }
    }

    /**
     * After Simulated built the drag session (with its native-pose distance), replace the
     * hold distance with the true visible pick distance for through-portal / projection grabs.
     * Fixes the maxed-out scroll on a cross-dimension grab and the wrong hold point on an image.
     */
    @Inject(method = "startDraggingSubLevel", at = @At("TAIL"), require = 0)
    private void ipl$applyGrabDistance(
        SubLevel sub, BlockPos blockPos, LocalPlayer player, InteractionHand hand, CallbackInfo ci
    ) {
        IplStraddleStaffPick.applyGrabDistance(
            (dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler) (Object) this, sub);
    }

    @Inject(method = "stopDragging", at = @At("HEAD"), require = 0)
    private void ipl$clearCaptureBeforeStop(CallbackInfo ci) {
        IplStraddleStaffPick.clearDragTargets();
    }

    /**
     * Beam node density derives from the length passed here: stock computes it with
     * {@code distanceSquaredWithSubLevels} on raw endpoints, which is cross-frame garbage
     * for a through-portal grab (wrong node count from the first frame). Seed the TRUE
     * physical length instead — the resolved route length when one exists, else the pick
     * ray's measured length — and bind the beam to its owner for the per-tick length feed.
     */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
        method = "updateBeam",
        at = @At(
            value = "NEW",
            target = "dev/simulated_team/simulated/content/physics_staff/PhysicsStaffClientHandler$PhysicsBeam"
        ),
        require = 0
    )
    private dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler.PhysicsBeam ipl$seedBeamLength(
        net.minecraft.world.phys.Vec3 start, net.minecraft.world.phys.Vec3 end, double length,
        Operation<dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler.PhysicsBeam> original,
        net.minecraft.world.level.Level level, java.util.UUID uuid,
        net.minecraft.world.phys.Vec3 startArg, net.minecraft.world.phys.Vec3 endArg
    ) {
        double best = ipl.sable.client.IplStaffBeamRoutes.knownLength(uuid);
        if (Double.isNaN(best)) {
            ClientSubLevel sub = ipl.sable.client.IplStaffPortalBeamRenderer.findHostedSubLevel(end);
            if (sub != null) {
                best = IplStraddleStaffPick.pickDistance(sub.getUniqueId());
            }
        }
        dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler.PhysicsBeam beam =
            original.call(start, end, Double.isNaN(best) ? length : best);
        ipl.sable.client.IplStaffBeamRoutes.registerBeamOwner(beam, uuid);
        return beam;
    }

    // ---- Фокус камеры ----
    // Preserve Simulated's focus math; IP's entity-visibility hook is not a camera mode.

    @WrapOperation(method = "getStaffFocusPos", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;getMainCamera()Lnet/minecraft/client/Camera;"), require = 1)
    private static Camera ipl$rootCamera(GameRenderer renderer, Operation<Camera> original) {
        return WorldRenderInfo.isRendering() && RenderStates.originalCamera != null
            ? RenderStates.originalCamera : original.call(renderer);
    }

    @WrapOperation(method = "getStaffFocusPos", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Camera;isDetached()Z"), require = 1)
    private static boolean ipl$physicalCameraMode(Camera camera, Operation<Boolean> original) {
        // MixinCamera.onIsThirdPerson returns true to show players THROUGH portals,
        // even on originalCamera. Calling it here selected the body-space origin
        // and cached that wrong origin for the entire physical beam frame.
        return !Minecraft.getInstance().options.getCameraType().isFirstPerson();
    }

    @Inject(method = "onRender", at = @At("HEAD"), cancellable = true, require = 1)
    private void ipl$ownedBeamPass(PoseStack poses, CallbackInfo ci) {
        // The NeoForge stage renders every beam. Do not run stock's global Catnip
        // draw() here: it can flush another world's pending selection effects.
        ci.cancel();
    }
}

package ipl.sable.mixin;

import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.simulated_team.simulated.config.server.physics.SimPhysics;
import dev.simulated_team.simulated.service.SimConfigService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import ipl.sable.duck.IplStaffDragSessionControl;

/**
 * Authoritative replacement for Simulated's PD motor tick. This is intentionally an overwrite:
 * wrapping Quaternion.transformInverse was too late and let stock frame assumptions leak into
 * constraint setup and following packets.
 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler$DragSession", remap = false)
public abstract class IplStaffDragSessionOverwriteMixin implements IplStaffDragSessionControl {

    @Shadow(remap = false) @Final private java.util.UUID playerUUID;
    @Shadow(remap = false) @Final private Vector3d playerRelativeGoal;
    @Shadow(remap = false) @Final private Vector3d localGoal;
    @Shadow(remap = false) @Final private Quaterniond orientation;
    @Shadow(remap = false) @Final private ServerSubLevel subLevel;
    @Shadow(remap = false) private PhysicsConstraintHandle constraint;
    @Shadow(remap = false) private void attachConstraint(SubLevelPhysicsSystem physicsSystem) {}

    /**
     * @author IPL-Sable
     * @reason Goal arrives pre-mapped from client; install exact PD target without stock frame recovery.
     */
    @Overwrite(remap = false)
    private void physicsTick(SubLevelPhysicsSystem physicsSystem) {
        if (this.subLevel.isRemoved()) return;

        if (this.constraint != null) {
            this.constraint.remove();
            this.constraint = null;
        }
        this.attachConstraint(physicsSystem);

        net.minecraft.server.level.ServerLevel hosting =
            (net.minecraft.server.level.ServerLevel) this.subLevel.getLevel();
        ServerPlayer player = hosting.getServer().getPlayerList().getPlayer(this.playerUUID);
        if (player == null || this.constraint == null) return;

        SimPhysics config = SimConfigService.INSTANCE.server().physics;
        for (ConstraintJointAxis axis : ConstraintJointAxis.ANGULAR) {
            this.constraint.setMotor(axis, 0.0, config.physicsStaffAngularStiffness.getF(),
                config.physicsStaffAngularDamping.getF(), false, 0.0);
        }

        double partial = physicsSystem.getPartialPhysicsTick();
        double eyeX = Mth.lerp(partial, player.xOld, player.getX());
        double eyeY = Mth.lerp(partial, player.yOld, player.getY()) + player.getEyeHeight();
        double eyeZ = Mth.lerp(partial, player.zOld, player.getZ());
        this.localGoal.set(this.playerRelativeGoal).add(eyeX, eyeY, eyeZ);

        // The absolute cursor point is in the PLAYER's world frame; fold it through the
        // grab chain (the event-sourced portal path between player and body) into the
        // body's parent frame, immediately before Simulated converts world space into
        // its constraint-local motor target.
        net.minecraft.world.phys.Vec3 goal = ipl.sable.transit.IplGrabChain.mapGoal(
            player, this.subLevel, new net.minecraft.world.phys.Vec3(
                this.localGoal.x, this.localGoal.y, this.localGoal.z
            )
        );
        this.localGoal.set(goal.x, goal.y, goal.z);
        this.orientation.transformInverse(this.localGoal);

        this.constraint.setMotor(ConstraintJointAxis.LINEAR_X, this.localGoal.x,
            config.physicsStaffLinearStiffness.getF(), config.physicsStaffLinearDamping.getF(), false, 0.0);
        this.constraint.setMotor(ConstraintJointAxis.LINEAR_Y, this.localGoal.y,
            config.physicsStaffLinearStiffness.getF(), config.physicsStaffLinearDamping.getF(), false, 0.0);
        this.constraint.setMotor(ConstraintJointAxis.LINEAR_Z, this.localGoal.z,
            config.physicsStaffLinearStiffness.getF(), config.physicsStaffLinearDamping.getF(), false, 0.0);
    }

    /** Keep held orientation continuous when the sub-level parent crosses a rotated portal. */
    @Override
    public void ipl$reframeAfterTransit(org.joml.Quaterniondc exitOrientation) {
        this.orientation.set(new Quaterniond(exitOrientation).mul(this.orientation));
    }

    /** Keep the stored cursor vector continuous when the dragging player crosses a portal. */
    @Override
    public void ipl$rotateRelativeGoal(org.joml.Quaterniondc portalRotation) {
        portalRotation.transform(this.playerRelativeGoal);
    }

    /**
     * Simulated removes sessions without stopDragging when the player logs off or stops
     * holding the staff; the grab chain must die with its session, never outlive it.
     */
    @org.spongepowered.asm.mixin.injection.Inject(
        method = "onRemoved", at = @org.spongepowered.asm.mixin.injection.At("HEAD"),
        remap = false, require = 0
    )
    private void ipl$endChainOnRemoval(
        org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci
    ) {
        net.minecraft.server.level.ServerLevel hosting =
            (net.minecraft.server.level.ServerLevel) this.subLevel.getLevel();
        if (hosting != null && hosting.getServer() != null) {
            ipl.sable.transit.IplGrabChain.end(hosting.getServer(), this.playerUUID);
        }
    }
}

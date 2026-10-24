package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import ipl.sable.dim.IplEnvironmentFrame;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Pressure, gravity and magnetic field belong to the sampled parent world.
 * This patches Sable's shared API, not individual propellers/balloon materials.
 */
@Pseudo
@Mixin(value = DimensionPhysicsData.class, remap = false)
public abstract class IplDimensionEnvironmentMixin {
    @WrapMethod(method = "getGravity(Lnet/minecraft/world/level/Level;Lorg/joml/Vector3dc;Lorg/joml/Vector3d;)Lorg/joml/Vector3d;")
    private static Vector3d ipl$gravity(
        Level level, Vector3dc position, Vector3d dest, Operation<Vector3d> original
    ) {
        var sample = IplEnvironmentFrame.resolve(level, position);
        return original.call(sample.level(), sample.position(), dest);
    }

    @WrapMethod(method = "getAirPressure")
    private static double ipl$pressure(Level level, Vector3dc position, Operation<Double> original) {
        var sample = IplEnvironmentFrame.resolve(level, position);
        return original.call(sample.level(), sample.position());
    }

    @org.spongepowered.asm.mixin.injection.ModifyVariable(
        method = {"of", "getDefault"}, at = @org.spongepowered.asm.mixin.injection.At("HEAD"),
        argsOnly = true, ordinal = 0
    )
    private static Level ipl$environmentData(Level level) {
        Level parent = IplEnvironmentFrame.contextualLevel(level);
        return parent == null ? level : parent;
    }
}
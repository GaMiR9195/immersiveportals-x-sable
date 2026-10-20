package dev.simulated_team.simulated.ponder.instructions;

import net.createmod.catnip.math.VecHelper;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.instruction.PonderInstruction;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class ColoredValueSlotInstruction extends PonderInstruction {

    public ColoredValueSlotInstruction(final SceneBuilder scene, final Vec3 location, final Direction side, final Vec3 rotation, final PonderPalette color, final int duration) {
        final Vec3 vec = location.add(Vec3.atLowerCornerOf(side.getNormal())
                .scale(-3 / 128f));
        final Vec3 expands = VecHelper.axisAlingedPlaneOf(side)
                .scale(11 / 128f);

        final AABB point = new AABB(vec, vec);
        final AABB expanded = point.inflate(expands.x, expands.y, expands.z);

        scene.addInstruction(new OBBOutlineInstruction(expanded, rotation, false, color, expanded.toString(), duration));
    }

    @Override
    public boolean isComplete() {
        return true;
    }

    @Override
    public void tick(final PonderScene scene) {
    }
}

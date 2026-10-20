package dev.simulated_team.simulated.mixin.physics_staff;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.simulated_team.simulated.index.SimItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsMixin {

    @Shadow public abstract int guiWidth();

    @Shadow @Final private Minecraft minecraft;

    @Shadow @Final private PoseStack pose;

    @Shadow public abstract void fill(int minX, int minY, int maxX, int maxY, int color);

    @WrapMethod(method = "renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;IIII)V")
    private void simulated$renderPhysicsStaff(final LivingEntity entity,
                                              final Level level,
                                              final ItemStack stack,
                                              final int x,
                                              final int y,
                                              final int seed,
                                              final int guiOffset,
                                              final Operation<Void> original) {
        final boolean isStaff = stack.is(SimItems.PHYSICS_STAFF);


        if (isStaff) {
            final Window window = Minecraft.getInstance().getWindow();
            final float scale = (float) window.getGuiScale();

            final Matrix4fc pose = this.pose.last().pose();
            final Vector3f position = pose.transformPosition(new Vector3f(x, y, 0));
            final Vector3f corner = pose.transformPosition(new Vector3f(x + 16, y + 16, 0));

            position.mul(scale);
            corner.mul(scale);

            final int slotHeight = (int) (corner.y - position.y);
            RenderSystem.enableScissor((int) position.x,
                    window.getHeight() - (int) position.y - slotHeight,
                    (int) (corner.x - position.x),
                    slotHeight);
        }

        original.call(entity, level, stack, x, y, seed, guiOffset);

        if (isStaff) {
            RenderSystem.disableScissor();
        }
    }

}

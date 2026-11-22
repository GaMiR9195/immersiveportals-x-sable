package ipl.sable.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The world a {@link LevelRenderer} draws: render-stage callbacks run for every IP world renderer. */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {

    @Accessor("level")
    ClientLevel ipl$getLevel();

    /** One entity of the pass, with Sable's placement of entities on sub-levels. */
    @Invoker("renderEntity")
    void ipl$renderEntity(Entity entity, double camX, double camY, double camZ, float partialTick,
                          PoseStack poseStack, MultiBufferSource buffers);
}

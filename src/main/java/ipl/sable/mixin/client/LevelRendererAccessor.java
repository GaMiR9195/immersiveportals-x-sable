package ipl.sable.mixin.client;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The world a {@link LevelRenderer} draws: render-stage callbacks run for every IP world renderer. */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {

    @Accessor("level")
    ClientLevel ipl$getLevel();
}
